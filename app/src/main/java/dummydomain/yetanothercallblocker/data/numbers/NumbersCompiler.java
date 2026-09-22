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

import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.data.BuildLog;
import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.source.NumberSource;

/**
 * Builds the one table out of everything the sources brought.
 *
 * <p>The order is the order of the list: the source that carries the database fills the empty
 * table, the library's own updates go on top of it, and every further source is a layer that
 * changes what it knows about and leaves the rest alone. A layer can also take a number out
 * again, which is a row that goes away rather than a row that says "no".
 *
 * <p>Nothing that doesn't belong is ever written: the filter is asked about each number as
 * it arrives, so the table holds what was wanted rather than being freed of the rest
 * afterwards.
 *
 * <p>A build works on a database of its own ({@link #forBuild}) and takes the place of the
 * one in use at the end, whole. The number is the key of the table, so however many sources
 * know a number, and however often a source repeats it, the table holds it once.
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
        /** Every source's counts added up, or null when the build didn't get that far. */
        public final Counts totals;

        Result(boolean ok, long numbers, int sources, Counts totals) {
            this(ok, numbers, sources, null, totals);
        }

        Result(boolean ok, long numbers, int sources, String error) {
            this(ok, numbers, sources, error, null);
        }

        Result(boolean ok, long numbers, int sources, String error, Counts totals) {
            this.ok = ok;
            this.numbers = numbers;
            this.sources = sources;
            this.error = error;
            this.totals = totals;
        }

    }

    /** Where a build has got to, in files. */
    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    /**
     * What one source did to the table.
     *
     * <p>Read is what it handed over; the rest is what became of it. A number two sources
     * know is inserted by the first and updated by the second, so the three of them add up to
     * what was read and not to the size of the table.
     */
    public static class Counts {

        public final long read;
        public final long inserted;
        public final long updated;
        public final long deleted;
        /** What the filter kept out, which is most of a community database. */
        public final long skipped;

        Counts(long read, long inserted, long updated, long deleted, long skipped) {
            this.read = read;
            this.inserted = inserted;
            this.updated = updated;
            this.deleted = deleted;
            this.skipped = skipped;
        }

        /** The two of them together, which is what a run adds up to over its sources. */
        Counts plus(Counts other) {
            if (other == null) return this;

            return new Counts(read + other.read, inserted + other.inserted,
                    updated + other.updated, deleted + other.deleted, skipped + other.skipped);
        }

    }

    /**
     * One source, and where what it brought is lying.
     *
     * <p>A build is a list of these, read in the order the user put their sources in. The
     * first one fills an empty table and every one after it changes what is already there -
     * that is all "first" means. No source is the database and the others additions to it.
     */
    public static class Input {

        public final NumberSource source;

        /** What it is called in the log. */
        public final String tag;

        /** Where its files are, or null when it isn't files at all. */
        public final File dir;

        /** What the library has fetched since, for the source whose files it keeps. */
        public final File updatesDir;

        /** The SQLite database it handed over, when that is what it hands over. */
        public final File database;

        public Input(NumberSource source, String tag, File dir, File updatesDir) {
            this(source, tag, dir, updatesDir, null);
        }

        public Input(NumberSource source, String tag, File dir, File updatesDir, File database) {
            this.source = source;
            this.tag = tag;
            this.dir = dir;
            this.updatesDir = updatesDir;
            this.database = database;
        }

    }

    /** Told what each source did, as it finishes. */
    public interface SourceListener {
        void onSourceFinished(NumberSource source, Counts counts);
    }

    /**
     * A source whose numbers don't come out of a slice file.
     *
     * <p>Most of them do: a source hands over a file in the format the community database is
     * written in, and it is read. A list held somewhere else in the app - the one fetched
     * from a PhoneBlock account, say - is just as much a source, and this is how it hands its
     * numbers over to the same machinery: the same filter, the same counting, the same table.
     */
    public interface ExtraSource {

        /** Whether this source's numbers come from here rather than from a file. */
        boolean canRead(NumberSource source);

        /** Hands every number over, one call each. */
        void read(NumberSource source, Entries entries);

    }

    /**
     * What a source that isn't a file hands over.
     *
     * <p>A source says what it knows and nothing more: a list of numbers to warn about knows
     * that they are worth warning about, and null for the rest means the row keeps whatever
     * an earlier source put there rather than having it overwritten with nothing.
     */
    public interface Entries {

        /**
         * @param rating what this source makes of the number, or null when it doesn't judge
         * @param category what it is used for, or null when this source doesn't say
         * @param score how strongly, or null when this source has no measure of it
         */
        void number(long number, Integer rating, Integer category, Integer score);

        /** A number this source takes out of what is underneath it. */
        void deleted(long number);

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

    /** The slices holding the business names, beside the ones holding the numbers. */
    private static final String FEATURED_PREFIX = "featured_slice_";
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

    /** And how often the build's own log gets a line about it. */
    private static final long LOG_INTERVAL_MS = 5_000;

    private final Context context;

    /** Which database this works on: the one in use, or the one being built. */
    private final String fileName;

    /** Which numbers are worth keeping, or null when all of them are. */
    private NumbersFilter filter;

    /** Reads and writes the database the app looks numbers up in. */
    public NumbersCompiler(Context context) {
        this(context, NumbersDb.FILE_NAME);
    }

    private NumbersCompiler(Context context, String fileName) {
        this.context = context.getApplicationContext();
        this.fileName = fileName;
    }

    /**
     * Works on the database a build assembles, beside the one in use.
     *
     * <p>Everything a build does happens here - filling the table, filtering it - and only
     * when all of it has worked does it take the place of the database the app reads. Until
     * then nothing the app does waits for the build, and a build that dies leaves the
     * database that worked untouched.
     */
    public static NumbersCompiler forBuild(Context context) {
        return new NumbersCompiler(context, NumbersDb.BUILD_FILE_NAME);
    }

    /**
     * Sets which numbers are worth writing down at all.
     *
     * <p>Asked of every number as it arrives, so that the ones that don't belong are never
     * written rather than deleted again afterwards.
     */
    public void setFilter(NumbersFilter filter) {
        this.filter = filter;
    }

    /** The file this one works on. */
    public File getDbFile() {
        return NumbersDb.getFile(context, fileName);
    }

    private NumbersDb openDb() {
        return new NumbersDb(context, fileName);
    }

    /**
     * Empties the table and fills it again.
     *
     * @param inputs the sources that are switched on, in the order they are asked
     */
    public Result compile(List<Input> inputs, ProgressListener listener) {
        return compile(inputs, listener, null, null, null);
    }

    /**
     * Empties the table and fills it again, source by source, in the order they are given.
     *
     * @param sourceListener told what each source did as it finishes, may be null
     * @param log written to as the work happens, may be null
     * @param extra where a source that isn't files hands its numbers over, may be null
     */
    public Result compile(List<Input> inputs, ProgressListener listener,
                          SourceListener sourceListener, BuildLog log, ExtraSource extra) {
        LOG.info("compile() started with {} sources", inputs.size());

        long startTime = System.currentTimeMillis();

        NumbersDb helper = openDb();

        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            // the indexes come after the rows; keeping them up to date while writing is work
            helper.recreate(db, false);

            /*
             * What each of them holds, counted first so that the progress has something to
             * count towards. Listing a directory of a few hundred thousand files is a second
             * or two; the reading afterwards is minutes.
             */
            List<List<String>> files = new ArrayList<>(inputs.size());

            int total = 0;

            for (Input input : inputs) {
                List<String> names = input.dir != null && input.database == null
                        ? listSlices(input.dir) : null;

                if (names != null && input.updatesDir != null) {
                    names = new ArrayList<>(names);
                    names.addAll(listNames(input.updatesDir, "", SECONDARY_POSTFIX));
                }

                files.add(names);

                total += names != null ? Math.max(1, names.size()) : 1;
            }

            int written = 0;

            Counts totals = new Counts(0, 0, 0, 0, 0);

            Run run = null;

            db.beginTransaction();
            try (NumbersWriter writer = new NumbersWriter(db)) {
                run = new Run(db, writer, listener, total);

                long countBefore = 0;

                for (int i = 0; i < inputs.size(); i++) {
                    Input input = inputs.get(i);
                    NumberSource source = input.source;

                    int sourceId = writer.addSource(source.getId(), source.getName(),
                            source.getType().ordinal(), i);

                    List<String> names = files.get(i);

                    run.startSource(sourceId, input.tag, log,
                            names != null ? names.size() : 1);

                    /*
                     * The first source writes into an empty table and has nothing to merge
                     * with, so it goes in with one statement per row and no read at all.
                     * Every source after it changes what is there, which means reading the
                     * row first. That is the only difference being first makes.
                     */
                    boolean asLayer = i != 0;

                    if (input.database != null) {
                        // one file holding the whole of what this source knows
                        run.readSqlite(input, sourceId, asLayer);
                    } else if (names != null && !names.isEmpty()) {
                        run.readFiles(input, names, sourceId, asLayer);

                        /*
                         * And the names the same files come with. Into the table as well,
                         * because the table is the whole answer once it is built: nothing
                         * opens the library's own files for a lookup after that.
                         */
                        run.readFeatured(input, sourceId);
                    } else if (extra != null && extra.canRead(source)) {
                        // a source the app holds itself, handed over the same way
                        run.readFrom(source, sourceId, extra);
                    } else {
                        run.step();
                    }

                    /*
                     * What this source did, worked out from the size of the table: the rows
                     * it added are what the table grew by, plus whatever it took out again,
                     * and everything else it handed over landed on a row that was there.
                     * Counted inside the transaction, where its own writing is visible.
                     */
                    long countAfter = NumbersDb.getCount(db);
                    long deleted = run.sourceDeleted();
                    long inserted = Math.max(0, countAfter - countBefore + deleted);
                    long updated = Math.max(0, run.sourceNumbers() - inserted);

                    countBefore = countAfter;

                    Counts counts = new Counts(run.sourceRead(), inserted, updated, deleted,
                            run.sourceSkipped());

                    if (log != null) {
                        log.line(input.tag, context.getString(R.string.build_log_read), counts.read);
                        log.line(input.tag, context.getString(R.string.build_log_inserted),
                                counts.inserted);
                        log.line(input.tag, context.getString(R.string.build_log_updated),
                                counts.updated);
                        log.line(input.tag, context.getString(R.string.build_log_deleted),
                                counts.deleted);

                        if (counts.skipped > 0) {
                            log.line(input.tag, context.getString(R.string.build_log_filtered),
                                    counts.skipped);
                        }

                        // only a source that brings names has a line about them
                        if (run.sourceNames() > 0) {
                            log.line(input.tag, context.getString(R.string.build_log_names),
                                    run.sourceNames());
                        }
                    }

                    if (sourceListener != null) sourceListener.onSourceFinished(source, counts);

                    totals = totals.plus(counts);

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
            NumbersDb.setMeta(db, NumbersDb.META_NAMES, String.valueOf(NumbersDb.getNamesCount(db)));

            /*
             * Now that nothing more is going in: one pass over what is there, rather than a
             * tree kept in order through nine million insertions that arrive in someone
             * else's order. Counting per source below is what uses it.
             */
            if (log != null) log.line(BuildLog.MAIN, context.getString(R.string.build_log_indexing));

            NumbersDb.createIndexes(db);

            countSources(db);

            /*
             * Both numbers, because the difference between them is the point: what the
             * sources handed over, and what is left once the same number from two of them is
             * one row.
             */
            LOG.info("compile() {} numbers out of {} entries, from {} sources in {} ms",
                    numbers, run != null ? run.entries : 0, written,
                    System.currentTimeMillis() - startTime);

            return new Result(true, numbers, written, totals);
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
        } catch (DbCompileService.Cancelled e) {
            throw e; // whoever asked for the build to stop is told by whoever started it
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

        /** Everything the sources handed over, before the table put the same number together. */
        private long entries;

        /** The same, for the source being read right now, and what it came to. */
        private long sourceNumbers;
        private long sourceDeletions;
        private long sourceDeletedRows;
        private long sourceSkipped;

        /** Business names the source being read brought along, which are not numbers. */
        private long sourceNames;

        /** What this source is called in the log, and where that log is. */
        private String tag;
        private BuildLog log;

        /** Which source's rows are being written; the same for a whole source. */
        private int sourceId;

        /** How far this source has got, and how far it has to go. */
        private int sourceDone;
        private int sourceTotal;

        /** When the log was last told how far this source has got. */
        private long lastLogged;

        Run(SQLiteDatabase db, NumbersWriter writer, ProgressListener listener, int total) {
            this.db = db;
            this.writer = writer;
            this.listener = listener;
            this.total = total;
        }

        /** Starts counting again; what came before belonged to the source before. */
        void startSource(int sourceId, String tag, BuildLog log, int sourceTotal) {
            this.sourceId = sourceId;
            this.tag = tag;
            this.log = log;
            this.sourceTotal = sourceTotal;

            sourceDone = 0;
            sourceNumbers = 0;
            sourceDeletions = 0;
            sourceSkipped = 0;
            sourceNames = 0;
            sourceDeletedRows = writer.getDeletedRows();
            lastLogged = 0;
        }

        long sourceNames() {
            return sourceNames;
        }

        long sourceNumbers() {
            return sourceNumbers - sourceSkipped;
        }

        long sourceSkipped() {
            return sourceSkipped;
        }

        long sourceRead() {
            return sourceNumbers + sourceDeletions;
        }

        long sourceDeleted() {
            return writer.getDeletedRows() - sourceDeletedRows;
        }

        /**
         * Reads one source's files into the table.
         *
         * <p>The first source is read on several threads where there are enough files to pay
         * for it: it writes into an empty table, so nothing it writes depends on anything
         * else it writes, and the opening and parsing of a few hundred thousand files is what
         * a build spends its time on. The sources after it change what is already there, so
         * they are read one file at a time, in their order.
         */
        void readFiles(Input input, List<String> names, int sourceId, boolean asLayer) {
            if (!asLayer && SliceBatchReader.workerCount(names.size()) > 1) {
                readInParallel(input, names, sourceId);
                return;
            }

            // said once, so that a source that takes a while says what it is working through
            if (log != null && names.size() > 1) {
                log.line(tag, context.getString(R.string.build_log_ingesting_files,
                        names.size()));
            }

            for (String name : names) {
                File file = new File(name.endsWith(SECONDARY_POSTFIX) && input.updatesDir != null
                        ? input.updatesDir : input.dir, name);

                read(file, sourceId, asLayer);
            }
        }

        private void readInParallel(Input input, List<String> names, int sourceId) {
            List<String> slices = new ArrayList<>(names.size());
            List<String> updates = new ArrayList<>();

            for (String name : names) {
                if (name.endsWith(SECONDARY_POSTFIX) && input.updatesDir != null) {
                    updates.add(name);
                } else {
                    slices.add(name);
                }
            }

            readBase(input.dir, slices, sourceId);

            // what the library has fetched since changes what those files said, so it follows
            for (String name : updates) {
                read(new File(input.updatesDir, name), sourceId, false);
            }
        }

        private void readBase(File dir, List<String> names, int sourceId) {
            int workers = SliceBatchReader.workerCount(names.size());

            if (workers <= 1) {
                readAll(dir, names, sourceId, false);
                return;
            }

            NumbersFilter[] filters = new NumbersFilter[workers];
            for (int i = 0; i < workers; i++) {
                filters[i] = filter != null ? filter.forWorker() : null;
            }

            if (log != null) {
                log.line(tag, context.getString(R.string.build_log_ingesting_threads,
                        names.size(), workers));
            }

            SliceBatchReader.Result result;
            try {
                result = SliceBatchReader.read(dir, names, filters, this::write);
            } catch (DbCompileService.Cancelled e) {
                throw e; // the readers were joined on the way out; nothing is left running
            } catch (Exception e) {
                LOG.error("readBase() reading the database failed", e);

                throw new RuntimeException(e);
            }

            sourceNumbers += result.read - result.deletions;
            sourceDeletions += result.deletions;
            sourceSkipped += result.skipped;

            entries += result.read;
        }

        /** One batch, written where everything else is written. */
        private void write(SliceBatchReader.Batch batch) {
            for (int i = 0; i < batch.size; i++) {
                if (batch.flags[i] == SliceBatchReader.DELETED) {
                    writer.delete(batch.numbers[i]);
                } else {
                    writer.put(batch.numbers[i], batch.flags[i], batch.scores[i], sourceId);
                }
            }

            pending += batch.size;
            files += batch.files;

            commitIfDue();

            for (int i = 0; i < batch.files; i++) {
                step();
            }
        }

        /**
         * Reads a source that handed over one SQLite database.
         *
         * <p>A file rather than a directory, and a format that is not the community
         * database's - what it has in common with the rest is that it ends up as rows in
         * the same table, in the place in the order its source has.
         */
        void readSqlite(Input input, int sourceId, boolean asLayer) {
            if (log != null) {
                log.line(tag, context.getString(R.string.build_log_reading_database));
            }

            SqliteImporter.Result result = SqliteImporter.read(input.database, db, writer,
                    sourceId, asLayer, filter,
                    read -> {
                        // a file of millions of rows is read in one go; this is where it looks
                        DbCompileService.checkCancelled();

                        if (log == null) return;

                        long now = System.currentTimeMillis();
                        if (now - lastLogged < LOG_INTERVAL_MS) return;

                        lastLogged = now;

                        log.line(tag, context.getString(R.string.build_log_ingesting_rows), read);
                    });

            /*
             * What the file said it was, kept with the source: for a database that is
             * published in numbered versions it is the only way to see, later, which one is
             * on the phone.
             */
            if (result.version > 0) input.source.setVersion(result.version);

            sourceNumbers += result.read - result.deletions;
            sourceDeletions += result.deletions;
            sourceSkipped += result.skipped;
            sourceNames += result.names;

            pending += result.read + result.names;
            entries += result.read;
            files++;

            commitIfDue();

            step();
        }

        /** Reads a source the app holds itself, as a layer over what is already there. */
        void readFrom(NumberSource source, int sourceId, ExtraSource extra) {
            long[] count = {0, 0, 0};

            extra.read(source, new Entries() {
                @Override
                public void number(long number, Integer rating, Integer category,
                                   Integer score) {
                    count[0]++;

                    if (filter != null && !filter.keep(number)) {
                        count[2]++;
                        return;
                    }

                    writer.merge(number, rating, category, score, false, sourceId);
                }

                @Override
                public void deleted(long number) {
                    count[1]++;

                    writer.delete(number);
                }
            });

            sourceNumbers += count[0];
            sourceDeletions += count[1];
            sourceSkipped += count[2];

            pending += count[0] + count[1];
            entries += count[0] + count[1];
            files++;

            commitIfDue();

            step();
        }

        /** The business names a slice source brought, out of its featured slices. */
        void readFeatured(Input input, int sourceId) {
            List<String> featured = listNames(input.dir, FEATURED_PREFIX, SLICE_POSTFIX);
            if (featured.isEmpty()) return;

            for (String name : featured) {
                File file = new File(input.dir, name);

                try (InputStream inputStream
                             = new BufferedInputStream(new FileInputStream(file))) {
                    SliceReader.readFeatured(inputStream, (number, businessName) -> {
                        // a name for a number the filter keeps out is one nothing asks for
                        if (filter != null && !filter.keep(number)) return;

                        writer.putName(number, businessName, sourceId);
                        sourceNames++;
                        pending++;
                    });
                } catch (Exception e) {
                    LOG.warn("readFeatured() couldn't read {}", file, e);
                }

                commitIfDue();
            }
        }

        void readAll(File dir, List<String> names, int sourceId, boolean asLayer) {
            for (String name : names) {
                read(new File(dir, name), sourceId, asLayer);
            }
        }

        void read(File file, int sourceId, boolean asLayer) {
            long[] read = NumbersCompiler.this.read(file, writer, sourceId, asLayer);

            sourceNumbers += read[0];
            sourceDeletions += read[1];
            sourceSkipped += read[2];

            long total = read[0] + read[1];

            pending += total;
            entries += total;
            files++;

            commitIfDue();

            step();
        }

        /** Says how far along it is; one file further. */
        void step() {
            report(listener, ++done, total);

            sourceDone++;

            /*
             * The log gets a line now and then rather than one per file: a few hundred
             * thousand of them would say nothing that the first and the last don't. Counted
             * within the source, because that is what the line is about - and not at all for
             * a source that is one thing, where "1 of 1" says nothing twice.
             */
            long now = System.currentTimeMillis();

            if (log != null && sourceTotal > 1 && now - lastLogged >= LOG_INTERVAL_MS) {
                lastLogged = now;

                log.line(tag, context.getString(R.string.build_log_ingesting,
                        sourceDone, sourceTotal));
            }

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
            DbCompileService.checkCancelled();

            if (pending < COMMIT_EVERY && files < COMMIT_EVERY_FILES) return;

            db.setTransactionSuccessful();
            db.endTransaction();
            db.beginTransaction();

            pending = 0;
            files = 0;
        }

    }

    /**
     * Writes the library's own update into the table that is already in use.
     *
     * <p>Between builds the library fetches what has changed since the database it holds was
     * made, and those files are read at the next build - which may be days away. Until then
     * the table would answer out of what the update supersedes, and it is the table that is
     * asked. So the update goes in now: a handful of files merged over what is there, not a
     * database read again.
     *
     * <p>As a layer, and written down as the source whose files it belongs to: the library
     * fetches these for the source it keeps, so they change what that source said and take
     * the same place in the order it has.
     *
     * @return how many entries went in, or -1 when there was nothing to do
     */
    public long mergeUpdates(File dir, String sourceUuid) {
        List<String> names = listNames(dir, "", SECONDARY_POSTFIX);
        if (names.isEmpty()) return -1;

        LOG.info("mergeUpdates() {} files from {}", names.size(), dir);

        NumbersDb helper = openDb();

        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            long entries = 0;

            db.beginTransaction();
            try (NumbersWriter writer = new NumbersWriter(db)) {
                int sourceId = writer.findSource(sourceUuid);

                /*
                 * No row for it means no table built from it, and an update on its own is
                 * not a database - it says what changed, not what there is.
                 */
                if (sourceId < 0) {
                    LOG.info("mergeUpdates() the table holds nothing from {}", sourceUuid);
                    return -1;
                }

                for (String name : names) {
                    long[] read = read(new File(dir, name), writer, sourceId, true);

                    entries += read[0] + read[1];
                }

                db.setTransactionSuccessful();
            } finally {
                if (db.inTransaction()) db.endTransaction();
            }

            // the size is written down rather than counted, so it has to be written again
            NumbersDb.setMeta(db, NumbersDb.META_COUNT,
                    String.valueOf(NumbersDb.getCount(db)));

            countSources(db);

            LOG.info("mergeUpdates() {} entries went in", entries);

            return entries;
        } catch (Exception e) {
            LOG.error("mergeUpdates() failed", e);
            return -1;
        } finally {
            helper.close();
        }
    }

    /**
     * Reads one slice file into the table.
     *
     * @return {@code {numbers, numbers it takes out again, numbers the filter kept out}}
     */
    private long[] read(File file, NumbersWriter writer, int sourceId, boolean asLayer) {
        long[] count = {0, 0, 0};

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            SliceReader.read(inputStream, new SliceReader.Visitor() {
                @Override
                public void onNumber(long number, int positive, int negative,
                                     int neutral, int category) {
                    count[0]++;

                    // the ones that don't belong here are never written, not written and deleted
                    if (filter != null && !filter.keep(number)) {
                        count[2]++;
                        return;
                    }

                    int rating = NumberFlags.ratingOf(positive, negative, neutral);
                    int score = NumberFlags.scoreOf(positive, negative);

                    if (asLayer) {
                        writer.merge(number, rating, category, score, false, sourceId);
                    } else {
                        writer.put(number, NumberFlags.of(rating, category, 0), score, sourceId);
                    }
                }

                @Override
                public void onDeleted(long number) {
                    writer.delete(number);

                    count[1]++;
                }
            });
        } catch (Exception e) {
            LOG.warn("read() couldn't read {}", file, e);
        }

        return count;
    }

    /**
     * Puts what was built in the place of the database the app reads.
     *
     * <p>A move rather than a copy: the file is complete by now, and what it replaces is of
     * no further use. The moment it lands, every lookup is answered out of the new one -
     * there is no point at which the app reads a half-built database.
     */
    public boolean promote() {
        File built = NumbersDb.getBuildFile(context);
        if (!built.exists()) return false;

        File live = NumbersDb.getFile(context);

        deleteDb(live);

        if (built.renameTo(live)) {
            dropJournals(built);
            dropShadowCopy();
            return true;
        }

        // across a boundary a rename can't cross, which shouldn't happen but can be worked
        LOG.warn("promote() couldn't move the built database into place, copying it");

        if (!copy(built, live)) return false;

        deleteDb(built);
        dropShadowCopy();

        return true;
    }

    /** Throws away a build, finished or not. */
    public void dropBuild() {
        deleteDb(NumbersDb.getBuildFile(context));
    }

    /**
     * Removes the copy that older versions kept beside the database.
     *
     * <p>There used to be one: the table was built whole and filtered afterwards, and the
     * copy was what the filtering could be taken back to. Now nothing that doesn't belong is
     * ever written, so there is nothing to go back to and no reason to keep a second copy of
     * a few hundred megabytes.
     */
    public void dropShadowCopy() {
        File file = NumbersDb.getShadowFile(context);

        if (file.exists() && !file.delete()) LOG.warn("dropShadowCopy() couldn't delete {}", file);
    }

    private static void deleteDb(File file) {
        if (file.exists() && !file.delete()) LOG.warn("deleteDb() couldn't delete {}", file);

        dropJournals(file);
    }

    /** What SQLite leaves beside a database; stale ones describe a database that is gone. */
    private static void dropJournals(File file) {
        for (String postfix : new String[]{"-journal", "-wal", "-shm"}) {
            File journal = new File(file.getPath() + postfix);

            if (journal.exists() && !journal.delete()) {
                LOG.warn("dropJournals() couldn't delete {}", journal);
            }
        }
    }

    /** Everything a screen says about the table, read in one go. */
    public static class Info {

        public final long count;
        /** Business names beside the numbers, from the sources that bring any. */
        public final long names;
        public final long compiledTime;
        public final boolean filtered;
        public final long size;
        /** Whether the table could be read at all; it can't while it is being written. */
        public final boolean readable;

        Info(long count, long names, long compiledTime, boolean filtered, long size) {
            this(count, names, compiledTime, filtered, size, true);
        }

        Info(long count, long names, long compiledTime, boolean filtered, long size,
             boolean readable) {
            this.count = count;
            this.names = names;
            this.compiledTime = compiledTime;
            this.filtered = filtered;
            this.size = size;
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
        NumbersDb helper = openDb();

        try {
            SQLiteDatabase db = helper.getReadableDatabase();

            String count = NumbersDb.getMeta(db, NumbersDb.META_COUNT, null);

            String names = NumbersDb.getMeta(db, NumbersDb.META_NAMES, null);

            String compiled = NumbersDb.getMeta(db, NumbersDb.META_COMPILED, null);

            boolean filtered = "1".equals(NumbersDb.getMeta(db, NumbersDb.META_FILTERED, "0"));

            return new Info(count != null ? Long.parseLong(count) : -1,
                    names != null ? Long.parseLong(names) : 0,
                    compiled != null ? Long.parseLong(compiled) : 0,
                    filtered, getSize());
        } catch (Exception e) {
            /*
             * Most likely because a build has the table open and hasn't reached a point where
             * it can answer. "Couldn't ask" and "there is nothing in it" are different things
             * and the screen says which.
             */
            LOG.warn("getInfo()", e);

            return new Info(-1, 0, 0, false, 0, false);
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
        NumbersDb helper = openDb();

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
        NumbersDb helper = openDb();

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

        NumbersDb helper = openDb();

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
        NumbersDb helper = openDb();

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

    /** How much room the table takes. */
    public long getSize() {
        return getDbFile().length();
    }

    /** Throws the table away; the sources are what it is built from, so nothing is lost. */
    public void clear() {
        dropShadowCopy();
        dropBuild();

        deleteDb(getDbFile());
    }

    /**
     * What a directory holds of one kind, in order, as names rather than as files.
     *
     * <p>Names, because a database can be a few hundred thousand files and a {@link File} for
     * each of them is tens of megabytes of paths held at once - on a phone that is the
     * difference between a build and no app. The file is made when it is read.
     */
    /**
     * The slice files in a source's directory.
     *
     * <p>The ones named the way the community database names them, when there are any - the
     * directory the database is unpacked into holds other things as well - and otherwise
     * whatever is there, because a source that hands over its own archive names its files
     * whatever it likes.
     */
    private static List<String> listSlices(File dir) {
        List<String> named = listNames(dir, SLICE_PREFIX, SLICE_POSTFIX);
        if (!named.isEmpty()) return named;

        String[] names = dir != null ? dir.list() : null;
        if (names == null) return new ArrayList<>();

        List<String> list = new ArrayList<>(names.length);

        for (String name : names) {
            if (!new File(dir, name).isDirectory()) list.add(name);
        }

        Collections.sort(list);

        return list;
    }

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
        // once per file or per batch of rows: often enough to stop within a moment
        DbCompileService.checkCancelled();

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
