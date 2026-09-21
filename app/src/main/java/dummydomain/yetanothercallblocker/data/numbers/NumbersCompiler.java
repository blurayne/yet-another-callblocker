package dummydomain.yetanothercallblocker.data.numbers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dummydomain.yetanothercallblocker.data.source.NumberSource;

/**
 * Builds the one table out of everything the sources brought.
 *
 * <p>The order is the order of the list: the source that carries the database fills the empty
 * table, the library's own updates go on top of it, and every further source is a layer that
 * changes what it knows about and leaves the rest alone. A layer can also take a number out
 * again, which is a row that goes away rather than a row that says "no".
 *
 * <p>When it is complete a copy is put aside, and only then is the filter applied - so
 * "unfiltered" is a file that exists rather than a download that would have to happen again,
 * exactly as it was with the database of files.
 */
public class NumbersCompiler {

    private static final Logger LOG = LoggerFactory.getLogger(NumbersCompiler.class);

    /** What a build ended up with. */
    public static class Result {

        public final boolean ok;
        public final long numbers;
        public final int sources;
        /** What went wrong, for whoever has to read it, or null. */
        public final String error;

        Result(boolean ok, long numbers, int sources) {
            this(ok, numbers, sources, null);
        }

        Result(boolean ok, long numbers, int sources, String error) {
            this.ok = ok;
            this.numbers = numbers;
            this.sources = sources;
            this.error = error;
        }

    }

    /** Where a build has got to, in files. */
    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    /** One source, and how much of the table came from it. */
    public static class SourceCount {

        public final String uuid;
        public final String name;
        public final int type;
        public final int layer;
        public final long count;

        SourceCount(String uuid, String name, int type, int layer, long count) {
            this.uuid = uuid;
            this.name = name;
            this.type = type;
            this.layer = layer;
            this.count = count;
        }

    }

    private static final String SLICE_PREFIX = "data_slice_";
    private static final String SLICE_POSTFIX = ".dat";
    private static final String SECONDARY_POSTFIX = ".sia";

    /**
     * How many entries are written before the work so far is made permanent.
     *
     * <p>Kept small on purpose. Everything an open transaction has changed is held - as
     * pages waiting to be written and as a journal to undo them with - and a phone that is
     * asked to keep a million rows in that state gets its app killed rather than an error.
     * Committing costs a fraction of a second and hands all of it back.
     */
    private static final int COMMIT_EVERY = 25_000;

    /** And how many files, for a database whose files hold a handful of numbers each. */
    private static final int COMMIT_EVERY_FILES = 1_000;

    /** How often the log says where the build is and what it is holding. */
    private static final int LOG_EVERY_FILES = 10_000;

    private final Context context;

    public NumbersCompiler(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Empties the table and fills it again.
     *
     * @param sources the sources that are switched on, in the order they are asked
     * @param baseDir where the downloaded database lies
     * @param secondaryDir where the library keeps the updates it fetched itself
     * @param layersDir where the layers of the other sources were kept
     */
    public Result compile(List<NumberSource> sources, File baseDir, File secondaryDir,
                          File layersDir, ProgressListener listener) {
        LOG.info("compile() started with {} sources", sources.size());

        long startTime = System.currentTimeMillis();

        NumbersDb helper = new NumbersDb(context);

        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            helper.recreate(db);

            List<String> baseFiles = listNames(baseDir, SLICE_PREFIX, SLICE_POSTFIX);
            List<String> secondaryFiles = listNames(secondaryDir, "", SECONDARY_POSTFIX);

            int total = baseFiles.size() + secondaryFiles.size()
                    + Math.max(0, sources.size() - 1);

            int written = 0;

            db.beginTransaction();
            try (NumbersWriter writer = new NumbersWriter(db)) {
                Run run = new Run(db, writer, listener, total);

                for (int i = 0; i < sources.size(); i++) {
                    NumberSource source = sources.get(i);

                    int sourceId = writer.addSource(source.getId(), source.getName(),
                            source.getType().ordinal(), i);

                    if (i == 0) {
                        // the database itself, and then what the library fetched since
                        run.readAll(baseDir, baseFiles, sourceId, false);
                        run.readAll(secondaryDir, secondaryFiles, sourceId, false);
                    } else {
                        File file = new File(layersDir, source.getId() + SLICE_POSTFIX);

                        if (file.exists()) {
                            run.read(file, sourceId, true);
                        } else {
                            run.step();
                        }
                    }

                    written++;
                }

                NumbersDb.setMeta(db, NumbersDb.META_COMPILED,
                        String.valueOf(System.currentTimeMillis()));
                NumbersDb.setMeta(db, NumbersDb.META_FILTERED, "0");

                db.setTransactionSuccessful();
            } finally {
                // there may be none left to end when this is reached the hard way
                if (db.inTransaction()) db.endTransaction();
            }

            long numbers = NumbersDb.getCount(db);
            NumbersDb.setMeta(db, NumbersDb.META_COUNT, String.valueOf(numbers));

            countSources(db);

            LOG.info("compile() {} numbers from {} sources in {} ms",
                    numbers, written, System.currentTimeMillis() - startTime);

            return new Result(true, numbers, written);
        } catch (OutOfMemoryError e) {
            /*
             * Caught rather than left to kill the app: a database of a few hundred thousand
             * files is more than some phones can hold at once, and the difference between a
             * build that says so and an app that disappears is the difference between a
             * problem that can be looked at and one that can only be guessed at.
             */
            LOG.error("compile() ran out of memory", e);

            return new Result(false, 0, 0, "OutOfMemoryError (max heap "
                    + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB)");
        } catch (Throwable e) {
            LOG.error("compile() failed", e);

            String message = e.getLocalizedMessage();

            return new Result(false, 0, 0, e.getClass().getSimpleName()
                    + (message != null ? ": " + message : ""));
        } finally {
            helper.close();
        }
    }

    /**
     * One build, as it walks the files.
     *
     * <p>It keeps what the walk needs to know - how far it has got, how much is waiting to be
     * made permanent - so that the files can be read from wherever they are without the count
     * of them being carried around by hand.
     */
    private final class Run {

        private final SQLiteDatabase db;
        private final NumbersWriter writer;
        private final ProgressListener listener;
        private final int total;

        private int done;

        /** Entries written since the last time the work was made permanent. */
        private int pending;

        /** Files read since then; a database of tiny files would otherwise never commit. */
        private int files;

        Run(SQLiteDatabase db, NumbersWriter writer, ProgressListener listener, int total) {
            this.db = db;
            this.writer = writer;
            this.listener = listener;
            this.total = total;
        }

        void readAll(File dir, List<String> names, int sourceId, boolean asLayer) {
            for (String name : names) {
                read(new File(dir, name), sourceId, asLayer);
            }
        }

        void read(File file, int sourceId, boolean asLayer) {
            pending += NumbersCompiler.this.read(file, writer, sourceId, asLayer);
            files++;

            commitIfDue();

            step();
        }

        /** Says how far along it is; one file further. */
        void step() {
            report(listener, ++done, total);

            /*
             * Left in the log on purpose: when a build disappears rather than fails, the
             * last of these lines is the only thing that says how far it got and how much it
             * was holding at the time.
             */
            if (done % LOG_EVERY_FILES == 0) {
                Runtime runtime = Runtime.getRuntime();

                LOG.info("compile() {}/{} files, heap {} MB of {} MB", done, total,
                        (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                        runtime.maxMemory() / (1024 * 1024));
            }
        }

        /*
         * Written down in chunks rather than in one go: a database of two million rows in a
         * single transaction wants as much room again for the journal, and a phone that runs
         * out of it has nothing to show for the wait.
         */
        private void commitIfDue() {
            if (pending < COMMIT_EVERY && files < COMMIT_EVERY_FILES) return;

            db.setTransactionSuccessful();
            db.endTransaction();
            db.beginTransaction();

            pending = 0;
            files = 0;
        }

    }

    /**
     * Reads one slice file into the table.
     *
     * @return how many entries it held
     */
    private int read(File file, NumbersWriter writer, int sourceId, boolean asLayer) {
        int[] count = {0};

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            SliceReader.read(inputStream, new SliceReader.Visitor() {
                @Override
                public void onNumber(long number, int positive, int negative,
                                     int neutral, int category) {
                    int rating = NumberFlags.ratingOf(positive, negative, neutral);
                    int score = NumberFlags.scoreOf(positive, negative);

                    if (asLayer) {
                        writer.merge(number, rating, category, score, false, sourceId);
                    } else {
                        writer.put(number, NumberFlags.of(rating, category, 0), score, sourceId);
                    }

                    count[0]++;
                }

                @Override
                public void onDeleted(long number) {
                    writer.delete(number);

                    count[0]++;
                }
            });
        } catch (Exception e) {
            LOG.warn("read() couldn't read {}", file, e);
        }

        return count[0];
    }

    /** Puts the finished database aside, so that filtering has something to go back to. */
    public boolean makeShadowCopy() {
        File file = NumbersDb.getFile(context);
        if (!file.exists()) return false;

        return copy(file, NumbersDb.getShadowFile(context));
    }

    public boolean hasShadowCopy() {
        return NumbersDb.getShadowFile(context).exists();
    }

    public void dropShadowCopy() {
        File file = NumbersDb.getShadowFile(context);

        if (file.exists() && !file.delete()) LOG.warn("dropShadowCopy() couldn't delete {}", file);
    }

    /** Puts the copy back, which undoes the filtering. */
    public boolean revertToShadowCopy() {
        File shadow = NumbersDb.getShadowFile(context);
        if (!shadow.exists()) return false;

        if (!copy(shadow, NumbersDb.getFile(context))) return false;

        NumbersDb helper = new NumbersDb(context);
        try {
            NumbersDb.setMeta(helper.getWritableDatabase(), NumbersDb.META_FILTERED, "0");
        } catch (Exception e) {
            LOG.warn("revertToShadowCopy() couldn't note it", e);
        } finally {
            helper.close();
        }

        return true;
    }

    /**
     * Throws away everything outside the country codes that are kept.
     *
     * @return how many numbers are left, or -1 when nothing was filtered
     */
    public long filter(List<String> prefixesToKeep, int shortNumbersMaxLength) {
        String condition = NumberRanges.keepCondition(prefixesToKeep, shortNumbersMaxLength);
        if (condition == null) {
            LOG.info("filter() nothing is set to keep");
            return -1;
        }

        NumbersDb helper = new NumbersDb(context);

        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            long before = NumbersDb.getCount(db);

            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM numbers WHERE NOT (" + condition + ")");
                db.execSQL("DELETE FROM names WHERE NOT (" + condition + ")");

                NumbersDb.setMeta(db, NumbersDb.META_FILTERED, "1");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            long after = NumbersDb.getCount(db);
            NumbersDb.setMeta(db, NumbersDb.META_COUNT, String.valueOf(after));

            countSources(db);

            // the rows are gone, but the space they took is only given back here
            db.execSQL("VACUUM");

            LOG.info("filter() {} numbers of {} are left", after, before);

            return after;
        } catch (Exception e) {
            LOG.error("filter() failed", e);
            return -1;
        } finally {
            helper.close();
        }
    }

    /** Everything a screen says about the table, read in one go. */
    public static class Info {

        public final long count;
        public final long compiledTime;
        public final boolean filtered;
        public final long size;
        public final long shadowSize;
        /** Whether the table could be read at all; it can't while it is being written. */
        public final boolean readable;

        Info(long count, long compiledTime, boolean filtered, long size, long shadowSize) {
            this(count, compiledTime, filtered, size, shadowSize, true);
        }

        Info(long count, long compiledTime, boolean filtered, long size, long shadowSize,
             boolean readable) {
            this.count = count;
            this.compiledTime = compiledTime;
            this.filtered = filtered;
            this.size = size;
            this.shadowSize = shadowSize;
            this.readable = readable;
        }

    }

    /**
     * What the table is: how much is in it, when it was built, whether it has been narrowed.
     *
     * <p>One open and three reads, rather than one open each. It still belongs on a
     * background thread: while a build is running the table is being written to, and asking
     * it anything waits for that to reach a point where it can answer.
     */
    public Info getInfo() {
        NumbersDb helper = new NumbersDb(context);

        try {
            SQLiteDatabase db = helper.getReadableDatabase();

            String count = NumbersDb.getMeta(db, NumbersDb.META_COUNT, null);

            String compiled = NumbersDb.getMeta(db, NumbersDb.META_COMPILED, null);

            boolean filtered = "1".equals(NumbersDb.getMeta(db, NumbersDb.META_FILTERED, "0"));

            return new Info(count != null ? Long.parseLong(count) : -1,
                    compiled != null ? Long.parseLong(compiled) : 0,
                    filtered, getSize(), getShadowSize());
        } catch (Exception e) {
            /*
             * Most likely because a build has the table open and hasn't reached a point where
             * it can answer. "Couldn't ask" and "there is nothing in it" are different things
             * and the screen says which.
             */
            LOG.warn("getInfo()", e);

            return new Info(-1, 0, false, 0, 0, false);
        } finally {
            helper.close();
        }
    }

    /**
     * How many numbers the table holds, as it was written down when it was last changed.
     *
     * <p>Read rather than counted, so that a screen can ask without waiting for a walk over
     * every row.
     */
    public long getCount() {
        NumbersDb helper = new NumbersDb(context);

        try {
            String value = NumbersDb.getMeta(helper.getReadableDatabase(),
                    NumbersDb.META_COUNT, null);

            return value != null ? Long.parseLong(value) : -1;
        } catch (Exception e) {
            LOG.warn("getCount()", e);
            return -1;
        } finally {
            helper.close();
        }
    }

    public boolean isFiltered() {
        NumbersDb helper = new NumbersDb(context);

        try {
            return "1".equals(NumbersDb.getMeta(helper.getReadableDatabase(),
                    NumbersDb.META_FILTERED, "0"));
        } catch (Exception e) {
            LOG.warn("isFiltered()", e);
            return false;
        } finally {
            helper.close();
        }
    }

    /**
     * Writes down how many rows each source has left in the table.
     *
     * <p>Counted here, where a build or a filtering has just walked the whole table anyway,
     * so that a screen can show it without counting anything.
     */
    private static void countSources(SQLiteDatabase db) {
        db.execSQL("UPDATE sources SET count ="
                + " (SELECT COUNT(*) FROM numbers WHERE numbers.source = sources.id)");
    }

    /** What each source contributed, in the order the table was built in. */
    public List<SourceCount> getSourceCounts() {
        List<SourceCount> counts = new ArrayList<>();

        NumbersDb helper = new NumbersDb(context);

        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT uuid, name, type, layer, count FROM sources ORDER BY layer", null)) {
            while (cursor.moveToNext()) {
                counts.add(new SourceCount(cursor.getString(0), cursor.getString(1),
                        cursor.getInt(2), cursor.getInt(3), cursor.getLong(4)));
            }
        } catch (Exception e) {
            LOG.warn("getSourceCounts()", e);
        } finally {
            helper.close();
        }

        return counts;
    }

    /** When the table was last built, or 0 when it never was. */
    public long getCompiledTime() {
        NumbersDb helper = new NumbersDb(context);

        try {
            String value = NumbersDb.getMeta(helper.getReadableDatabase(),
                    NumbersDb.META_COMPILED, null);

            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            LOG.warn("getCompiledTime()", e);
            return 0;
        } finally {
            helper.close();
        }
    }

    /** How much room the table takes, and the copy beside it. */
    public long getSize() {
        return NumbersDb.getFile(context).length();
    }

    public long getShadowSize() {
        return NumbersDb.getShadowFile(context).length();
    }

    /** Throws the table away; the sources are what it is built from, so nothing is lost. */
    public void clear() {
        dropShadowCopy();

        File file = NumbersDb.getFile(context);
        if (file.exists() && !file.delete()) LOG.warn("clear() couldn't delete {}", file);
    }

    /**
     * What a directory holds of one kind, in order, as names rather than as files.
     *
     * <p>Names, because a database can be a few hundred thousand files and a {@link File} for
     * each of them is tens of megabytes of paths held at once - on a phone that is the
     * difference between a build and no app. The file is made when it is read.
     */
    private static List<String> listNames(File dir, String prefix, String postfix) {
        String[] names = dir != null ? dir.list() : null;

        if (names == null) return new ArrayList<>();

        List<String> list = new ArrayList<>(names.length);

        for (String name : names) {
            if (name.startsWith(prefix) && name.endsWith(postfix)
                    && name.length() > prefix.length() + postfix.length()
                    && Character.isDigit(name.charAt(prefix.length()))) {
                list.add(name);
            }
        }

        Collections.sort(list);

        return list;
    }

    private static void report(ProgressListener listener, int current, int total) {
        if (listener != null) listener.onProgress(current, total);
    }

    private static boolean copy(File source, File target) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];

            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            return true;
        } catch (IOException e) {
            LOG.error("copy() couldn't copy {} to {}", source, target, e);
            return false;
        }
    }

}
