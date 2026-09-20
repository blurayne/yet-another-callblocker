package dummydomain.yetanothercallblocker.data.source;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Getting the database out of whatever the server sent.
 *
 * <p>A source may hand out the plain file, or gzip it, or pack it into a tar.gz or a zip -
 * often without saying which, because a file server says "application/octet-stream" to
 * everything and a file name is not a promise. So the content decides: every one of these
 * formats says what it is in its first bytes, which is worth more than a name or a header.
 *
 * <p>Nothing here is Android-specific on purpose: what it does can be tried out anywhere.
 */
public class ArchiveUtils {

    /** What a stream turned out to be. */
    public enum Format {
        PLAIN, GZIP, TAR, TAR_GZIP, ZIP
    }

    /** How much has to be readable ahead to tell the formats apart. */
    private static final int HEADER_SIZE = 512;

    /** A tar header says so at this offset; everything before it is the first file's name. */
    private static final int TAR_MAGIC_OFFSET = 257;

    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final byte[] TAR_MAGIC = {'u', 's', 't', 'a', 'r'};

    /** The name the database is expected to have inside an archive. */
    private static final String DATABASE_SUFFIX = ".dat";

    private ArchiveUtils() {
    }

    /**
     * Opens the database inside a stream, whatever it is packed in.
     *
     * <p>Nothing is held in memory that doesn't have to be: the community database is tens of
     * megabytes, so the archive is read through rather than unpacked into a byte array. The
     * one exception is an archive whose files are named in a way that says nothing - then the
     * biggest file wins, and finding out which that is means keeping it.
     *
     * @return a stream over the database itself; closing it closes the one passed in
     */
    public static InputStream open(InputStream inputStream) throws IOException {
        BufferedInputStream in = new BufferedInputStream(inputStream, HEADER_SIZE * 2);

        switch (detect(in)) {
            case GZIP:
                return new GZIPInputStream(in);

            case TAR_GZIP:
                return openTar(new BufferedInputStream(new GZIPInputStream(in), HEADER_SIZE * 2));

            case TAR:
                return openTar(in);

            case ZIP:
                return openZip(in);

            default:
                return in;
        }
    }

    /** The database as bytes; for sources small enough that it doesn't matter. */
    public static byte[] unpack(InputStream inputStream) throws IOException {
        return readFully(open(inputStream));
    }

    /**
     * What the stream holds, by its first bytes. The stream is left where it was.
     *
     * <p>A gzip stream is looked into far enough to tell a gzipped database from a tar.gz,
     * because the two are told apart only by what is inside them.
     */
    public static Format detect(BufferedInputStream in) throws IOException {
        byte[] header = peek(in, HEADER_SIZE);

        if (startsWith(header, ZIP_MAGIC)) return Format.ZIP;
        if (isTar(header)) return Format.TAR;

        if (startsWith(header, GZIP_MAGIC)) {
            return isGzippedTar(in) ? Format.TAR_GZIP : Format.GZIP;
        }

        return Format.PLAIN;
    }

    /** Whether the gzip stream holds a tar, which only its content can say. */
    private static boolean isGzippedTar(BufferedInputStream in) throws IOException {
        in.mark(HEADER_SIZE * 8); // the compressed header is smaller than what it unpacks to
        try {
            byte[] unpacked = peekFully(new GZIPInputStream(in), HEADER_SIZE);
            return isTar(unpacked);
        } catch (IOException e) {
            return false; // not readable as gzip after all; let the caller find out
        } finally {
            in.reset();
        }
    }

    private static boolean isTar(byte[] header) {
        if (header.length < TAR_MAGIC_OFFSET + TAR_MAGIC.length) return false;

        for (int i = 0; i < TAR_MAGIC.length; i++) {
            if (header[TAR_MAGIC_OFFSET + i] != TAR_MAGIC[i]) return false;
        }

        return true;
    }

    /**
     * The database out of a tar: the headers say what is where, so the file that is wanted
     * can be handed over where it lies.
     */
    private static InputStream openTar(InputStream in) throws IOException {
        byte[] best = null;
        byte[] header = new byte[HEADER_SIZE];

        while (readFully(in, header)) {
            String name = readString(header, 0, 100);
            if (name.isEmpty()) break; // the two empty blocks that end a tar

            long size = readOctal(header, 124, 12);
            char type = (char) (header[156] == 0 ? '0' : header[156]);

            boolean isFile = type == '0' || type == '\0';
            long padded = (size + HEADER_SIZE - 1) / HEADER_SIZE * HEADER_SIZE;

            if (isFile && size > 0) {
                // the name says it is the database: hand it over without reading it first
                if (isDatabaseName(name)) return new BoundedInputStream(in, size);

                byte[] content = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
                if (!readFully(in, content)) break;

                skip(in, padded - size);

                if (best == null || content.length > best.length) best = content;
            } else {
                skip(in, padded);
            }
        }

        if (best == null) throw new IOException("the archive holds no file");

        return new ByteArrayInputStream(best);
    }

    private static InputStream openZip(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);

        byte[] best = null;

        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;

            // the stream stops at the end of the entry by itself, so it can be handed over
            if (isDatabaseName(entry.getName())) return zip;

            byte[] content = readEntry(zip);
            if (best == null || content.length > best.length) best = content;
        }

        if (best == null) throw new IOException("the archive holds no file");

        return new ByteArrayInputStream(best);
    }

    private static boolean isDatabaseName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(DATABASE_SUFFIX);
    }

    private static byte[] peek(BufferedInputStream in, int size) throws IOException {
        in.mark(size + 1);
        try {
            return peekFully(in, size);
        } finally {
            in.reset();
        }
    }

    /** Reads up to {@code size} bytes; a shorter stream gives whatever there was. */
    private static byte[] peekFully(InputStream in, int size) throws IOException {
        byte[] buffer = new byte[size];

        int read = 0;
        while (read < size) {
            int count = in.read(buffer, read, size - read);
            if (count == -1) break;
            read += count;
        }

        if (read == size) return buffer;

        byte[] shorter = new byte[read];
        System.arraycopy(buffer, 0, shorter, 0, read);
        return shorter;
    }

    private static boolean startsWith(byte[] header, byte[] magic) {
        if (header.length < magic.length) return false;

        for (int i = 0; i < magic.length; i++) {
            if (header[i] != magic[i]) return false;
        }

        return true;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    private static byte[] readEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int count = in.read(buffer, read, buffer.length - read);
            if (count == -1) return false;
            read += count;
        }

        return true;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);

            if (skipped <= 0) {
                if (in.read() == -1) return;
                skipped = 1;
            }

            left -= skipped;
        }
    }

    private static String readString(byte[] buffer, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < buffer.length && buffer[end] != 0) end++;

        return new String(buffer, offset, end - offset, java.nio.charset.Charset.forName("UTF-8"));
    }

    /** A stream over the part of another one that holds a single file. */
    private static class BoundedInputStream extends InputStream {

        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;

            int value = in.read();
            if (value != -1) left--;

            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) return -1;

            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read != -1) left -= read;

            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), left);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static long readOctal(byte[] buffer, int offset, int length) {
        String value = readString(buffer, offset, length).trim();
        if (value.isEmpty()) return 0;

        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
