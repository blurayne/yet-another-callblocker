package net.evolution515.callblocker.data.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveUtils.class);

    /** What a stream turned out to be packed in. */
    public enum Format {
        PLAIN, GZIP, TAR, TAR_GZIP, ZIP
    }

    /**
     * And what is inside it, which is a different question with a different answer.
     *
     * <p>How a source packs its data says nothing about what the data is: the same zip can
     * hold the community database's own slice files or one SQLite database, and the two are
     * read by entirely different machinery. A file server calls both of them
     * "application/octet-stream", so the content is what has to say.
     */
    public enum Content {
        /** The community database's own format: a directory of {@code data_slice_*.dat}. */
        SIA,
        /** One SQLite database, in the shape the update packages are built in. */
        SQLITE,
        /** One YABL file: a whole database, compressed in blocks. */
        YABL,
        /** Something that is neither, or too little of it to tell. */
        UNKNOWN
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

    /** A YABL file says so in its first four bytes, and usually in its name. */
    private static final byte[] YABL_MAGIC = {'Y', 'A', 'B', 'L'};
    private static final String YABL_SUFFIX = ".yabl";

    /** The first bytes of every SQLite file there has ever been. */
    private static final byte[] SQLITE_MAGIC = {
            'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0};

    /** What a SQLite database is called when it is packed with other things. */
    private static final String[] SQLITE_SUFFIXES = {".sqlite", ".sqlite3", ".db"};

    /** How many entries of an archive are looked at before giving up on naming it. */
    private static final int ENTRIES_TO_LOOK_AT = 32;

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

    /**
     * Unpacks everything an archive holds into a directory.
     *
     * <p>The community database is not one file but a few thousand, so a source that hands it
     * over packed hands over the whole set - and every one of them has to end up where the
     * app looks for them. That is what this does, and it is a different job from
     * {@link #open(InputStream)}, which finds the one file a layer consists of.
     *
     * <p>The folders inside the archive are dropped and every file lands directly in the
     * directory: the same database is packed flat by one command and under a folder of its
     * own by the next, the app has no use for the difference, and a name that walks out of
     * the directory with {@code ..} can't be written anywhere it shouldn't be.
     *
     * @param fallbackName what the file is called when the archive turns out to be one file
     * @return how many files were written
     */
    public static int unpackAll(InputStream inputStream, File targetDir, String fallbackName)
            throws IOException {
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("couldn't create " + targetDir);
        }

        BufferedInputStream in = new BufferedInputStream(inputStream, HEADER_SIZE * 2);

        switch (detect(in)) {
            case ZIP:
                return unpackZip(in, targetDir);

            case TAR:
                return unpackTar(in, targetDir);

            case TAR_GZIP:
                return unpackTar(new BufferedInputStream(
                        new GZIPInputStream(in), HEADER_SIZE * 2), targetDir);

            case GZIP:
                return write(new GZIPInputStream(in), new File(targetDir, fallbackName)) ? 1 : 0;

            default:
                return write(in, new File(targetDir, fallbackName)) ? 1 : 0;
        }
    }

    private static int unpackZip(InputStream in, File targetDir) throws IOException {
        int written = 0;

        ZipInputStream zip = new ZipInputStream(in);

        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;

            String name = baseName(entry.getName());
            if (name.isEmpty()) continue;

            if (write(zip, new File(targetDir, name))) written++;
        }

        return written;
    }

    private static int unpackTar(InputStream in, File targetDir) throws IOException {
        int written = 0;

        byte[] header = new byte[HEADER_SIZE];

        while (readFully(in, header)) {
            String entryName = readString(header, 0, 100);
            if (entryName.isEmpty()) break; // the two empty blocks that end a tar

            long size = readOctal(header, 124, 12);
            char type = (char) (header[156] == 0 ? '0' : header[156]);

            long padded = (size + HEADER_SIZE - 1) / HEADER_SIZE * HEADER_SIZE;

            String name = baseName(entryName);

            if ((type == '0' || type == '\0') && size > 0 && !name.isEmpty()) {
                if (write(new BoundedInputStream(in, size), new File(targetDir, name))) written++;

                skip(in, padded - size);
            } else {
                skip(in, padded);
            }
        }

        return written;
    }

    /** The name without the folders it was in, which is where the database wants it. */
    private static String baseName(String name) {
        String cleaned = name.replace('\\', '/');

        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);

        return cleaned.equals(".") || cleaned.equals("..") ? "" : cleaned;
    }

    /** Writes what the stream holds into a file; the stream itself is left open. */
    private static boolean write(InputStream in, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];

            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        return true;
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

    /**
     * What the stream holds, as opposed to how it is packed. The stream is left where it was.
     *
     * <p>The first bytes are enough for all of it, which is what makes this worth doing
     * before anything is downloaded: an archive names its entries at the front, a gzip
     * stream unpacks to its first block from its first block, and a SQLite file says so in
     * its first sixteen bytes.
     */
    public static Content inspect(BufferedInputStream in) throws IOException {
        Format format = detect(in);

        switch (format) {
            case ZIP:
                return namedInZip(in);

            case TAR:
                return namedInTar(in, false);

            case TAR_GZIP:
                return namedInTar(in, true);

            case GZIP:
                if (unpacksToYabl(in)) return Content.YABL;
                return unpacksToSqlite(in) ? Content.SQLITE : Content.SIA;

            default:
                byte[] start = peek(in, SQLITE_MAGIC.length);

                if (startsWith(start, YABL_MAGIC)) return Content.YABL;

                return startsWith(start, SQLITE_MAGIC) ? Content.SQLITE : Content.SIA;
        }
    }

    /** Told what each file inside an archive is called and what its first bytes are. */
    public interface EntryHeader {
        void onEntry(String name, String header);
    }

    /**
     * Walks an archive and says what each file in it is called and what it starts with.
     *
     * <p>For when something refuses a set of files and the only useful question is what is
     * actually in them. Nothing is unpacked and nothing is kept: each entry's first bytes
     * are read, the rest of it is skipped, and a stream that stops in the middle - a partial
     * download, on purpose - simply ends the walk.
     *
     * @param maxEntries how many to look at before giving up; an archive can hold thousands
     */
    public static void headers(InputStream inputStream, int maxEntries, EntryHeader visitor)
            throws IOException {
        BufferedInputStream in = new BufferedInputStream(inputStream, HEADER_SIZE * 2);

        switch (detect(in)) {
            case ZIP:
                headersOfZip(in, maxEntries, visitor);
                break;

            case TAR:
                headersOfTar(in, maxEntries, visitor);
                break;

            case TAR_GZIP:
                headersOfTar(new BufferedInputStream(
                        new GZIPInputStream(in), HEADER_SIZE * 2), maxEntries, visitor);
                break;

            case GZIP:
                visitor.onEntry("", headerOf(new GZIPInputStream(in)));
                break;

            default:
                visitor.onEntry("", headerOf(in));
                break;
        }
    }

    private static void headersOfZip(InputStream in, int maxEntries, EntryHeader visitor) {
        ZipInputStream zip = new ZipInputStream(in);

        try {
            for (int i = 0; i < maxEntries; i++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;

                if (entry.isDirectory()) continue;

                visitor.onEntry(baseName(entry.getName()), headerOf(zip));
            }
        } catch (IOException e) {
            // a stream that stops in the middle has said everything it is going to
            LOG.debug("headersOfZip() the archive ended", e);
        }
    }

    private static void headersOfTar(InputStream in, int maxEntries, EntryHeader visitor) {
        byte[] header = new byte[HEADER_SIZE];

        try {
            for (int i = 0; i < maxEntries; i++) {
                if (!readFully(in, header)) break;

                String entryName = readString(header, 0, 100);
                if (entryName.isEmpty()) break;

                long size = readOctal(header, 124, 12);
                char type = (char) (header[156] == 0 ? '0' : header[156]);

                long padded = (size + HEADER_SIZE - 1) / HEADER_SIZE * HEADER_SIZE;

                if ((type == '0' || type == '\0') && size > 0) {
                    BoundedInputStream entry = new BoundedInputStream(in, size);

                    visitor.onEntry(baseName(entryName), headerOf(entry));

                    skip(entry, size); // whatever of it the header didn't take
                    skip(in, padded - size);
                } else {
                    skip(in, padded);
                }
            }
        } catch (IOException e) {
            LOG.debug("headersOfTar() the archive ended", e);
        }
    }

    /**
     * The first bytes of a file, as the characters they stand for.
     *
     * <p>Every mark these files carry is letters, so the first byte that isn't one is where
     * the mark ends - which keeps a binary file from being read out as noise.
     */
    private static String headerOf(InputStream in) throws IOException {
        byte[] bytes = peekFully(in, 8);

        StringBuilder text = new StringBuilder(bytes.length);

        for (byte value : bytes) {
            char c = (char) (value & 0xff);

            if (c < 'A' || c > 'z') break;

            text.append(c);
        }

        return text.toString();
    }

    /** What an archive's entry names say it holds. */
    private static Content ofName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);

        if (lower.endsWith(DATABASE_SUFFIX)) return Content.SIA;
        if (lower.endsWith(YABL_SUFFIX)) return Content.YABL;

        for (String suffix : SQLITE_SUFFIXES) {
            if (lower.endsWith(suffix)) return Content.SQLITE;
        }

        return Content.UNKNOWN;
    }

    private static Content namedInZip(BufferedInputStream in) throws IOException {
        in.mark(HEADER_SIZE * 64);
        try {
            ZipInputStream zip = new ZipInputStream(in);

            for (int i = 0; i < ENTRIES_TO_LOOK_AT; i++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;

                if (entry.isDirectory()) continue;

                Content content = ofName(entry.getName());
                if (content != Content.UNKNOWN) return content;
            }
        } catch (IOException e) {
            LOG.debug("namedInZip() couldn't read the entries", e);
        } finally {
            in.reset();
        }

        return Content.UNKNOWN;
    }

    private static Content namedInTar(BufferedInputStream in, boolean gzipped)
            throws IOException {
        in.mark(HEADER_SIZE * 64);
        try {
            InputStream tar = gzipped ? new GZIPInputStream(in) : in;

            byte[] header = new byte[HEADER_SIZE];

            for (int i = 0; i < ENTRIES_TO_LOOK_AT; i++) {
                if (!readFully(tar, header)) break;

                String name = readString(header, 0, 100);
                if (name.isEmpty()) break;

                Content content = ofName(name);
                if (content != Content.UNKNOWN) return content;

                long size = readOctal(header, 124, 12);

                skip(tar, (size + HEADER_SIZE - 1) / HEADER_SIZE * HEADER_SIZE);
            }
        } catch (IOException e) {
            LOG.debug("namedInTar() couldn't read the entries", e);
        } finally {
            in.reset();
        }

        return Content.UNKNOWN;
    }

    /** Whether what the gzip stream unpacks to is a SQLite database. */
    private static boolean unpacksToYabl(BufferedInputStream in) throws IOException {
        in.mark(HEADER_SIZE * 8);
        try {
            return startsWith(peekFully(new GZIPInputStream(in), YABL_MAGIC.length), YABL_MAGIC);
        } catch (IOException e) {
            return false;
        } finally {
            in.reset();
        }
    }

    private static boolean unpacksToSqlite(BufferedInputStream in) throws IOException {
        in.mark(HEADER_SIZE * 8);
        try {
            byte[] unpacked = peekFully(new GZIPInputStream(in), SQLITE_MAGIC.length);

            return startsWith(unpacked, SQLITE_MAGIC);
        } catch (IOException e) {
            return false;
        } finally {
            in.reset();
        }
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
