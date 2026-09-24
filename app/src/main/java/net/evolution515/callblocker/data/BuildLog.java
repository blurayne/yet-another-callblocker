package net.evolution515.callblocker.data;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What a build did, written down as it does it.
 *
 * <p>A build fetches several sources, reads a few hundred thousand files and ends minutes
 * later, mostly while nobody is watching. The notification says where it is; this says what
 * happened - per source, in order, with the numbers that came of it and the reasons for
 * whatever didn't work.
 *
 * <p>Every line is tagged with whose it is: the name of the source it is about, or "Main" for
 * the parts that belong to the build as a whole. So the log of one source is its lines, and
 * the log of the run is all of them.
 *
 * <p>It is a file rather than a memory buffer because the interesting runs are the ones that
 * ended badly, sometimes by the app disappearing - and what was written by then is still
 * there afterwards. It is kept to a size that a phone won't mind and a person can read.
 */
public class BuildLog {

    private static final Logger LOG = LoggerFactory.getLogger(BuildLog.class);

    private static final String FILE_NAME = "build-log.txt";

    /** What "the build itself" is called, as opposed to one of its sources. */
    public static final String MAIN = "Main";

    /** How much of it is kept; older runs fall off the front. */
    private static final long MAX_SIZE = 512 * 1024;

    /** How much is left when it is trimmed, so that trimming is rare. */
    private static final long KEEP_SIZE = 384 * 1024;

    private final Context context;

    public BuildLog(Context context) {
        this.context = context.getApplicationContext();
    }

    public static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** Starts a run: a blank line, and the time it started. */
    public void startRun(String title) {
        trim();

        append("\n─── " + title + " ─ "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date()) + "\n");
    }

    /**
     * One line about one source, or about the build itself.
     *
     * @param tag whose line it is; {@link #MAIN} for the build as a whole
     */
    public void line(String tag, String text) {
        append(time() + " [" + (tag != null ? tag : MAIN) + "] " + text + "\n");

        LOG.info("[{}] {}", tag, text); // and into the app's log, where a run is followed live
    }

    /** The same, with a number that reads better with separators. */
    public void line(String tag, String text, long count) {
        line(tag, text + " " + NumberFormat.getInstance().format(count));
    }

    public String read() {
        File file = getFile(context);
        if (!file.exists()) return null;

        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) Math.min(reader.length(), MAX_SIZE)];

            reader.seek(reader.length() - bytes.length);
            reader.readFully(bytes);

            return new String(bytes, Charset.forName("UTF-8"));
        } catch (Exception e) {
            LOG.warn("read()", e);
            return null;
        }
    }

    public void clear() {
        File file = getFile(context);

        if (file.exists() && !file.delete()) LOG.warn("clear() couldn't delete {}", file);
    }

    private void append(String text) {
        File file = getFile(context);

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, true), Charset.forName("UTF-8"))) {
            writer.write(text);
        } catch (IOException e) {
            LOG.warn("append()", e);
        }
    }

    /** Drops the oldest part when it has grown past what is worth keeping. */
    private void trim() {
        File file = getFile(context);
        if (!file.exists() || file.length() <= MAX_SIZE) return;

        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) KEEP_SIZE];

            reader.seek(reader.length() - bytes.length);
            reader.readFully(bytes);

            String kept = new String(bytes, Charset.forName("UTF-8"));

            // start at a line of its own rather than in the middle of one
            int newline = kept.indexOf('\n');
            if (newline >= 0) kept = kept.substring(newline + 1);

            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file, false), Charset.forName("UTF-8"))) {
                writer.write(kept);
            }
        } catch (Exception e) {
            LOG.warn("trim()", e);
        }
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

}
