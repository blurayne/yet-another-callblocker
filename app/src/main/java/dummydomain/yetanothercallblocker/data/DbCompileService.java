package dummydomain.yetanothercallblocker.data;

import android.content.Context;
import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.BuildConfig;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.source.ArchiveUtils;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceNames;
import dummydomain.yetanothercallblocker.data.source.SourceHttp;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.numbers.SliceReader;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.NumberFilter;
import dummydomain.yetanothercallblocker.sia.utils.FileUtils;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Builds the number database out of the sources, in the order the user put them in.
 *
 * <p>The first source carries the database itself - the whole archive, the way it has always
 * been downloaded. Every source after it is a layer on top: a file of numbers that are added
 * to what is already there, replacing what an earlier layer said about the same number. A
 * layer can also carry numbers to take out again, which is how a source says "this one is
 * not spam" about an entry another source brought.
 *
 * <p>Layers are put where the library keeps the updates it fetches itself, which is the same
 * question asked in the same order: the layer is looked at first, the database underneath
 * second. An entry with no ratings counts as not found, and that is what a number to delete
 * is written as.
 *
 * <p>The fetched layers are kept, so that they can be put back without asking their sources
 * again - which has to happen after the library's own update, because that update is merged
 * into the same place and would otherwise bury them.
 */
public class DbCompileService {

    private static final Logger LOG = LoggerFactory.getLogger(DbCompileService.class);

    /** Where the fetched layers are kept between compiles. */
    private static final String LAYERS_DIR_NAME = "sia-layers";

    private static final String LAYER_POSTFIX = ".dat";

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 120;

    /** How a compile went. */
    public enum Status {
        /** The database was built. */
        COMPILED,
        /** There is no source to build it from. */
        NO_SOURCES,
        /** The first source didn't hand over a database. */
        NO_BASE,
        FAILED
    }

    /** What a compile did. */
    public static class Result {

        public final Status status;
        /** How many sources went into the database. */
        public final int sources;
        /** How many of them couldn't be fetched. */
        public final int failed;
        /** What went wrong, in words, or null when nothing did. */
        public final String reason;

        Result(Status status, int sources, int failed) {
            this(status, sources, failed, null);
        }

        Result(Status status, int sources, int failed, String reason) {
            this.status = status;
            this.sources = sources;
            this.failed = failed;
            this.reason = reason;
        }

        public boolean isOk() {
            return status == Status.COMPILED;
        }

    }

    /** Reports what the build is doing and how far it has got. */
    public interface ProgressListener {

        /**
         * A step of the build has started.
         *
         * <p>Building is four jobs of quite different character - fetching the database,
         * unpacking it, reading every source into the table, and filtering it - and which of
         * them is running is worth more to someone watching a bar than the bar itself.
         *
         * @param titleResId what to call it
         */
        void onPhase(int titleResId);

        void onProgress(int current, int total);
    }

    /** Says which step started, when anyone is listening. */
    private static void phase(ProgressListener listener, int titleResId) {
        if (listener != null) listener.onPhase(titleResId);
    }

    private final Context context;
    private final Settings settings;
    private final SourceService sourceService;
    private final BuildLog buildLog;

    /**
     * @param context used for what a source's row ends up saying, in the user's language
     */
    public DbCompileService(Context context, Settings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.sourceService = YacbHolder.getSourceService();
        this.buildLog = new BuildLog(this.context);
    }

    /**
     * Fetches every source that is switched on and builds the database from them.
     *
     * @param listener notified as the sources are fetched, may be null
     */
    public Result compile(ProgressListener listener) {
        return compile(false, listener);
    }

    /**
     * @param force fetches the database again even when the one on the phone would do; what
     *              "fetch this source now" means when the user asks for it by hand
     */
    public Result compile(boolean force, ProgressListener listener) {
        LOG.debug("compile() started");

        long startTime = System.currentTimeMillis();

        buildLog.startRun(context.getString(R.string.build_log_run));

        List<NumberSource> sources = getSources();
        if (sources.isEmpty()) {
            LOG.info("compile() there are no sources to build the database from");

            buildLog.line(BuildLog.MAIN, context.getString(R.string.sources_none_enabled));

            return new Result(Status.NO_SOURCES, 0, 0);
        }

        int total = sources.size();
        int failed = 0;

        /*
         * The source that carries the database itself: the first one marked as such, or, when
         * none is, the first of them - which is what every list looked like before there was
         * a mark to set.
         */
        NumberSource base = sources.get(0);
        for (NumberSource source : sources) {
            if (source.getRole() == NumberSource.Role.BASE) {
                base = source;
                break;
            }
        }

        if (listener != null) listener.onProgress(1, total);

        /*
         * Tens of megabytes are not fetched again because someone pressed a button: the
         * database is downloaded when there is none, and after that only when its own
         * schedule says so. What changes in between is what the other sources carry.
         */
        if (force || needsDownload(base)) {
            phase(listener, R.string.main_db_downloading);

            buildLog.line(tagOf(base), context.getString(R.string.build_log_downloading));

            String failure = downloadBase(base, listener);

            if (failure != null) {
                LOG.error("compile() the database itself couldn't be fetched: {}", failure);

                buildLog.line(tagOf(base), context.getString(R.string.build_log_failed, failure));

                return new Result(Status.NO_BASE, 0, 1, failure);
            }
        } else {
            LOG.debug("compile() the database is there and not due");

            buildLog.line(tagOf(base), context.getString(R.string.source_result_kept));

            /*
             * Said out loud in the source's row: a build that keeps the database it already
             * has looks exactly like one that fetched it, and the difference matters when
             * someone is waiting to see a source they just changed take effect.
             */
            base.setLastCheck(System.currentTimeMillis());

            note(base, context.getString(R.string.source_result_kept));
        }

        reloadDatabases(); // the layers go on top of what was just downloaded

        // the base goes into the table first, whatever place it has in the list
        List<NumberSource> ordered = new ArrayList<>(sources.size());
        ordered.add(base);
        for (NumberSource source : sources) {
            if (!source.getId().equals(base.getId())) ordered.add(source);
        }

        /*
         * A compile is the whole database, not an addition to the last one: what an earlier
         * compile put on top is cleared out, so that a source that was removed or switched
         * off takes its numbers with it. The library's own updates live there too and are
         * fetched again by the next one.
         */
        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();

        if (ordered.size() > 1) phase(listener, R.string.source_fetching);

        for (int i = 1; i < ordered.size(); i++) {
            if (listener != null) listener.onProgress(i + 1, total);

            NumberSource layer = ordered.get(i);

            buildLog.line(tagOf(layer), context.getString(R.string.build_log_downloading));

            if (!downloadLayer(layer)) {
                failed++;

                buildLog.line(tagOf(layer), context.getString(R.string.build_log_failed,
                        layer.getLastResult() != null ? layer.getLastResult() : ""));
            }
        }

        dropLayersOfGoneSources(ordered);

        if (!applyLayers(ordered)) {
            return new Result(Status.FAILED, total - failed, failed,
                    context.getString(R.string.db_build_not_readable));
        }

        phase(listener, R.string.reading_sources);

        String tableFailure = buildNumbersTable(ordered, listener);

        if (tableFailure != null) {
            return new Result(Status.FAILED, total - failed, failed, tableFailure);
        }

        LOG.info("compile() built the database from {} of {} sources", total - failed, total);

        /*
         * The line someone reads first: what came out of the whole thing, and when it was
         * over - which is also the answer to "did that build I started ever finish".
         */
        buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_finished,
                NumberFormat.getInstance().format(new NumbersCompiler(context).getCount()),
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()),
                (System.currentTimeMillis() - startTime) / 1000));

        return new Result(Status.COMPILED, total - failed, failed);
    }

    /**
     * Puts the layers back on top of the database, from what was fetched last time.
     *
     * <p>Wanted after the library updates itself: that update is merged into the same place
     * the layers live, so without this the numbers a layer added - and the ones it took out -
     * would quietly go back to what the update says about them.
     */
    public boolean reapplyLayers() {
        List<NumberSource> sources = getSources();

        return sources.size() <= 1 || applyLayers(sources);
    }

    /** The sources the database is built from, in the order they are asked. */
    private List<NumberSource> getSources() {
        return sourceService != null
                ? sourceService.getEnabledSources(NumberSource.Type.DATABASE)
                : new ArrayList<>();
    }

    /**
     * Whether the database has to be fetched.
     *
     * <p>When there is none; when the source has been pointed somewhere else since, because
     * then what is on the phone is the old address's database and building from it would
     * quietly ignore the change; and when its own schedule says so.
     */
    private boolean needsDownload(NumberSource source) {
        File info = new File(new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_PATH_PREFIX), "data_slice_info.dat");

        if (!info.exists()) return true;

        if (source.hasMoved()) {
            LOG.info("needsDownload() the source points somewhere else than what is here");
            return true;
        }

        return source.isDue(System.currentTimeMillis());
    }

    /**
     * Downloads the database itself, which is what the rest is layered onto.
     *
     * @return null when it worked, otherwise what went wrong
     */
    private String downloadBase(NumberSource source, ProgressListener listener) {
        LOG.debug("downloadBase() {}", source.getUrl());

        if (TextUtils.isEmpty(source.getUrl())) {
            note(source, context.getString(R.string.source_result_no_address));
            return context.getString(R.string.source_result_no_address);
        }

        source.setLastCheck(System.currentTimeMillis());

        /*
         * The library moves directories around rather than merging them: the new database is
         * unpacked beside the old one, the old one is renamed away, and the new one takes its
         * place. A run that was cut short in the middle of that leaves the renamed one behind,
         * and the next run then can't rename anything anywhere.
         */
        dropLeftovers();

        boolean downloaded = false;
        String error = null;
        try {
            // the client asks the service which source it is fetching, to log in as that one
            sourceService.setFetchingSource(source);

            /*
             * The library unpacks a zip and nothing else, which is what its own database is
             * served as. A source that hands the same files over as a tar.gz is unpacked
             * here instead - the files are the same, only the wrapping is different, and a
             * source shouldn't have to repack a database to be usable.
             */
            downloaded = Boolean.FALSE.equals(looksLikeZip(source))
                    ? unpackBase(source, listener)
                    : YacbHolder.getDbManager().downloadMainDb(source.getUrl());
        } catch (Exception e) {
            LOG.error("downloadBase() failed", e);
            error = e.getClass().getSimpleName()
                    + (e.getLocalizedMessage() != null ? ": " + e.getLocalizedMessage() : "");
        } finally {
            sourceService.setFetchingSource(null);
        }

        if (!downloaded) {
            note(source, context.getString(R.string.source_result_failed));
            return error != null ? error : context.getString(R.string.db_build_no_base_text);
        }

        /*
         * Downloaded is not the same as readable. What arrives is an archive that the library
         * unpacks itself, and one that holds nothing it recognises unpacks into an empty
         * directory - which is then in the place of the database that worked. Saying so here
         * is the difference between "there is nothing in it" and a screen full of numbers
         * that are quietly gone.
         */
        reloadDatabases();

        if (!YacbHolder.getCommunityDatabase().isOperational()) {
            LOG.error("downloadBase() what arrived can't be read as a database");

            note(source, context.getString(R.string.source_result_not_a_database));
            return context.getString(R.string.db_build_not_readable);
        }

        // what is on the phone now came from here, which is how a changed address is noticed
        source.setFetchedUrl(source.getUrl());

        note(source, context.getString(R.string.source_result_database));

        return null;
    }

    /**
     * Whether what is behind the address is a zip, which is all the library can unpack.
     *
     * <p>Asked with the first bytes rather than by the name: a file is called what whoever
     * put it there felt like calling it, and every one of these formats says what it is in
     * its first four bytes.
     *
     * @return null when the question couldn't be asked at all; then the library has its go,
     * which is what happened before there was a question
     */
    private Boolean looksLikeZip(NumberSource source) {
        DeferredInit.initNetwork();

        OkHttpClient client = SourceHttp.decorate(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build(),
                source, sourceService.getSecret(source.getId()), false);

        Request.Builder builder = new Request.Builder()
                .url(source.getUrl())
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME)
                // packed in flight would say nothing about what the file itself is
                .header("Accept-Encoding", "identity");

        /*
         * The first bytes are all this needs, so they are what is asked for - and a server
         * that won't serve a part of a file is asked for the whole one and hung up on after
         * four bytes, which costs about as little.
         */
        byte[] magic = readFirstBytes(client,
                builder.header("Range", "bytes=0-15").build());

        if (magic == null) magic = readFirstBytes(client, builder.removeHeader("Range").build());

        if (magic == null) return null;

        boolean zip = magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;

        LOG.debug("looksLikeZip() {}", zip);

        return zip;
    }

    /** The first four bytes of what an address serves, or null when they can't be had. */
    private static byte[] readFirstBytes(OkHttpClient client, Request request) {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOG.debug("readFirstBytes() the server answered {}", response.code());
                return null;
            }

            ResponseBody body = response.body();
            if (body == null) return null;

            byte[] magic = new byte[4];

            try (InputStream inputStream = body.byteStream()) {
                int read = 0;
                while (read < magic.length) {
                    int count = inputStream.read(magic, read, magic.length - read);
                    if (count == -1) break;

                    read += count;
                }

                return read == magic.length ? magic : null;
            }
        } catch (Exception e) {
            LOG.warn("readFirstBytes() couldn't ask", e);
            return null;
        }
    }

    /**
     * Fetches the database and unpacks it into the place the app reads it from.
     *
     * <p>The new one is unpacked beside the old one and only takes its place once it is
     * whole, so a download that is cut short leaves what was there working.
     */
    private boolean unpackBase(NumberSource source, ProgressListener listener) {
        File dataDir = new File(YacbHolder.getStorage().getDataDirPath());

        String name = dirName();
        if (name == null) return false;

        File dir = new File(dataDir, name);
        File tempDir = new File(dataDir, name + "-tmp");
        File oldDir = new File(dataDir, name + "-old");
        File archive = new File(dataDir, name + "-download.part");

        try {
            if (!download(source, archive, false)) return false;

            phase(listener, R.string.unpacking_db);

            FileUtils.delete(tempDir);
            createDir(tempDir);

            int files;
            try (InputStream inputStream
                         = new BufferedInputStream(new FileInputStream(archive))) {
                files = ArchiveUtils.unpackAll(inputStream, tempDir, "data_slice_0.dat");
            }

            LOG.info("unpackBase() unpacked {} files", files);

            if (files == 0) return false;

            FileUtils.delete(oldDir);

            if (dir.exists() && !dir.renameTo(oldDir)) {
                LOG.warn("unpackBase() couldn't move the old database out of the way");
                return false;
            }

            if (!tempDir.renameTo(dir)) {
                LOG.warn("unpackBase() couldn't put the new database in place");

                if (oldDir.exists()) oldDir.renameTo(dir); // leave what worked where it was
                return false;
            }

            FileUtils.delete(oldDir);

            return true;
        } catch (Exception e) {
            LOG.error("unpackBase() failed", e);
            return false;
        } finally {
            FileUtils.delete(archive);
            FileUtils.delete(tempDir);
        }
    }

    /** What the directory the database lives in is called, without the slash. */
    private static String dirName() {
        String prefix = SiaConstants.SIA_PATH_PREFIX;

        int slash = prefix.indexOf('/');

        return slash > 0 ? prefix.substring(0, slash) : null;
    }

    /** Clears what an interrupted download left beside the database. */
    private static void dropLeftovers() {
        String name = dirName();
        if (name == null) return;

        File dataDir = new File(YacbHolder.getStorage().getDataDirPath());

        for (String leftover : new String[]{name + "-old", name + "-tmp"}) {
            File dir = new File(dataDir, leftover);
            if (!dir.exists()) continue;

            LOG.info("dropLeftovers() removing {}", dir);

            FileUtils.delete(dir);
        }
    }

    /** Fetches one layer and keeps it, replacing what was fetched from that source before. */
    private boolean downloadLayer(NumberSource source) {
        LOG.debug("downloadLayer() {}", source.getUrl());

        if (TextUtils.isEmpty(source.getUrl())) {
            note(source, context.getString(R.string.source_result_no_address));
            return false;
        }

        source.setLastCheck(System.currentTimeMillis());

        File file = getLayerFile(source);
        File tempFile = new File(file.getPath() + ".part");

        try {
            if (!download(source, tempFile)) {
                note(source, context.getString(R.string.source_result_failed));
                return false;
            }

            long[] read = readSlice(tempFile);
            if (read == null) {
                note(source, context.getString(R.string.source_result_not_a_database));
                return false;
            }

            source.setVersion((int) read[1]);

            if (file.exists() && !file.delete()) {
                LOG.warn("downloadLayer() couldn't replace {}", file);
                return false;
            }

            if (!tempFile.renameTo(file)) {
                LOG.warn("downloadLayer() couldn't move {}", tempFile);
                return false;
            }

            note(source, context.getString(R.string.source_result_numbers, (int) read[0]));

            return true;
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                LOG.warn("downloadLayer() couldn't clean up {}", tempFile);
            }
        }
    }

    /** Fetches the source into a file, unpacked on the way if it arrives packed. */
    private boolean download(NumberSource source, File target) {
        return download(source, target, true);
    }

    /**
     * Fetches the source into a file.
     *
     * @param unpack whether a packed answer is unpacked on the way. A layer is one file and
     *               is read as one, so it is; the database is a few thousand and is unpacked
     *               into a directory afterwards, so it isn't.
     */
    private boolean download(NumberSource source, File target, boolean unpack) {
        DeferredInit.initNetwork();

        OkHttpClient client = SourceHttp.decorate(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build(),
                source, sourceService.getSecret(source.getId()), unpack);

        Request request = new Request.Builder()
                .url(source.getUrl())
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME)
                .build();

        createDir(target.getParentFile());

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOG.warn("download() the server answered {}", response.code());
                return false;
            }

            ResponseBody body = response.body();
            if (body == null) return false;

            try (InputStream inputStream = body.byteStream();
                 OutputStream outputStream = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];

                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            }

            return true;
        } catch (Exception e) {
            LOG.warn("download() failed", e);
            return false;
        }
    }

    /** Merges every layer into the database, in the order of the sources. */
    private boolean applyLayers(List<NumberSource> sources) {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

        if (!communityDatabase.isOperational()) {
            LOG.warn("applyLayers() there's no database to put the layers on");
            return false;
        }

        NumberFilter numberFilter = DbFilteringUtils.getNumberFilter(settings);

        boolean applied = false;

        for (int i = 1; i < sources.size(); i++) {
            File file = getLayerFile(sources.get(i));
            if (!file.exists()) continue;

            if (applyLayer(file, numberFilter)) applied = true;
        }

        if (applied) reloadDatabases();

        return true;
    }

    /**
     * Merges one layer in.
     *
     * <p>The version the library keeps is put back afterwards: it says which update was
     * fetched last and is what the next one is asked for, and a layer is not one of those.
     */
    private boolean applyLayer(File file, NumberFilter numberFilter) {
        LOG.debug("applyLayer() {}", file.getName());

        dummydomain.yetanothercallblocker.sia.Settings siaSettings = YacbHolder.getSiaSettings();

        int version = siaSettings.getSecondaryDbVersion();

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            YacbHolder.getCommunityDatabase().updateSecondary(inputStream, numberFilter);

            return true;
        } catch (Exception e) {
            LOG.warn("applyLayer() couldn't apply {}", file, e);
            return false;
        } finally {
            siaSettings.setSecondaryDbVersion(version);
        }
    }

    /** Forgets what was fetched from sources that are gone or switched off. */
    private void dropLayersOfGoneSources(List<NumberSource> sources) {
        Set<String> names = new HashSet<>();
        for (NumberSource source : sources) {
            names.add(getLayerFile(source).getName());
        }

        File[] files = getLayersDir().listFiles();
        if (files == null) return;

        for (File file : files) {
            if (names.contains(file.getName())) continue;

            if (!file.delete()) LOG.warn("dropLayersOfGoneSources() couldn't delete {}", file);
        }
    }

    /**
     * Looks at what was fetched: how many numbers it holds and what version it says it is.
     *
     * @return {@code {numbers, version}}, or null when the file isn't a database file at all
     */
    private static long[] readSlice(File file) {
        long[] count = {0};

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            int version = SliceReader.read(inputStream, new SliceReader.Visitor() {
                @Override
                public void onNumber(long number, int positive, int negative,
                                     int neutral, int category) {
                    count[0]++;
                }

                @Override
                public void onDeleted(long number) {
                    count[0]++;
                }
            });

            return new long[]{count[0], version};
        } catch (Exception e) {
            LOG.warn("readSlice() couldn't read {}", file, e);
            return null;
        }
    }

    /**
     * Writes what every source brought into one table, beside the one in use.
     *
     * <p>This is where the sources stop being files in three formats and become rows: the
     * database fills the empty table, the updates and the layers go on top of it, and what
     * comes out is one file with the number as its key - so a number that several sources
     * know is one row, whatever they each say about it.
     *
     * <p>All of it happens in a database of its own: built, then filtered, and only then put
     * in the place of the one the app reads. The copy that a filter can be taken back to is
     * made in between, while the table is still whole.
     */
    private String buildNumbersTable(List<NumberSource> sources, ProgressListener listener) {
        /*
         * Everything happens beside the database the app is reading, and what comes out takes
         * its place only when it is whole. Nothing waits for the build, nothing is locked by
         * it, and a build that fails leaves what worked exactly where it was.
         */
        NumbersCompiler compiler = NumbersCompiler.forBuild(context);

        compiler.dropBuild(); // whatever an earlier attempt left behind

        String dataDir = YacbHolder.getStorage().getDataDirPath();

        List<String> tags = new ArrayList<>(sources.size());
        for (NumberSource source : sources) {
            tags.add(tagOf(source));
        }

        NumbersCompiler.Result result = compiler.compile(sources,
                new File(dataDir, SiaConstants.SIA_PATH_PREFIX),
                new File(dataDir, SiaConstants.SIA_SECONDARY_PATH_PREFIX),
                getLayersDir(),
                listener != null ? listener::onProgress : null,
                this::noteSourceCounts, buildLog, tags);

        /*
         * The library keeps what it read in memory - the slices it was asked about, and the
         * tree above them - and the table was just built out of the same files. Letting go of
         * that before the filtering walks the table again is free, and on a database of a few
         * hundred thousand files it is the difference between filtering and running out.
         */
        YacbHolder.getCommunityDatabase().reload();

        if (!result.ok) {
            LOG.error("buildNumbersTable() the table couldn't be built: {}", result.error);

            compiler.dropBuild();

            return result.error != null
                    ? context.getString(R.string.db_build_table_failed_reason, result.error)
                    : context.getString(R.string.db_build_table_failed);
        }

        noteSourceMeta(compiler, sources);

        boolean filtering = settings.isDbFilteringEnabled();

        /*
         * The copy is what a filter can be taken back to, so it is made while the table is
         * still whole and only when there is going to be something to take back: it is as big
         * as the table itself, and a phone that has just written a few hundred megabytes has
         * no business writing them a second time for nothing.
         */
        if (filtering && settings.getDbFilteringKeepMaster()) {
            compiler.makeShadowCopy();
        } else {
            compiler.dropShadowCopy(); // an older one would be a copy of an older database
        }

        if (filtering) {
            phase(listener, R.string.filtering_db);

            buildLog.line(BuildLog.MAIN, context.getString(R.string.filtering_db));

            compiler.filter(DbFilteringUtils.getPrefixesToKeep(settings),
                    settings.getDbFilteringKeepShortNumbers()
                            ? settings.getDbFilteringKeepShortNumbersMaxLength() : 0);
        }

        // and only now does it become the database the app looks numbers up in
        if (!compiler.promote()) {
            LOG.error("buildNumbersTable() the built database couldn't be put in place");

            return context.getString(R.string.db_build_table_failed);
        }

        return null;
    }

    /**
     * Writes down what a source did the moment it has done it.
     *
     * <p>So that the screens showing the sources fill in as the build runs rather than all at
     * once at the end: a source that has been read is a source that has something to say.
     */
    private void noteSourceCounts(NumberSource source, NumbersCompiler.Counts counts) {
        if (sourceService == null) return;

        source.setEntries(counts.read);
        source.setLastUpdate(System.currentTimeMillis());
        source.setLastResult(context.getString(R.string.source_result_numbers,
                (int) counts.read));

        sourceService.save(source);
    }

    /** What a source is called in the log and in what it says about itself. */
    private String tagOf(NumberSource source) {
        return SourceNames.getName(context, source);
    }

    /**
     * Keeps with each source what the build found out about it: how much of the database
     * came from there, and - for the one that carries the database - which version it is.
     */
    private void noteSourceMeta(NumbersCompiler compiler, List<NumberSource> sources) {
        if (sourceService == null) return;

        int version = YacbHolder.getCommunityDatabase().getEffectiveDbVersion();

        for (NumbersCompiler.SourceCount count : compiler.getSourceCounts()) {
            for (NumberSource source : sources) {
                if (!source.getId().equals(count.uuid)) continue;

                source.setEntries(count.count);

                // the database's version is the library's; a layer said its own when it arrived
                if (count.layer == 0) source.setVersion(version);

                sourceService.save(source);
                break;
            }
        }
    }

    /** Writes down how the source went, which is what its row in the list says. */
    private void note(NumberSource source, String result) {
        source.setLastResult(result);
        source.setLastUpdate(System.currentTimeMillis());

        if (sourceService != null) sourceService.save(source);
    }

    private File getLayerFile(NumberSource source) {
        return new File(getLayersDir(), source.getId() + LAYER_POSTFIX);
    }

    private static File getLayersDir() {
        return new File(YacbHolder.getStorage().getDataDirPath(), LAYERS_DIR_NAME);
    }

    private static void createDir(File dir) {
        if (dir == null) return;

        if (!dir.isDirectory() && !dir.mkdirs()) LOG.warn("createDir() couldn't create {}", dir);
    }

    private static void reloadDatabases() {
        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();
    }

}
