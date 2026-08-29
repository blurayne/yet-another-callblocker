package dummydomain.yetanothercallblocker.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseDataSlice;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;

/**
 * Filters the number database, keeping the unfiltered one around.
 *
 * <p>Filtering deletes and rewrites the database files in place, so without a copy of the
 * unfiltered database the only way back is downloading it again. This keeps that copy (the
 * "master") next to the database it filters, which also means that filtering always starts from
 * the unfiltered database instead of narrowing down an already filtered one.
 *
 * <p>The database the app uses stays where the library expects it ({@code sia/}); the master is
 * the copy ({@code sia-master/}). It exists exactly while the database in use is filtered, so
 * "revert to the master" is putting it back, and a run that filters nothing out drops it again.
 */
public class DbFilteringService {

    /** What a filtering run did. */
    public enum Status {
        /** The database was filtered. */
        FILTERED,
        /** The filter matched everything, so the database was left as it is. */
        NOTHING_FILTERED,
        /** Filtering is turned off, or no prefixes are set. */
        NO_FILTER,
        /** There's no database to filter and it couldn't be downloaded. */
        NO_DATABASE,
        /** The user stopped the run; the unfiltered database was put back. */
        CANCELLED,
        FAILED
    }

    /** Reports how far a filtering run has got. */
    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    /** The outcome of a filtering run. */
    public static class Result {

        public final Status status;
        public final int entriesBefore;
        public final int entriesAfter;

        Result(Status status, int entriesBefore, int entriesAfter) {
            this.status = status;
            this.entriesBefore = entriesBefore;
            this.entriesAfter = entriesAfter;
        }

        public int getRemovedEntries() {
            return Math.max(0, entriesBefore - entriesAfter);
        }

    }

    private static final String MASTER_DIR_NAME = "sia-master";

    private static final String SLICE_NAME_PREFIX = "data_slice_";
    private static final String SLICE_NAME_POSTFIX = ".dat";
    private static final String INFO_FILE_NAME = SLICE_NAME_PREFIX + "info" + SLICE_NAME_POSTFIX;

    private static final String SECONDARY_SLICE_POSTFIX = ".sia";

    private static final Logger LOG = LoggerFactory.getLogger(DbFilteringService.class);

    /** Only one run happens at a time: the task service handles its intents one by one. */
    private static final AtomicBoolean CANCELLATION_REQUESTED = new AtomicBoolean();

    private final Settings settings;

    public DbFilteringService(Settings settings) {
        this.settings = settings;
    }

    /** Asks the filtering run that is in progress, if any, to stop. */
    public static void requestCancellation() {
        LOG.debug("requestCancellation()");

        CANCELLATION_REQUESTED.set(true);
    }

    /** Whether a copy of the unfiltered database is kept. */
    public boolean hasMaster() {
        return isDatabase(getMasterDir());
    }

    /**
     * Filters the database, downloading it first if there is none.
     *
     * <p>Always filters the master (the unfiltered database), so filtering twice with different
     * settings gives the same result as filtering once with the second ones.
     */
    public Result filter() {
        return filter(null);
    }

    /**
     * @param listener notified as the database parts are processed, may be null
     */
    public Result filter(ProgressListener listener) {
        LOG.debug("filter() started");

        CANCELLATION_REQUESTED.set(false);

        NumberFilter numberFilter = DbFilteringUtils.getNumberFilter(settings);
        if (numberFilter == null) {
            LOG.info("filter() filtering is not configured");
            return new Result(Status.NO_FILTER, 0, 0);
        }

        File mainDir = getMainDir();
        File masterDir = getMasterDir();

        try {
            if (isDatabase(masterDir)) {
                LOG.debug("filter() restoring the master database");
                if (!replaceDir(mainDir, masterDir)) return new Result(Status.FAILED, 0, 0);
            } else {
                if (!isDatabase(mainDir) && !downloadDb()) {
                    LOG.warn("filter() there's no database to filter");
                    return new Result(Status.NO_DATABASE, 0, 0);
                }

                LOG.debug("filter() keeping a copy of the unfiltered database");
                if (!copyDir(mainDir, masterDir)) return new Result(Status.FAILED, 0, 0);
            }

            reloadDatabases();

            int entriesBefore = readEntryCount(mainDir);

            int entriesAfter = filterFiles(numberFilter, listener);

            if (entriesAfter < 0) { // cancelled part way through
                LOG.info("filter() cancelled, restoring the unfiltered database");

                replaceDir(mainDir, masterDir);
                settings.setDbFiltered(false);
                reloadDatabases();

                return new Result(Status.CANCELLED, entriesBefore, entriesBefore);
            }

            reloadDatabases();

            if (entriesAfter >= entriesBefore) {
                // the database in use is the unfiltered one, so the copy is just a second one
                LOG.info("filter() nothing was filtered out");

                delete(masterDir);
                settings.setDbFiltered(false);

                return new Result(Status.NOTHING_FILTERED, entriesBefore, entriesAfter);
            }

            settings.setDbFiltered(true);

            if (!settings.getDbFilteringKeepMaster()) {
                LOG.debug("filter() dropping the master database");
                delete(masterDir);
            }

            LOG.info("filter() filtered {} entries down to {}", entriesBefore, entriesAfter);
            return new Result(Status.FILTERED, entriesBefore, entriesAfter);
        } catch (Exception e) {
            LOG.error("filter() failed", e);
            return new Result(Status.FAILED, 0, 0);
        }
    }

    /**
     * Re-applies the filter after the database was updated.
     *
     * @param mainDbReplaced whether the whole database was downloaded again
     *                       (rather than the updates database having changed)
     */
    public Result updateFilter(boolean mainDbReplaced) {
        LOG.debug("updateFilter({})", mainDbReplaced);

        if (!settings.isDbFilteringEnabled() || !settings.isDbFiltered()) {
            return new Result(Status.NO_FILTER, 0, 0);
        }

        if (mainDbReplaced) {
            // what was just downloaded is the new unfiltered database
            delete(getMasterDir());

            return filter();
        }

        /*
         * Only the updates database changed. The main database is filtered already, so it is
         * left alone rather than restored from the master and filtered again - that would copy
         * the whole database on every update.
         */
        NumberFilter numberFilter = DbFilteringUtils.getNumberFilter(settings);
        if (numberFilter == null) return new Result(Status.NO_FILTER, 0, 0);

        try {
            int entriesLeft = filterFiles(numberFilter, null);

            reloadDatabases();

            return new Result(entriesLeft < 0 ? Status.CANCELLED : Status.FILTERED, 0, 0);
        } catch (Exception e) {
            LOG.error("updateFilter() failed", e);
            return new Result(Status.FAILED, 0, 0);
        }
    }

    /** Puts the unfiltered database back. */
    public boolean revertToMaster() {
        LOG.debug("revertToMaster() started");

        File masterDir = getMasterDir();
        if (!isDatabase(masterDir)) {
            LOG.warn("revertToMaster() there's no master database");
            return false;
        }

        if (!replaceDir(getMainDir(), masterDir)) return false;

        delete(masterDir);
        settings.setDbFiltered(false);

        reloadDatabases();

        LOG.info("revertToMaster() the unfiltered database is back in use");
        return true;
    }

    /**
     * Filters the database part by part, which is what
     * {@code DbManager.filterDb()} does - except that this can report progress,
     * can be stopped, and knows how many entries are left when it's done.
     *
     * @return the number of entries left, or -1 if the run was cancelled
     */
    private int filterFiles(NumberFilter numberFilter, ProgressListener listener) {
        List<File> files = new ArrayList<>();

        File mainDir = getMainDir();
        File[] mainFiles = mainDir.listFiles((d, name) -> isSliceFile(name));
        if (mainFiles != null) files.addAll(Arrays.asList(mainFiles));

        File secondaryDir = new File(getDataDir(), SiaConstants.SIA_SECONDARY_PATH_PREFIX);
        File[] secondaryFiles = secondaryDir.listFiles(
                (d, name) -> name.endsWith(SECONDARY_SLICE_POSTFIX));
        if (secondaryFiles != null) files.addAll(Arrays.asList(secondaryFiles));

        int total = files.size();
        int entriesLeft = 0;

        for (int i = 0; i < total; i++) {
            if (CANCELLATION_REQUESTED.get()) return -1;

            entriesLeft += filterOrDeleteSlice(files.get(i), numberFilter);

            if (listener != null) listener.onProgress(i + 1, total);
        }

        return entriesLeft;
    }

    /** @return the number of entries the file holds afterwards (0 if it was deleted) */
    private int filterOrDeleteSlice(File file, NumberFilter numberFilter) {
        int entries = 0;

        if (numberFilter.isDetailed()) {
            entries = filterSlice(file, numberFilter);
        } else if (numberFilter.keepPrefix(getSlicePrefix(file.getName()))) {
            entries = countSliceEntries(file);
        }

        if (entries <= 0 && file.exists() && !file.delete()) {
            LOG.warn("filterOrDeleteSlice() couldn't delete {}", file);
        }

        return entries;
    }

    /** @return the number of entries kept, 0 if the file is to be dropped */
    private int filterSlice(File file, NumberFilter numberFilter) {
        CommunityDatabaseDataSlice original = new CommunityDatabaseDataSlice();

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            original.loadFromStream(inputStream);
        } catch (Exception e) {
            LOG.warn("filterSlice() couldn't read {}", file, e);
            return 0;
        }

        CommunityDatabaseDataSlice slice = new CommunityDatabaseDataSlice();
        if (!slice.partialClone(original, original.generateFilteredIndex(numberFilter))) {
            return 0; // nothing in this part matches the filter
        }

        try (BufferedOutputStream outputStream
                     = new BufferedOutputStream(new FileOutputStream(file))) {
            slice.writeMerged(null, outputStream);
        } catch (Exception e) {
            LOG.error("filterSlice() couldn't write {}", file, e);
            return 0;
        }

        return slice.getNumberOfItems();
    }

    /** The part of a file name the filter matches against: the digits of the numbers it holds. */
    private static String getSlicePrefix(String name) {
        if (name.endsWith(SECONDARY_SLICE_POSTFIX)) {
            return name.substring(0, name.length() - SECONDARY_SLICE_POSTFIX.length());
        }
        return name.substring(SLICE_NAME_PREFIX.length(),
                name.length() - SLICE_NAME_POSTFIX.length());
    }

    private boolean downloadDb() {
        LOG.debug("downloadDb() no database, downloading it");

        try {
            return YacbHolder.getDbManager().downloadMainDb(settings.getDatabaseDownloadUrl());
        } catch (Exception e) {
            LOG.warn("downloadDb() failed", e);
            return false;
        }
    }

    private static void reloadDatabases() {
        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();
    }

    /** The number of entries the database says it has (filtering doesn't update it). */
    private static int readEntryCount(File dir) {
        File file = new File(dir, INFO_FILE_NAME);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file)))) {
            reader.readLine(); // the header
            reader.readLine(); // the database version

            String entries = reader.readLine();
            return entries != null ? Integer.parseInt(entries.trim()) : 0;
        } catch (Exception e) {
            LOG.warn("readEntryCount() couldn't read {}", file, e);
            return 0;
        }
    }

    /** The number of entries a database part holds. */
    private static int countSliceEntries(File file) {
        CommunityDatabaseDataSlice slice = new CommunityDatabaseDataSlice();

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            slice.loadFromStream(inputStream);
            return slice.getNumberOfItems();
        } catch (Exception e) {
            LOG.warn("countSliceEntries() couldn't read {}", file, e);
            return 0;
        }
    }

    private static boolean isSliceFile(String name) {
        return name.startsWith(SLICE_NAME_PREFIX) && name.endsWith(SLICE_NAME_POSTFIX)
                && name.length() > SLICE_NAME_PREFIX.length()
                && Character.isDigit(name.charAt(SLICE_NAME_PREFIX.length()));
    }

    private static boolean isDatabase(File dir) {
        return new File(dir, INFO_FILE_NAME).exists();
    }

    private File getMainDir() {
        return new File(getDataDir(), SiaConstants.SIA_PATH_PREFIX);
    }

    private File getMasterDir() {
        return new File(getDataDir(), MASTER_DIR_NAME);
    }

    private static String getDataDir() {
        return YacbHolder.getStorage().getDataDirPath();
    }

    private static boolean replaceDir(File target, File source) {
        delete(target);
        return copyDir(source, target);
    }

    private static boolean copyDir(File source, File target) {
        File[] files = source.listFiles();
        if (files == null) {
            LOG.warn("copyDir() {} isn't a directory", source);
            return false;
        }

        if (!target.isDirectory() && !target.mkdirs()) {
            LOG.warn("copyDir() couldn't create {}", target);
            return false;
        }

        byte[] buffer = new byte[8192];

        for (File file : files) {
            if (!file.isFile()) continue;

            try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
                 OutputStream outputStream = new FileOutputStream(new File(target, file.getName()))) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            } catch (IOException e) {
                LOG.error("copyDir() couldn't copy {}", file, e);
                return false;
            }
        }

        return true;
    }

    private static void delete(File file) {
        if (!file.exists()) return;

        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                delete(child);
            }
        }

        if (!file.delete()) LOG.warn("delete() couldn't delete {}", file);
    }

}
