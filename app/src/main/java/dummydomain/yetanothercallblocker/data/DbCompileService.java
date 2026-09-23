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
import dummydomain.yetanothercallblocker.SourceTestHelper;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.source.ArchiveUtils;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceNames;
import dummydomain.yetanothercallblocker.data.source.SourceHttp;
import dummydomain.yetanothercallblocker.data.source.SourceTester;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.numbers.NumberFlags;
import dummydomain.yetanothercallblocker.data.numbers.NumbersFilter;
import dummydomain.yetanothercallblocker.data.numbers.SqliteImporter;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.sia.utils.FileUtils;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import okhttp3.HttpUrl;
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

    /** How much of an archive is fetched to see what is in it, when nothing was unpacked. */
    private static final int HEADERS_PEEK_BYTES = 2 * 1024 * 1024;

    /** How many of its entries are walked, and how many of those are worth saying. */
    private static final int HEADERS_MAX_ENTRIES = 256;
    private static final int HEADERS_TO_SAY = 12;

    /** How much has to arrive before the download says so again. */
    private static final long PROGRESS_EVERY_BYTES = 512 * 1024;

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 120;

    /**
     * What started a build, which is the whole of what decides who gets fetched.
     *
     * <p>A build always reads every source into a new table. Whether a source is fetched
     * first is another question, and this is the answer to it: the same run started three
     * ways asks three different sets of sources, and none of them is "all of them" by
     * accident.
     */
    public enum Trigger {
        /**
         * The schedule came round.
         *
         * <p>Only the sources that have a schedule, and only the ones whose turn it is. A
         * source that is fetched when the user says so is not fetched by the clock.
         */
        SCHEDULED,

        /**
         * Someone asked for the database to be built.
         *
         * <p>Everything with a reason to be fetched: the ones whose schedule is up, and the
         * ones that are only ever fetched when asked - being asked is what this is. Not the
         * ones set to be fetched once, which have been.
         */
        BUILD,

        /** Someone asked for this source, now. Whatever is on the phone, it is fetched. */
        FORCED
    }

    /** How a compile went. */
    /**
     * Thrown out of a build that someone asked to stop.
     *
     * <p>Unchecked, because it has to get out through code that was written for a build that
     * runs to its end: readers, downloads, the compiler. Each of them lets it through rather
     * than reporting it as a failure of its own, and the service that started the build is
     * where it lands.
     */
    public static class Cancelled extends RuntimeException {
        Cancelled() {
            super("the build was cancelled");
        }
    }

    /** Whether someone asked for the build that is running to stop. */
    private static volatile boolean cancelRequested;

    /** Asks the running build to stop at the next place it looks. */
    public static void requestCancel() {
        cancelRequested = true;
    }

    /** Starts a build with a clean slate: a cancel from before is not about this one. */
    public static void clearCancel() {
        cancelRequested = false;
    }

    public static boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * Stops the build here if it was asked to.
     *
     * <p>Called wherever the build reports how far it is - which is often enough that a
     * cancel is answered within a second or two, and seldom enough that it costs nothing.
     */
    public static void checkCancelled() {
        if (cancelRequested) throw new Cancelled();
    }

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

        /**
         * Which sources couldn't be fetched and why, one line each, for a build that went
         * on without them. A database built from four sources out of five is a success
         * with a fifth of the story missing, and the notification has to tell that part.
         */
        public final List<String> failures;

        Result(Status status, int sources, int failed) {
            this(status, sources, failed, null, null);
        }

        Result(Status status, int sources, int failed, String reason) {
            this(status, sources, failed, reason, null);
        }

        Result(Status status, int sources, int failed, String reason, List<String> failures) {
            this.status = status;
            this.sources = sources;
            this.failed = failed;
            this.reason = reason;
            this.failures = failures != null ? failures : new ArrayList<String>();
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
        return compile(Trigger.BUILD, listener);
    }

    /**
     * @param trigger what started this, which is what decides who gets fetched
     */
    public Result compile(Trigger trigger, ProgressListener listener) {
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

        /*
         * The one whose files the library keeps - the featured names and what the app knows
         * about countries are read from there, and there is one such place. It is the first
         * database source in the list and nothing else: no source is marked as carrying the
         * database, because every one of them can.
         */
        NumberSource primary = getPrimary(sources);

        try {
            return compile(sources, primary, total, trigger, listener, startTime);
        } catch (Cancelled e) {
            // said in the log, because a run that just stops is a run that looks killed
            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_cancelled));

            throw e;
        }
    }

    private Result compile(List<NumberSource> sources, NumberSource primary, int total,
                           Trigger trigger, ProgressListener listener, long startTime) {
        int failed = 0;

        // who couldn't be fetched and why, for the notification at the end
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < sources.size(); i++) {
            checkCancelled();

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

            if (files && !needsDownload(source, isPrimary, trigger)) {
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

            buildLog.line(tagOf(source), source.getType() == NumberSource.Type.DATABASE
                    ? context.getString(R.string.build_log_downloading_from, source.getUrl())
                    : context.getString(R.string.build_log_downloading));

            String failure = fetch(source, isPrimary, listener);

            if (failure != null) {
                failed++;
                failures.add(tagOf(source) + ": " + failure);

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
         * What the library has fetched for itself since is left where it is and read with
         * the source it belongs to. It used to be thrown away here, because an earlier
         * compile merged the other sources into the same place and a build had to start from
         * what the sources actually say - nothing is merged there any more. The library
         * clears it itself when it fetches a database the updates no longer fit.
         */
        dropDirsOfGoneSources(sources);

        phase(listener, R.string.reading_sources);

        NumbersCompiler.Counts[] totals = new NumbersCompiler.Counts[1];

        String tableFailure = buildNumbersTable(sources, primary, listener, totals);

        if (tableFailure != null) {
            return new Result(Status.FAILED, total - failed, failed, tableFailure, failures);
        }

        LOG.info("compile() built the database from {} of {} sources", total - failed, total);

        dropFilesOfSourcesThatSaidSo(sources, primary);

        logSummary(totals[0], total - failed, total, startTime);

        return new Result(Status.COMPILED, total - failed, failed, null, failures);
    }

    /**
     * Throws away what the sources that asked for it handed over, now that it is in the table.
     *
     * <p>A source's files are read by every build, so keeping them is what spares the next
     * one a download - which is why this is off unless the source says otherwise. When it
     * does say so, the numbers are in the table and what goes is only the copy they were
     * read out of.
     *
     * <p>For the source whose files the library keeps, that is its slices and the index over
     * them, and nothing else: the business names and the country data live in the same
     * directory, are read on every call, and are not this source's numbers.
     */
    private void dropFilesOfSourcesThatSaidSo(List<NumberSource> sources, NumberSource primary) {
        for (NumberSource source : sources) {
            if (!source.getDropFilesAfterBuild() || !source.hasFiles()) continue;

            LOG.info("dropFilesOfSourcesThatSaidSo() dropping the files of {}", tagOf(source));

            if (source == primary) {
                dropSlices();
            } else {
                FileUtils.delete(getSourceDir(source));
            }

            buildLog.line(tagOf(source), context.getString(R.string.build_log_files_dropped));
        }
    }

    /**
     * Removes the community slices the library holds, and the index over them.
     *
     * <p>The index goes with them on purpose: while it is there the app takes the database to
     * be present, so it would neither fetch it again nor find anything in it - and the next
     * build would read an empty directory over a table that was fine.
     */
    private static void dropSlices() {
        File dir = new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_PATH_PREFIX);

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();

            if (!file.isFile() || !name.startsWith("data_slice_")) continue;

            if (!file.delete()) LOG.warn("dropSlices() couldn't delete {}", file);
        }

        // what the library fetched on top of them says nothing without them
        FileUtils.delete(new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_SECONDARY_PATH_PREFIX));

        YacbHolder.getSiaSettings().setSecondaryDbVersion(0);

        reloadDatabases();
    }

    /**
     * What the whole run came to, at the end of the run.
     *
     * <p>Each source says what it did as it finishes, which is what someone watching reads;
     * this is the same question asked of the build - how much was handed over altogether,
     * what became of it, and whether it is over. Someone who opens the log the next morning
     * reads these lines and nothing else.
     */
    private void logSummary(NumbersCompiler.Counts totals, int ok, int total, long startTime) {
        NumberFormat format = NumberFormat.getInstance();

        if (totals != null) {
            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_read),
                    totals.read);
            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_inserted),
                    totals.inserted);
            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_updated),
                    totals.updated);
            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_deleted),
                    totals.deleted);

            if (totals.skipped > 0) {
                buildLog.line(BuildLog.MAIN,
                        context.getString(R.string.build_log_total_filtered), totals.skipped);
            }

            buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_names),
                    totals.names);
            buildLog.line(BuildLog.MAIN,
                    context.getString(R.string.build_log_total_categories), totals.categories);

            if (totals.newCategories > 0) {
                buildLog.line(BuildLog.MAIN,
                        context.getString(R.string.build_log_total_categories_new),
                        totals.newCategories);
            }
        }

        buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_total_sources,
                ok, total));

        /*
         * The line someone reads first: what came out of the whole thing, and when it was
         * over - which is also the answer to "did that build I started ever finish".
         */
        buildLog.line(BuildLog.MAIN, context.getString(R.string.build_log_finished,
                format.format(new NumbersCompiler(context).getCount()),
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()),
                (System.currentTimeMillis() - startTime) / 1000));
    }

    /**
     * Writes what the library has just fetched for itself into the table as well.
     *
     * <p>The library updates itself between builds, and what it fetches is read at the next
     * build - which may be days away. A number is looked up in the table, so until then the
     * table would answer out of what that update supersedes. Merging it in now costs a
     * handful of files rather than a database read again.
     *
     * <p>It goes in as the source whose files the library keeps, because that is whose
     * update it is: it changes what that source said and keeps the place in the order that
     * source has, so a later source still has the last word.
     */
    public void mergeSecondaryUpdate() {
        NumberSource primary = getPrimary(getSources());
        if (primary == null) return;

        File dir = new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_SECONDARY_PATH_PREFIX);

        long entries = new NumbersCompiler(context).mergeUpdates(dir, primary.getId());

        if (entries < 0) return;

        LOG.info("mergeSecondaryUpdate() {} entries went into the table", entries);

        // what was looked up before came out of the table as it was a moment ago
        if (YacbHolder.getNumbersLookup() != null) YacbHolder.getNumbersLookup().reload();
        if (YacbHolder.getNumberInfoCache() != null) YacbHolder.getNumberInfoCache().clear();
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
            /*
             * A source that hands over a SQLite database can't be it, whatever its place in
             * the list: what the library keeps is its own slice files, and putting anything
             * else there leaves it with no database at all. Such a source is read into the
             * table like any other, out of a directory of its own.
             */
            if (source.getType() == NumberSource.Type.DATABASE
                    && source.getContent() != ArchiveUtils.Content.SQLITE) {
                return source;
            }
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

        /*
         * What is behind the address decides where it goes, so a source nobody has looked at
         * yet is looked at now: a few kilobytes off the front, which is all it takes to tell
         * slice files from a SQLite database. Without it the first fetch of a SQLite source
         * would be unpacked into the library's own directory, where it is not a database.
         */
        if (source.getContent() == ArchiveUtils.Content.UNKNOWN) probeContent(source);

        if (primary && source.getContent() != ArchiveUtils.Content.SQLITE) {
            return downloadBase(source, listener);
        }

        return downloadIntoDir(source, getSourceDir(source), listener);
    }

    /** Asks the address what it holds and writes the answer down on the source. */
    private void probeContent(NumberSource source) {
        String secret = sourceService != null ? sourceService.getSecret(source.getId()) : null;

        SourceTester.Result result = SourceTester.test(source, secret);

        /*
         * Said in the log whatever it was: an address that is mistyped fails right here,
         * and "not reachable: UnknownHostException: blurayne.github.oi" is the whole
         * diagnosis, where "couldn't be fetched" would have been the start of one.
         */
        buildLog.line(tagOf(source), context.getString(R.string.build_log_answer,
                SourceTestHelper.getOutcome(context, result)));

        if (!result.isOk() || result.content == ArchiveUtils.Content.UNKNOWN) {
            LOG.info("probeContent() {} didn't say what it holds", tagOf(source));
            return;
        }

        LOG.info("probeContent() {} holds {}", tagOf(source), result.content);

        source.setContent(result.content);

        if (sourceService != null) sourceService.save(source);
    }

    /**
     * Fetches a source into a directory of its own, whatever it hands over.
     *
     * <p>One file or a few hundred thousand, packed or plain: every source can be a whole
     * database, and what decides what it does to the table is where it stands in the list,
     * not how much it brought.
     */
    private String downloadIntoDir(NumberSource source, File dir,
                                   ProgressListener listener) {
        if (TextUtils.isEmpty(source.getUrl())) {
            noteFailed(source, context.getString(R.string.source_result_no_address));
            return context.getString(R.string.source_result_no_address);
        }

        source.setLastCheck(System.currentTimeMillis());

        File archive = new File(dir.getParentFile(), source.getId() + ".part");
        File tempDir = new File(dir.getParentFile(), source.getId() + "-tmp");

        try {
            String failure = download(source, archive, false, listener);

            if (failure != null) {
                noteFailed(source, failure);
                return failure;
            }

            FileUtils.delete(tempDir);
            createDir(tempDir);

            ArchiveUtils.Format format;
            int files;
            try (BufferedInputStream inputStream
                         = new BufferedInputStream(new FileInputStream(archive))) {
                format = ArchiveUtils.detect(inputStream);
                files = ArchiveUtils.unpackAll(inputStream, tempDir, "data_slice_0.dat");
            }

            if (files == 0) {
                String reason = context.getString(R.string.build_log_archive_no_files,
                        context.getString(SourceTestHelper.getFormatName(format)));

                noteFailed(source, context.getString(R.string.source_result_not_a_database));
                return reason;
            }

            ArchiveUtils.Content content = SqliteImporter.find(tempDir) != null
                    ? ArchiveUtils.Content.SQLITE : ArchiveUtils.Content.SIA;

            // what it was and what it held, because "1055 files" alone answers neither
            buildLog.line(tagOf(source), context.getString(R.string.build_log_unpacked,
                    files, context.getString(SourceTestHelper.getFormatName(format)),
                    context.getString(SourceTestHelper.getContentName(content))));

            FileUtils.delete(dir);

            if (!tempDir.renameTo(dir)) {
                LOG.warn("downloadIntoDir() couldn't put {} in place", dir);

                noteFailed(source, context.getString(R.string.source_result_failed));
                return context.getString(R.string.source_result_failed);
            }

            source.setFetchedUrl(source.getUrl());

            // what actually arrived has the last word about what this source hands over
            source.setContent(content);

            noteFetched(source, context.getString(R.string.source_result_files, files));

            return null;
        } catch (Cancelled e) {
            throw e; // not a failure of this step: the whole build was asked to stop
        } catch (Exception e) {
            LOG.error("downloadIntoDir() failed", e);

            String reason = describe(e);

            noteFailed(source, reason);

            return reason;
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
            noteFailed(source, context.getString(R.string.source_result_failed));
        }

        return ok;
    }

    /**
     * Says what the files actually start with, when the library won't read them.
     *
     * <p>"Not a database" is true and useless. These files say what they are in their first
     * few bytes, and a set that the library refuses is nearly always one where those bytes
     * are wrong - a rewrite that renamed some of the marks and not others, or renamed them
     * to something neither name. Naming the file and what stands in it turns an afternoon
     * into a minute.
     */
    private boolean logHeaders(File dir, String tag) {
        boolean looked = false;

        String[][] wanted = {
                {"data_slice_info.dat", "YACBSIAI", "MDI"},
                {"data_slice_0.dat", "YABF", "MTZF", "MTZD"},
                {"featured_slice_info.dat", "YACBSIAI", "MDI"},
                {"featured_slice_0.dat", "YABX", "MTZX"},
        };

        for (String[] expected : wanted) {
            File file = firstOf(dir, expected[0]);

            if (file == null) {
                buildLog.line(tag, context.getString(R.string.build_log_header_missing,
                        expected[0]));
                continue;
            }

            looked = true;

            String found = readHeader(file);

            boolean ok = false;
            for (int i = 1; i < expected.length; i++) {
                if (expected[i].equalsIgnoreCase(found)) ok = true;
            }

            if (ok) continue;

            StringBuilder names = new StringBuilder();
            for (int i = 1; i < expected.length; i++) {
                if (names.length() != 0) names.append(", ");
                names.append(expected[i]);
            }

            buildLog.line(tag, context.getString(R.string.build_log_header_wrong,
                    file.getName(), found, names.toString()));
        }

        return looked;
    }

    /**
     * Opens the archive itself and names what is in it.
     *
     * <p>For when nothing was unpacked: the library does its own unpacking for a zip and can
     * refuse before a single file is written, which leaves the directory holding whatever
     * was there before - or nothing at all - and no way to see what the source actually
     * sent. So the source is asked again for the first few megabytes and those are walked
     * entry by entry, reading the first bytes of each and skipping the rest.
     */
    private void logArchiveHeaders(NumberSource source, String tag) {
        DeferredInit.initNetwork();

        OkHttpClient client = SourceHttp.decorate(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build(),
                source, sourceService != null ? sourceService.getSecret(source.getId()) : null,
                false);

        Request request = new Request.Builder()
                .url(source.getUrl())
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME)
                .header("Range", "bytes=0-" + (HEADERS_PEEK_BYTES - 1))
                // packed in flight as well would say nothing about the files themselves
                .header("Accept-Encoding", "identity")
                .build();

        int[] said = {0};

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) return;

            try (InputStream in = body.byteStream()) {
                ArchiveUtils.headers(in, HEADERS_MAX_ENTRIES, (name, header) -> {
                    if (said[0]++ >= HEADERS_TO_SAY) return;

                    buildLog.line(tag, context.getString(R.string.build_log_archive_holds,
                            name.isEmpty() ? "?" : name,
                            header.isEmpty() ? "?" : header));
                });
            }
        } catch (Exception e) {
            LOG.warn("logArchiveHeaders() couldn't look inside", e);
        }

        if (said[0] == 0) {
            buildLog.line(tag, context.getString(R.string.build_log_archive_empty));
        }
    }

    /** The named file, or the first one of that kind when the numbered one isn't there. */
    private static File firstOf(File dir, String name) {
        File exact = new File(dir, name);
        if (exact.isFile()) return exact;

        String prefix = name.substring(0, name.lastIndexOf('_') + 1);

        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix)
                    && file.getName().endsWith(".dat")) {
                return file;
            }
        }

        return null;
    }

    /** The first bytes of a file, as the characters they stand for. */
    private static String readHeader(File file) {
        byte[] header = new byte[8];

        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(header);
            if (read <= 0) return "";

            StringBuilder text = new StringBuilder(read);

            for (int i = 0; i < read; i++) {
                char c = (char) (header[i] & 0xff);

                // a mark is letters; the first byte that isn't one is where it ends
                if (c < 'A' || c > 'z') break;

                text.append(c);
            }

            return text.toString();
        } catch (Exception e) {
            LOG.debug("readHeader() couldn't read {}", file, e);
            return "";
        }
    }

    /**
     * Whether a source has to be fetched again.
     *
     * <p>Two answers come before the schedule and before whoever asked. There is nothing of
     * it on the phone, so there is nothing to build from and it is fetched whatever anyone
     * says. Or it has been pointed somewhere else since, and what is here came from the old
     * address - building from that would quietly ignore the change.
     *
     * <p>Past those, it is the schedule and who is asking. A source that is only ever
     * fetched when asked is fetched when someone asks for a build, and left alone by the
     * clock. One set to be fetched once has been, and is left alone by both. One with a
     * schedule is fetched when its turn has come, whichever of the two started the run.
     */
    private boolean needsDownload(NumberSource source, boolean primary, Trigger trigger) {
        if (trigger == Trigger.FORCED) return true;

        File here = primary
                ? new File(new File(YacbHolder.getStorage().getDataDirPath(),
                        SiaConstants.SIA_PATH_PREFIX), "data_slice_info.dat")
                : getSourceDir(source);

        if (!here.exists()) return true;

        if (source.hasMoved()) {
            LOG.info("needsDownload() the source points somewhere else than what is here");
            return true;
        }

        switch (source.getUpdates()) {
            case MANUAL:
                return trigger == Trigger.BUILD;

            case ONCE:
                return false; // there is something here, so the one time has been

            default:
                return source.isDue(System.currentTimeMillis());
        }
    }

    /**
     * Fetches the source whose files the library keeps, into the place the library reads.
     *
     * @return null when it worked, otherwise what went wrong
     */
    private String downloadBase(NumberSource source, ProgressListener listener) {
        LOG.debug("downloadBase() {}", source.getUrl());

        if (TextUtils.isEmpty(source.getUrl())) {
            noteFailed(source, context.getString(R.string.source_result_no_address));
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
            if (Boolean.FALSE.equals(looksLikeZip(source))) {
                error = unpackBase(source, listener);
            } else if (!YacbHolder.getDbManager().downloadMainDb(source.getUrl())) {
                /*
                 * The library says no and nothing else, so what it said no to is looked at
                 * here: the archive is opened and its files named, which is where a wrong
                 * mark in one of them shows up.
                 */
                buildLog.line(tagOf(source),
                        context.getString(R.string.build_log_library_refused));

                logArchiveHeaders(source, tagOf(source));

                error = context.getString(R.string.source_result_failed);
            }
        } catch (Cancelled e) {
            throw e; // not a failure of this step: the whole build was asked to stop
        } catch (Exception e) {
            LOG.error("downloadBase() failed", e);
            error = describe(e);
        } finally {
            sourceService.setFetchingSource(null);
        }

        if (error != null) {
            noteFailed(source, error);
            return error;
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
            LOG.warn("downloadBase() what arrived isn't slice files");

            /*
             * And what it is instead, because "not a database" says nothing. What landed is
             * looked at first; when nothing did - the unpacking is the library's and it may
             * have refused before writing anything - the archive itself is opened and its
             * files are named where they lie.
             */
            if (!logHeaders(new File(YacbHolder.getStorage().getDataDirPath(),
                    SiaConstants.SIA_PATH_PREFIX), tagOf(source))) {
                logArchiveHeaders(source, tagOf(source));
            }

            /*
             * Which is not the same as it being nothing. The same address can hand over one
             * SQLite database instead, and that is a database - it just isn't the library's
             * kind, so the library unpacked it and found nothing it knew. Before calling
             * this a failure it is fetched into a place of its own and looked at properly.
             *
             * The cost is one extra download, once: what it turns out to be is written on
             * the source, and from then on it is fetched straight into that place.
             */
            String failure = downloadIntoDir(source, getSourceDir(source), listener);

            if (failure == null) {
                /*
                 * And it is usable after all - as a SQLite database, or as slice files that
                 * the library wouldn't take but the table can be built from either way. Not
                 * the library's, so the featured names and the country data stay missing;
                 * the numbers, which is what a call is answered with, are there.
                 */
                LOG.info("downloadBase() usable as {} out of a place of its own",
                        source.getContent());

                buildLog.line(tagOf(source), context.getString(
                        source.getContent() == ArchiveUtils.Content.SQLITE
                                ? R.string.build_log_is_a_database
                                : R.string.build_log_not_the_librarys));

                return null;
            }

            LOG.error("downloadBase() what arrived can't be read as a database");

            noteFailed(source, context.getString(R.string.source_result_not_a_database));
            return context.getString(R.string.db_build_not_readable);
        }

        // slice files, which is what the library keeps - said out loud so nothing guesses again
        source.setContent(ArchiveUtils.Content.SIA);

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
    private String unpackBase(NumberSource source, ProgressListener listener) {
        File dataDir = new File(YacbHolder.getStorage().getDataDirPath());

        String name = dirName();
        if (name == null) return context.getString(R.string.source_result_failed);

        File dir = new File(dataDir, name);
        File tempDir = new File(dataDir, name + "-tmp");
        File oldDir = new File(dataDir, name + "-old");
        File archive = new File(dataDir, name + "-download.part");

        try {
            String failure = download(source, archive, false, listener);
            if (failure != null) return failure;

            phase(listener, R.string.unpacking_db);

            FileUtils.delete(tempDir);
            createDir(tempDir);

            int files;
            try (InputStream inputStream
                         = new BufferedInputStream(new FileInputStream(archive))) {
                files = ArchiveUtils.unpackAll(inputStream, tempDir, "data_slice_0.dat");
            }

            LOG.info("unpackBase() unpacked {} files", files);

            if (files == 0) {
                return context.getString(R.string.build_log_archive_no_files,
                        context.getString(R.string.source_format_tar_gzip));
            }

            buildLog.line(tagOf(source), context.getString(R.string.build_log_unpacked,
                    files, context.getString(R.string.source_format_tar_gzip),
                    context.getString(SourceTestHelper.getContentName(
                            ArchiveUtils.Content.SIA))));

            FileUtils.delete(oldDir);

            if (dir.exists() && !dir.renameTo(oldDir)) {
                LOG.warn("unpackBase() couldn't move the old database out of the way");
                return context.getString(R.string.build_log_couldnt_replace);
            }

            if (!tempDir.renameTo(dir)) {
                LOG.warn("unpackBase() couldn't put the new database in place");

                if (oldDir.exists()) oldDir.renameTo(dir); // leave what worked where it was
                return context.getString(R.string.build_log_couldnt_replace);
            }

            FileUtils.delete(oldDir);

            return null;
        } catch (Cancelled e) {
            throw e; // not a failure of this step: the whole build was asked to stop
        } catch (Exception e) {
            LOG.error("unpackBase() failed", e);
            return describe(e);
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
     * Says how far the download has got, now and then.
     *
     * <p>Every few hundred kilobytes rather than every buffer: the notification is redrawn
     * by someone else's process and the log is a file, and neither is worth touching eight
     * thousand times for one source.
     *
     * @return when it last said, which is what to pass in next time
     */
    private long sayProgress(NumberSource source, long written, long total, long lastSaid,
                             ProgressListener listener) {
        if (lastSaid != 0 && written - lastSaid < PROGRESS_EVERY_BYTES) return lastSaid;

        checkCancelled(); // every half megabyte: a download stops within a moment too

        if (listener != null && total > 0) {
            // as tenths of a percent, because a notification counts in whole steps
            listener.onProgress((int) (written * 1000 / total), 1000);
        }

        buildLog.line(tagOf(source), total > 0
                ? context.getString(R.string.build_log_downloaded_of,
                        megabytes(written), megabytes(total))
                : context.getString(R.string.build_log_downloaded, megabytes(written)));

        return written;
    }

    /** A size a person reads rather than a number of bytes. */
    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
    }

    /**
     * Fetches the source into a file.
     *
     * @param unpack whether a packed answer is unpacked on the way. What a source hands over
     *               is unpacked into a directory of its own afterwards, whether it is one
     *               file or a few hundred thousand, so it isn't.
     */
    private String download(NumberSource source, File target, boolean unpack,
                            ProgressListener listener) {
        DeferredInit.initNetwork();

        // an address that can't even be parsed is the commonest typo there is
        if (HttpUrl.parse(source.getUrl()) == null) {
            return context.getString(R.string.source_test_bad_url) + ": " + source.getUrl();
        }

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

                // the two that mean "you", and everything else with its number
                return response.code() == 401 || response.code() == 403
                        ? context.getString(R.string.source_test_unauthorized, response.code())
                        : context.getString(R.string.source_test_http_error, response.code())
                                + (TextUtils.isEmpty(response.message())
                                        ? "" : " " + response.message());
            }

            ResponseBody body = response.body();
            if (body == null) return context.getString(R.string.source_test_empty);

            /*
             * How much of it there is, when the server says. A source can be tens of
             * megabytes over a connection that is having a bad day, and a build that says
             * nothing for four minutes looks exactly like one that has died.
             */
            long total = body.contentLength();

            try (InputStream inputStream = body.byteStream();
                 OutputStream outputStream = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];

                long written = 0;
                long lastSaid = 0;

                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);

                    written += read;

                    lastSaid = sayProgress(source, written, total, lastSaid, listener);
                }

                sayProgress(source, written, total, 0, listener);

                if (written == 0) return context.getString(R.string.source_test_empty);
            }

            return null;
        } catch (Cancelled e) {
            throw e; // not a failure of this step: the whole build was asked to stop
        } catch (Exception e) {
            LOG.warn("download() failed", e);
            return context.getString(R.string.source_test_unreachable, describe(e));
        }
    }

    /** An exception in the words a log line has room for: its kind, and what it said. */
    private static String describe(Exception e) {
        String message = e.getLocalizedMessage();

        return TextUtils.isEmpty(message)
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
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
                                     ProgressListener listener,
                                     NumbersCompiler.Counts[] totals) {
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

        totals[0] = result.totals;

        noteSourceMeta(compiler, sources, primary);

        // and only now does it become the database the app looks numbers up in
        if (!compiler.promote()) {
            LOG.error("buildNumbersTable() the built database couldn't be put in place");

            return context.getString(R.string.db_build_table_failed);
        }

        /*
         * What was built is a new file in the place of the old one, so a lookup that had the
         * old one open is still reading it - and what the app remembers about the numbers it
         * has already been asked came out of the database this one replaces.
         */
        if (YacbHolder.getNumbersLookup() != null) YacbHolder.getNumbersLookup().reload();
        if (YacbHolder.getNumberInfoCache() != null) YacbHolder.getNumberInfoCache().clear();

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

            File database = null;

            if (source.getType() == NumberSource.Type.DATABASE) {
                /*
                 * Where its files went is what decides where they are read from, and a
                 * source can have turned out to be a SQLite database after the order was
                 * worked out - the fetch says so, and this is after the fetch. Reading the
                 * library's directory for a source that is no longer in it would find
                 * nothing at all.
                 */
                if (source == primary
                        && source.getContent() != ArchiveUtils.Content.SQLITE) {
                    dir = new File(dataDir, SiaConstants.SIA_PATH_PREFIX);
                    updatesDir = new File(dataDir, SiaConstants.SIA_SECONDARY_PATH_PREFIX);
                } else {
                    dir = getSourceDir(source);

                    // what is lying there decides how it is read, not what anyone expected
                    database = SqliteImporter.find(dir);
                }
            }

            inputs.add(new NumbersCompiler.Input(
                    source, tagOf(source), dir, updatesDir, database));
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
    private void noteSourceMeta(NumbersCompiler compiler, List<NumberSource> sources,
                                NumberSource primary) {
        if (sourceService == null) return;

        int version = YacbHolder.getCommunityDatabase().getEffectiveDbVersion();

        for (NumbersCompiler.SourceCount count : compiler.getSourceCounts()) {
            for (NumberSource source : sources) {
                if (!source.getId().equals(count.uuid)) continue;

                source.setEntries(count.count);

                /*
                 * The version the library reports belongs to the source whose files it
                 * keeps, and to that one only. Any other source says its own - a SQLite
                 * database carries it in its meta table - and writing the library's over
                 * that would replace a real version with a zero.
                 */
                if (source == primary && version > 0) source.setVersion(version);

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

    /**
     * The same, for a fetch that didn't work.
     *
     * <p>What the source said about itself described what it had handed over, and it has
     * handed over nothing: the count, the version and the kind of thing behind the address
     * are about data that is gone or was never there. A row that goes on saying "400.000
     * numbers" about an address answering 404 is worse than one that says nothing.
     */
    private void noteFailed(NumberSource source, String result) {
        source.forgetFetched();

        note(source, result);
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
