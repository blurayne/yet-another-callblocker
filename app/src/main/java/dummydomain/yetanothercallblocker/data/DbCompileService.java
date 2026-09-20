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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.BuildConfig;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceHttp;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.numbers.SliceReader;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.NumberFilter;
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

        Result(Status status, int sources, int failed) {
            this.status = status;
            this.sources = sources;
            this.failed = failed;
        }

        public boolean isOk() {
            return status == Status.COMPILED;
        }

    }

    /** Reports which source is being fetched. */
    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    private final Context context;
    private final Settings settings;
    private final SourceService sourceService;

    /**
     * @param context used for what a source's row ends up saying, in the user's language
     */
    public DbCompileService(Context context, Settings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.sourceService = YacbHolder.getSourceService();
    }

    /**
     * Fetches every source that is switched on and builds the database from them.
     *
     * @param listener notified as the sources are fetched, may be null
     */
    public Result compile(ProgressListener listener) {
        LOG.debug("compile() started");

        List<NumberSource> sources = getSources();
        if (sources.isEmpty()) {
            LOG.info("compile() there are no sources to build the database from");
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
        if (needsDownload(base)) {
            if (!downloadBase(base)) {
                LOG.warn("compile() the database itself couldn't be fetched");
                return new Result(Status.NO_BASE, 0, 1);
            }
        } else {
            LOG.debug("compile() the database is there and not due");

            base.setLastCheck(System.currentTimeMillis());
            if (sourceService != null) sourceService.save(base);
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

        for (int i = 1; i < ordered.size(); i++) {
            if (listener != null) listener.onProgress(i + 1, total);

            if (!downloadLayer(ordered.get(i))) failed++;
        }

        dropLayersOfGoneSources(ordered);

        if (!applyLayers(ordered)) return new Result(Status.FAILED, total - failed, failed);

        buildNumbersTable(ordered, listener);

        LOG.info("compile() built the database from {} of {} sources", total - failed, total);

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

    /** Whether the database has to be fetched: there is none, or its schedule says so. */
    private boolean needsDownload(NumberSource source) {
        File info = new File(new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_PATH_PREFIX), "data_slice_info.dat");

        return !info.exists() || source.isDue(System.currentTimeMillis());
    }

    /** Downloads the database itself, which is what the rest is layered onto. */
    private boolean downloadBase(NumberSource source) {
        LOG.debug("downloadBase() {}", source.getUrl());

        if (TextUtils.isEmpty(source.getUrl())) {
            note(source, context.getString(R.string.source_result_no_address));
            return false;
        }

        source.setLastCheck(System.currentTimeMillis());

        boolean downloaded = false;
        try {
            // the client asks the service which source it is fetching, to log in as that one
            sourceService.setFetchingSource(source);

            downloaded = YacbHolder.getDbManager().downloadMainDb(source.getUrl());
        } catch (Exception e) {
            LOG.warn("downloadBase() failed", e);
        } finally {
            sourceService.setFetchingSource(null);
        }

        note(source, context.getString(downloaded
                ? R.string.source_result_database : R.string.source_result_failed));

        return downloaded;
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
        DeferredInit.initNetwork();

        OkHttpClient client = SourceHttp.decorate(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build(),
                source, sourceService.getSecret(source.getId()));

        Request request = new Request.Builder()
                .url(source.getUrl())
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME)
                .build();

        createDir(getLayersDir());

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
     * Writes what every source brought into the one table, and puts a copy aside.
     *
     * <p>This is where the sources stop being files in three formats and become rows: the
     * database fills the empty table, the updates and the layers go on top of it, and what
     * comes out is one file with the number as its key.
     *
     * <p>The copy is made while the table is still whole, because filtering is what happens
     * next and there has to be something to go back to that isn't a download.
     */
    private void buildNumbersTable(List<NumberSource> sources, ProgressListener listener) {
        NumbersCompiler compiler = new NumbersCompiler(context);

        String dataDir = YacbHolder.getStorage().getDataDirPath();

        NumbersCompiler.Result result = compiler.compile(sources,
                new File(dataDir, SiaConstants.SIA_PATH_PREFIX),
                new File(dataDir, SiaConstants.SIA_SECONDARY_PATH_PREFIX),
                getLayersDir(),
                listener != null ? listener::onProgress : null);

        if (!result.ok) {
            LOG.warn("buildNumbersTable() the table couldn't be built");
            return;
        }

        noteSourceMeta(compiler, sources);

        compiler.makeShadowCopy();

        if (settings.isDbFilteringEnabled()) {
            compiler.filter(DbFilteringUtils.getPrefixesToKeep(settings),
                    settings.getDbFilteringKeepShortNumbers()
                            ? settings.getDbFilteringKeepShortNumbersMaxLength() : 0);

            // the copy is what the filtering can be taken back to; not everyone wants to pay
            if (!settings.getDbFilteringKeepMaster()) compiler.dropShadowCopy();
        }
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
        if (!dir.isDirectory() && !dir.mkdirs()) LOG.warn("createDir() couldn't create {}", dir);
    }

    private static void reloadDatabases() {
        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();
    }

}
