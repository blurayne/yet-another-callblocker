package dummydomain.yetanothercallblocker.data.numbers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a database slice, entry by entry.
 *
 * <p>The library can look a number up in a slice but not walk through one, and walking through
 * is what filling a table needs. The format is small enough to read here: a header, a version,
 * a count, that many entries of a number and five bytes, then the numbers the slice takes out
 * again, then an end mark.
 *
 * <p>Nothing is held: entries are handed to the visitor as they are read, so a slice of any
 * size costs one buffer.
 *
 * <p>Both names of every mark are accepted, because the same file has two. What SIA ships is
 * marked MTZF, a delta slice MTZD and the trailer MTZEND; a file that has been through a
 * rewrite carries YABF and YABEND instead. The bytes between them are the same either way -
 * a delta is a slice whose deletions happen to be the interesting part - so a slice is read
 * without caring which it is, and a file that was rewritten halfway is read too.
 */
public class SliceReader {

    /** Told about every entry of a slice, in the order they are stored (by number). */
    public interface Visitor {

        /**
         * @param category the SIA category id, 0 when the entry has none
         */
        void onNumber(long number, int positive, int negative, int neutral, int category);

        /** A number this slice takes out of what is underneath it. */
        void onDeleted(long number);

    }

    private static final int BUFFER_SIZE = 32 * 1024;

    private SliceReader() {
    }

    /**
     * Reads a whole slice.
     *
     * @return the version the slice says it has
     * @throws IOException when the stream isn't a slice, or ends in the middle of one
     */
    public static int read(InputStream inputStream, Visitor visitor) throws IOException {
        BufferedInputStream in = inputStream instanceof BufferedInputStream
                ? (BufferedInputStream) inputStream
                : new BufferedInputStream(inputStream, BUFFER_SIZE);

        String header = readChars(in, 4);
        if (!"YABF".equalsIgnoreCase(header) && !"MTZF".equalsIgnoreCase(header)
                && !"MTZD".equalsIgnoreCase(header)) {
            throw new IOException("Not a database slice: " + header);
        }

        readByte(in); // ignored, as the library ignores it

        int version = readInt(in);

        readChars(in, 2); // ignored
        readInt(in); // ignored

        int count = readInt(in);
        if (count < 0) throw new IOException("Negative number of items: " + count);

        for (int i = 0; i < count; i++) {
            long number = readLong(in);

            int positive = readByte(in) & 0xff;
            int negative = readByte(in) & 0xff;
            int neutral = readByte(in) & 0xff;
            readByte(in); // a byte whose meaning nobody knows
            int category = readByte(in) & 0xff;

            visitor.onNumber(number, positive, negative, neutral, category);
        }

        String divider = readChars(in, 2);
        if (!"CP".equalsIgnoreCase(divider)) {
            throw new IOException("Divider not found: " + divider);
        }

        int deletedCount = readInt(in);
        if (deletedCount < 0) throw new IOException("Negative number of deletions");

        for (int i = 0; i < deletedCount; i++) {
            visitor.onDeleted(readLong(in));
        }

        String endMark = readChars(in, 6);
        if (!"YABEND".equalsIgnoreCase(endMark) && !"MTZEND".equalsIgnoreCase(endMark)) {
            throw new IOException("End mark not found: " + endMark);
        }

        return version;
    }

    /** Characters are stored one byte each, not as UTF-8 of more than one. */
    private static String readChars(InputStream in, int count) throws IOException {
        StringBuilder sb = new StringBuilder(count);

        for (int i = 0; i < count; i++) {
            sb.append((char) (readByte(in) & 0xff));
        }

        return sb.toString();
    }

    private static int readInt(InputStream in) throws IOException {
        return (readByte(in) & 0xff)
                | ((readByte(in) & 0xff) << 8)
                | ((readByte(in) & 0xff) << 16)
                | ((readByte(in) & 0xff) << 24);
    }

    private static long readLong(InputStream in) throws IOException {
        long value = 0;

        for (int i = 0; i < 8; i++) {
            value |= ((long) (readByte(in) & 0xff)) << (8 * i);
        }

        return value;
    }

    private static byte readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("The slice ends in the middle of an entry");

        return (byte) value;
    }

}
