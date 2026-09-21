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
import dummydomain.yetanothercallblocker.data.numbers.NumberFlags;
import dummydomain.yetanothercallblocker.data.numbers.NumbersFilter;
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
 * <p>No source is the database that the others are added to. Every source can hand over a
 * whole database - one file or a few hundred thousand, packed or plain - and the order alone
 * decides what happens to a number two of them know: the first source writes into an empty
 * table, every one after it changes what is already there, and the last one to say something
 * about a number is the one that is believed. A source can also carry numbers to take out
 * again, which is how it says "this one is not spam" about an entry an earlier one brought.
 *
 * <p>One source is still the one whose files the library keeps: it reads a single directory
 * for the featured names and what the app knows about countries, and that is the first
 * database source in the list, for that reason and no other. Every other source is unpacked
 * into a place of its own and read in its turn.
 *
 * <p>The database is built beside the one in use and put in its place only once it is whole,
 * so a build that fails leaves the one that worked exactly where it was.
 */
public class DbCompileService {

    private static final Logger LOG = LoggerFactory.getLogger(DbCompileService.class);

    /** Where an older version kept one file per source; removed when one is found. */
    private static final String LAYERS_DIR_NAME = "sia-layers";

    /** And where it kept the downloaded files as they arrived, to undo filtering with. */
    private static final String MASTER_DIR_NAME = "sia-master";

    /** And where each source keeps what it handed over, one directory each. */
    private static final String SOURCES_DIR_NAME = "sources";

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
         * The one whose files the library keeps - the featured names and what the app knows
         * about countries are read from there, and there is one such place. It is the first
         * database source in the list and nothing else: no source is marked as carrying the
         * database, because every one of them can.
         */
        NumberSource primary = getPrimary(sources);

        for (int i = 0; i < sources.size(); i++) {
            NumberSource source = sources.get(i);

            if (listener != null) listener.onProgress(i + 1, total);

            phase(listener, source.getType() == NumberSource.Type.DATABASE
                    ? R.string.main_db_downloading : R.string.source_fetching);

            boolean isPrimary = source == primary;

            /*
             * A PhoneBlock account keeps its own account of when it was last asked, so it is
             * asked every time and decides for itself; the sources that hand over files are
             * asked here.
             */
            boolean files = source.getType() != NumberSource.Type.PHONE_BLOCK;

            if (files && !force && !needsDownload(source, isPrimary)) {
                LOG.debug("compile() {} is there and not due", tagOf(source));

                /*
                 * Said out loud in the source's row: a build that keeps what it already has
                 * looks exactly like one that fetched it, and the difference matters when
                 * someone is waiting to see a source they just changed take effect.
                 */
                source.setLastCheck(System.currentTimeMillis());

                buildLog.line(tagOf(source), context.getString(R.string.source_result_kept));

                note(source, context.getString(R.string.source_result_kept));
                continue;
            }

            buildLog.line(tagOf(source), context.getString(R.string.build_log_downloading));

            String failure = fetch(source, isPrimary, listener);

            if (failure != null) {
                failed++;

                LOG.warn("compile() {} couldn't be fetched: {}", tagOf(source), failure);

                buildLog.line(tagOf(source),
                        context.getString(R.string.build_log_failed, failure));

                /*
                 * The first source fills the empty table, so without it there is nothing for
                 * the rest to change. The others are worth carrying on without.
                 */
                if (i == 0) return new Result(Status.NO_BASE, 0, total, failure);
            }
        }

        reloadDatabases();

        /*
         * A compile is the whole database, not an addition to the last one: what an earlier
         * compile put on top is cleared out, so that a source that was removed or switched
         * off takes its numbers with it. The library's own updates live there too and are
         * fetched again by the next one.
         */
        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();

        dropDirsOfGoneSources(sources);

        applyLayers(sources, primary);

        phase(listener, R.string.reading_sources);

        String tableFailure = buildNumbersTable(sources, primary, listener);

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
     * Puts the other sources back on top of the library's database, from what was fetched
     * last time.
     *
     * <p>Wanted after the library updates itself: that update is merged into the same place
     * the other sources were merged into, so without this the numbers one of them added -
     * and the ones it took out - would quietly go back to what the update says about them.
     */
    public void reapplyLayers() {
        List<NumberSource> sources = getSources();

        if (sources.size() > 1) applyLayers(sources, getPrimary(sources));
    }

    /**
     * The sources the database is built from, in the order they are asked.
     *
     * <p>Not only the ones that hand over a database file: a PhoneBlock account is a source
     * like any other - it is in the same list, switched on the same way - and what it knows
     * belongs in the same table. CardDAV is in that list too and has nothing to fetch it
     * with yet, so it is left out rather than counted as a source that brought nothing.
     */
    private List<NumberSource> getSources() {
        return sourceService != null
                ? sourceService.getEnabledSources(
                        NumberSource.Type.DATABASE, NumberSource.Type.PHONE_BLOCK)
                : new ArrayList<>();
    }

    /**
     * The source whose files the library keeps, or null when there is no such source.
     *
     * <p>Not a role and not a rank: the library reads one directory for the featured names
     * and what the app knows about countries, so one source's files go there, and that is
     * the first database source in the list. Every other source is unpacked into a place of
     * its own and read in its turn.
     */
    private NumberSource getPrimary(List<NumberSource> sources) {
        for (NumberSource source : sources) {
            if (source.getType() == NumberSource.Type.DATABASE) return source;
        }

        return null;
    }

    /** Where a source that isn't the primary one keeps what it handed over. */
    private static File getSourceDir(NumberSource source) {
        return new File(new File(YacbHolder.getStorage().getDataDirPath(), SOURCES_DIR_NAME),
                source.getId());
    }

    /**
     * Fetches one source, wherever its kind of source keeps what it brings.
     *
     * @return null when it worked, otherwise what went wrong
     */
    private String fetch(NumberSource source, boolean primary, ProgressListener listener) {
        if (source.getType() == NumberSource.Type.PHONE_BLOCK) {
            return updatePhoneBlock(source) ? null
                    : context.getString(R.string.source_result_failed);
        }

        if (primary) return downloadBase(source, listener);

        return downloadIntoDir(source, getSourceDir(source));
    }

    /**
     * Fetches a source into a directory of its own, whatever it hands over.
     *
     * <p>One file or a few hundred thousand, packed or plain: every source can be a whole
     * database, and what decides what it does to the table is where it stands in the list,
     * not how much it brought.
     */
    private String downloadIntoDir(NumberSource source, File dir) {
        if (TextUtils.isEmpty(source.getUrl())) {
            note(source, context.getString(R.string.source_result_no_address));
            return context.getString(R.string.source_result_no_address);
        }

        source.setLastCheck(System.currentTimeMillis());

        File archive = new File(dir.getParentFile(), source.getId() + ".part");
        File tempDir = new File(dir.getParentFile(), source.getId() + "-tmp");

        try {
            if (!download(source, archive, false)) {
                note(source, context.getString(R.string.source_result_failed));
                return context.getString(R.string.source_result_failed);
            }

            FileUtils.delete(tempDir);
            createDir(tempDir);

            int files;
            try (InputStream inputStream
                         = new BufferedInputStream(new FileInputStream(archive))) {
                files = ArchiveUtils.unpackAll(inputStream, tempDir, "data_slice_0.dat");
            }

            if (files == 0) {
                note(source, context.getString(R.string.source_result_not_a_database));
                return context.getString(R.string.source_result_not_a_database);
            }

            FileUtils.delete(dir);

            if (!tempDir.renameTo(dir)) {
                LOG.warn("downloadIntoDir() couldn't put {} in place", dir);

                note(source, context.getString(R.string.source_result_failed));
                return context.getString(R.string.source_result_failed);
            }

            source.setFetchedUrl(source.getUrl());

            noteFetched(source, context.getString(R.string.source_result_files, files));

            return null;
        } catch (Exception e) {
            LOG.error("downloadIntoDir() failed", e);

            note(source, context.getString(R.string.source_result_failed));

            return e.getClass().getSimpleName()
                    + (e.getLocalizedMessage() != null ? ": " + e.getLocalizedMessage() : "");
        } finally {
            FileUtils.delete(archive);
            FileUtils.delete(tempDir);
        }
    }

    /**
     * Brings the PhoneBlock list up to date, which is what fetching that source means.
     *
     * <p>The list is kept where the account screen keeps it - the same one an incoming call
     * is held against - and the build reads it from there rather than downloading a second
     * copy of it.
     */
    private boolean updatePhoneBlock(NumberSource source) {
        PhoneBlockService.Result result = new PhoneBlockService(settings,
                YacbHolder.getPhoneBlockList(), YacbHolder.getPhoneBlockPersonalLists())
                .update(false);

        boolean ok = result.status != PhoneBlockService.Status.FAILED
                && result.status != PhoneBlockService.Status.NOT_CONFIGURED;

        if (ok) {
            noteFetched(source, context.getString(R.string.source_result_numbers, result.size));
        } else {
            note(source, context.getString(R.string.source_result_failed));
        }

        return ok;
    }

    /**
     * Whether a source has to be fetched again.
     *
     * <p>When nothing of it is on the phone; when it has been pointed somewhere else since,
     * because then what is here is the old address's and building from it would quietly
     * ignore the change; and when its own schedule says so. Every source is asked this, not
     * only the one the library keeps: any of them can be tens of megabytes, and none of them
     * is worth fetching again because a build was started.
     */
    private boolean needsDownload(NumberSource source, boolean primary) {
        File here = primary
                ? new File(new File(YacbHolder.getStorage().getDataDirPath(),
                        SiaConstants.SIA_PATH_PREFIX), "data_slice_info.dat")
                : getSourceDir(source);

        if (!here.exists()) return true;

        if (source.hasMoved()) {
            LOG.info("needsDownload() the source points somewhere else than what is here");
            return true;
        }

        return source.isDue(System.currentTimeMillis());
    }

    /**
     * Fetches the source whose files the library keeps, into the place the library reads.
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
            return error != null ? error
                    : context.getString(R.string.source_result_failed);
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

        noteFetched(source, context.getString(R.string.source_result_database));

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

    /**
     * Fetches the source into a file.
     *
     * @param unpack whether a packed answer is unpacked on the way. What a source hands over
     *               is unpacked into a directory of its own afterwards, whether it is one
     *               file or a few hundred thousand, so it isn't.
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

    /**
     * Puts what the other sources brought into the library's database as well.
     *
     * <p>Numbers are looked up in the library's own files, not yet in the table this build
     * fills, so a source whose numbers are only in the table would be built and then not
     * asked. The library takes one slice file at a time, so this is what it can be given:
     * the sources that handed over a single file. A source that handed over a whole
     * directory of them is in the table and will be looked up there when lookups move.
     */
    private void applyLayers(List<NumberSource> sources, NumberSource primary) {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

        if (!communityDatabase.isOperational()) {
            LOG.warn("applyLayers() there's no database to put the others on");
            return;
        }

        NumberFilter numberFilter = DbFilteringUtils.getNumberFilter(settings);

        boolean applied = false;

        for (NumberSource source : sources) {
            if (source == primary || source.getType() != NumberSource.Type.DATABASE) continue;

            File[] files = getSourceDir(source).listFiles();
            if (files == null || files.length != 1 || !files[0].isFile()) continue;

            if (applyLayer(files[0], numberFilter)) applied = true;
        }

        if (applied) reloadDatabases();
    }

    /**
     * Merges one file in.
     *
     * <p>The version the library keeps is put back afterwards: it says which update was
     * fetched last and is what the next one is asked for, and this is not one of those.
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

    /**
     * Forgets what was fetched from sources that are gone or switched off, and what older
     * versions kept beside the database.
     */
    private void dropDirsOfGoneSources(List<NumberSource> sources) {
        dropOldCopies();

        Set<String> names = new HashSet<>();
        for (NumberSource source : sources) {
            names.add(source.getId());
        }

        File[] dirs = new File(YacbHolder.getStorage().getDataDirPath(), SOURCES_DIR_NAME)
                .listFiles();

        if (dirs == null) return;

        for (File dir : dirs) {
            if (names.contains(dir.getName())) continue;

            LOG.info("dropDirsOfGoneSources() removing {}", dir);

            FileUtils.delete(dir);
        }
    }

    /**
     * Removes what older versions kept beside the database.
     *
     * <p>Two things, both from when the database was built whole and filtered afterwards: a
     * file per source, and a copy of the downloaded files as they arrived, to take the
     * filtering back to. Nothing is filtered out of a finished database any more - a number
     * that doesn't belong is never written - so neither has anything to go back to, and
     * between them they are a few hundred megabytes of a phone's storage.
     */
    private static void dropOldCopies() {
        String dataDir = YacbHolder.getStorage().getDataDirPath();

        for (String name : new String[]{LAYERS_DIR_NAME, MASTER_DIR_NAME}) {
            File dir = new File(dataDir, name);
            if (!dir.exists()) continue;

            LOG.info("dropOldCopies() removing {}", dir);

            FileUtils.delete(dir);
        }
    }

    /**
     * Writes what every source brought into one table, beside the one in use.
     *
     * <p>This is where the sources stop being files in three formats and become rows. They
     * are read in the order the user put them in and nothing else: the first one writes into
     * an empty table, every one after it changes what is already there, and the number is
     * the key - so a number that several sources know is one row, with the last of them
     * having the say. No source is the database that the rest are added to.
     *
     * <p>All of it happens in a database of its own: filled here and put in the place of the
     * one the app reads only once it is whole.
     */
    private String buildNumbersTable(List<NumberSource> sources, NumberSource primary,
                                     ProgressListener listener) {
        /*
         * Everything happens beside the database the app is reading, and what comes out takes
         * its place only when it is whole. Nothing waits for the build, nothing is locked by
         * it, and a build that fails leaves what worked exactly where it was.
         */
        NumbersCompiler compiler = NumbersCompiler.forBuild(context);

        compiler.dropBuild(); // whatever an earlier attempt left behind

        /*
         * What is worth keeping is decided as each number arrives rather than afterwards: a
         * community database holds nine million numbers from everywhere, and writing all of
         * them down to delete most of them again is minutes of work and a few hundred
         * megabytes for nothing. The same filter applies to every source.
         */
        NumbersFilter filter = NumbersFilter.of(settings);

        compiler.setFilter(filter);

        buildLog.line(BuildLog.MAIN, filter != null
                ? context.getString(R.string.build_log_filter,
                        NumbersFilter.describe(settings))
                : context.getString(R.string.build_log_no_filter));

        NumbersCompiler.Result result = compiler.compile(toInputs(sources, primary),
                listener != null ? listener::onProgress : null,
                this::noteSourceCounts, buildLog, new PhoneBlockSource());

        /*
         * The library keeps what it read in memory - the slices it was asked about, and the
         * tree above them - and the table was just built out of the same files. Letting go of
         * it here is free and leaves the phone that much more to work with.
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

        // and only now does it become the database the app looks numbers up in
        if (!compiler.promote()) {
            LOG.error("buildNumbersTable() the built database couldn't be put in place");

            return context.getString(R.string.db_build_table_failed);
        }

        return null;
    }

    /**
     * Where each source's numbers are lying, in the order they are read.
     *
     * <p>One place per source: the source whose files the library keeps reads those, and what
     * the library has fetched for itself since goes with them; every other source reads the
     * directory it was unpacked into; a source that isn't files at all has none and hands its
     * numbers over another way.
     */
    private List<NumbersCompiler.Input> toInputs(List<NumberSource> sources,
                                                 NumberSource primary) {
        String dataDir = YacbHolder.getStorage().getDataDirPath();

        List<NumbersCompiler.Input> inputs = new ArrayList<>(sources.size());

        for (NumberSource source : sources) {
            File dir = null;
            File updatesDir = null;

            if (source.getType() == NumberSource.Type.DATABASE) {
                if (source == primary) {
                    dir = new File(dataDir, SiaConstants.SIA_PATH_PREFIX);
                    updatesDir = new File(dataDir, SiaConstants.SIA_SECONDARY_PATH_PREFIX);
                } else {
                    dir = getSourceDir(source);
                }
            }

            inputs.add(new NumbersCompiler.Input(source, tagOf(source), dir, updatesDir));
        }

        return inputs;
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

        /*
         * What it brought to this build, without touching when it was last fetched: it was
         * read, which every build does to every source, and moving that date every time
         * would mean a source with a schedule is never due again.
         */
        source.setLastResult(context.getString(R.string.source_result_numbers,
                (int) counts.read));

        sourceService.save(source);
    }

    /**
     * The PhoneBlock list, handed to the table the way a slice file would be.
     *
     * <p>Everything on it is a number the community warns about - the ones it has cleared are
     * not kept - so each of them goes in as one negative rating, which is what a rating of
     * "spam" amounts to in a table that counts ratings.
     */
    private static class PhoneBlockSource implements NumbersCompiler.ExtraSource {

        @Override
        public boolean canRead(NumberSource source) {
            return source.getType() == NumberSource.Type.PHONE_BLOCK;
        }

        @Override
        public void read(NumberSource source, NumbersCompiler.Entries entries) {
            PhoneBlockList list = YacbHolder.getPhoneBlockList();
            if (list == null) return;

            PhoneBlockList.Snapshot snapshot = list.snapshot();

            for (int i = 0; i < snapshot.size(); i++) {
                /*
                 * The rating and nothing else: the list says a number is worth warning about,
                 * not what it is used for or how many people said so. A category or a count
                 * from the database underneath is worth more than a zero from here.
                 */
                entries.number(snapshot.getNumber(i), NumberFlags.RATING_NEGATIVE, null, null);
            }
        }

    }

    /** What a source is called in the log and in what it says about itself. */
    private String tagOf(NumberSource source) {
        return SourceNames.getName(context, source);
    }

    /**
     * Keeps with each source what the build found out about it: how much of the database
     * came from there, and - for the source whose files the library keeps - which version
     * it says it is.
     */
    private void noteSourceMeta(NumbersCompiler compiler, List<NumberSource> sources) {
        if (sourceService == null) return;

        int version = YacbHolder.getCommunityDatabase().getEffectiveDbVersion();

        for (NumbersCompiler.SourceCount count : compiler.getSourceCounts()) {
            for (NumberSource source : sources) {
                if (!source.getId().equals(count.uuid)) continue;

                source.setEntries(count.count);

                // the first source's files are the library's, so its version is the one it says
                if (count.layer == 0) source.setVersion(version);

                sourceService.save(source);
                break;
            }
        }
    }

    /**
     * Writes down how the source went, which is what its row in the list says.
     *
     * <p>When it was asked, not when it last handed anything over: a source that failed, or
     * that was left as it is because it isn't due, has been looked at and nothing more. Only
     * {@link #noteFetched} moves the date the schedule counts from - otherwise every build
     * would push it forward and a source with a weekly schedule would never come round.
     */
    private void note(NumberSource source, String result) {
        source.setLastResult(result);
        source.setLastCheck(System.currentTimeMillis());

        if (sourceService != null) sourceService.save(source);
    }

    /** The same, for a source that has just handed something over. */
    private void noteFetched(NumberSource source, String result) {
        source.setLastUpdate(System.currentTimeMillis());

        note(source, result);
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
