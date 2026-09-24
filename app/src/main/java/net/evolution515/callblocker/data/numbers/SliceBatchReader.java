package net.evolution515.callblocker.data.numbers;

import android.os.Process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads the database's own slice files on several threads at once.
 *
 * <p>A community database is a few hundred thousand small files, and once the filter is
 * throwing most of the numbers away before they are written, opening and reading those files
 * is what a build spends its time on - not the database. Opening files is something a phone
 * can do on several cores at once; writing to SQLite is not, because one database has one
 * writer whatever anyone does.
 *
 * <p>So the work is split along that line. Readers take files, parse them and hold what
 * survives the filter against a batch; the thread that owns the database takes batches off a
 * queue and writes them, one after another. Nobody waits for anybody except when the queue
 * is empty or full, which is exactly the point at which waiting is the right thing to do.
 *
 * <p>This is only done for the files that carry the database itself, where a number appears
 * in exactly one of them and the order they are read in makes no difference. The updates that
 * come after it, and the layers of the other sources, are changes to what is already there -
 * they have to be applied in their order, and they are, one file at a time.
 */
class SliceBatchReader {

    private static final Logger LOG = LoggerFactory.getLogger(SliceBatchReader.class);

    /** In the flags of an entry: this number is taken out rather than written. */
    static final int DELETED = -1;

    /** How many entries a reader collects before handing them over. */
    private static final int BATCH_SIZE = 2048;

    /**
     * And after how many files at the latest.
     *
     * <p>A filter that keeps one number in twenty-five would otherwise take thousands of
     * files to fill a batch, and nothing would be written - or said about how far along it
     * is - until it had.
     */
    private static final int FILES_PER_BATCH = 200;

    /** How long the writer waits for a batch before looking at whether anyone is still reading. */
    private static final long POLL_MS = 200;

    /** What a reader hands over: entries that survived the filter, in no particular order. */
    static final class Batch {

        final long[] numbers = new long[BATCH_SIZE];
        final int[] flags = new int[BATCH_SIZE];
        final int[] scores = new int[BATCH_SIZE];

        int size;

        /** How many files were finished while this batch was being filled. */
        int files;

        boolean add(long number, int flags, int score) {
            numbers[size] = number;
            this.flags[size] = flags;
            scores[size] = score;

            return ++size == BATCH_SIZE;
        }

    }

    /** Written on the thread that owns the database, in the order the batches arrive. */
    interface Sink {
        void write(Batch batch);
    }

    /** What the reading came to, for the counting the build does afterwards. */
    static final class Result {

        /** Everything the files held, before the filter had a say. */
        final long read;

        /** How many of those were numbers taken out rather than written. */
        final long deletions;

        /** And how many the filter kept out. */
        final long skipped;

        Result(long read, long deletions, long skipped) {
            this.read = read;
            this.deletions = deletions;
            this.skipped = skipped;
        }

    }

    /** How many readers are worth starting on this phone for this many files. */
    static int workerCount(int files) {
        if (files < 1000) return 1; // not enough to pay for the threads

        int cores = Runtime.getRuntime().availableProcessors();

        return Math.max(1, Math.min(3, cores - 1));
    }

    private SliceBatchReader() {
    }

    /**
     * Reads every file and writes what survives, on this thread.
     *
     * @param filters one filter per reader, because a filter is not shared between threads
     * @return what was read and what the filter kept out
     * @throws Exception whatever a reader ran into first
     */
    static Result read(File dir, List<String> names, NumbersFilter[] filters, Sink sink)
            throws Exception {
        int workers = filters.length;

        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(workers * 2);

        AtomicInteger next = new AtomicInteger();
        AtomicInteger running = new AtomicInteger(workers);

        /*
         * Set when the readers are to stop, which is also what keeps them from waiting for a
         * writer that is no longer taking anything: without it, a failure while writing would
         * leave them blocked on a full queue and this method waiting for them to finish.
         */
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicLong read = new AtomicLong();
        AtomicLong deletions = new AtomicLong();
        AtomicLong skipped = new AtomicLong();

        Throwable[] failure = new Throwable[1];

        Thread[] threads = new Thread[workers];

        for (int i = 0; i < workers; i++) {
            NumbersFilter filter = filters[i];

            threads[i] = new Thread(() -> {
                // the same standing as the thread that started them: out of the UI's way
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                try {
                    work(dir, names, filter, queue, next, read, deletions, skipped,
                            failure, stopped);
                } catch (Throwable t) {
                    synchronized (failure) {
                        if (failure[0] == null) failure[0] = t;
                    }
                } finally {
                    running.decrementAndGet();
                }
            }, "yacb-slice-reader");

            threads[i].start();
        }

        try {
            while (true) {
                Batch batch = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);

                if (batch != null) {
                    sink.write(batch);
                    continue;
                }

                // nothing waiting and nobody left to put anything there
                if (running.get() == 0 && queue.isEmpty()) break;
            }
        } finally {
            stopped.set(true);

            /*
             * Emptying the queue while waiting for them: a reader that is holding a full
             * batch is waiting for room, and joining it without making any would be waiting
             * for each other.
             */
            for (Thread thread : threads) {
                while (thread.isAlive()) {
                    queue.poll();
                    thread.join(POLL_MS);
                }
            }

            queue.clear();
        }

        synchronized (failure) {
            if (failure[0] != null) {
                if (failure[0] instanceof Exception) throw (Exception) failure[0];
                throw new RuntimeException(failure[0]);
            }
        }

        return new Result(read.get(), deletions.get(), skipped.get());
    }

    private static void work(File dir, List<String> names, NumbersFilter filter,
                             BlockingQueue<Batch> queue, AtomicInteger next,
                             AtomicLong read, AtomicLong deletions, AtomicLong skipped,
                             Throwable[] failure, AtomicBoolean stopped)
            throws InterruptedException {
        Batch[] batch = {new Batch()};

        long[] counts = {0, 0, 0}; // read, deletions, skipped

        while (!stopped.get()) {
            synchronized (failure) {
                if (failure[0] != null) return; // someone else has already failed
            }

            int index = next.getAndIncrement();
            if (index >= names.size()) break;

            File file = new File(dir, names.get(index));

            try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                SliceReader.read(inputStream, new SliceReader.Visitor() {
                    @Override
                    public void onNumber(long number, int positive, int negative,
                                         int neutral, int category) {
                        counts[0]++;

                        if (filter != null && !filter.keep(number)) {
                            counts[2]++;
                            return;
                        }

                        int rating = NumberFlags.ratingOf(positive, negative, neutral);

                        if (batch[0].add(number, NumberFlags.of(rating, category, 0),
                                NumberFlags.scoreOf(positive, negative))) {
                            hand(queue, batch, stopped);
                        }
                    }

                    @Override
                    public void onDeleted(long number) {
                        counts[0]++;
                        counts[1]++;

                        if (batch[0].add(number, DELETED, 0)) hand(queue, batch, stopped);
                    }
                });
            } catch (Exception e) {
                LOG.warn("work() couldn't read {}", file, e);
            }

            if (++batch[0].files >= FILES_PER_BATCH) hand(queue, batch, stopped);
        }

        if (!stopped.get() && (batch[0].size > 0 || batch[0].files > 0)) {
            hand(queue, batch, stopped);
        }

        read.addAndGet(counts[0]);
        deletions.addAndGet(counts[1]);
        skipped.addAndGet(counts[2]);
    }

    /**
     * Hands the batch over and starts another, waiting if the writer is behind.
     *
     * <p>Waiting in steps rather than for as long as it takes, so that a reader notices when
     * there is no longer anyone to hand anything to.
     */
    private static void hand(BlockingQueue<Batch> queue, Batch[] batch, AtomicBoolean stopped) {
        try {
            while (!stopped.get()) {
                if (queue.offer(batch[0], POLL_MS, TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        batch[0] = new Batch();
    }

}
