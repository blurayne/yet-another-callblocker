package net.evolution515.callblocker.data.numbers;

import org.tukaani.xz.BasicArrayCache;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads a YABL file: one file holding a whole database, compressed in blocks.
 *
 * <p>The format is specified in {@code YABL-FORMAT.md} of the callblocker-sia-data repository,
 * and its reference implementation is that repository's {@code bin/_yabl.py}. In short: a
 * plain 38-byte header, then independently compressed chunks - a prelude with the meta
 * values, the categories and the deletions, the featured (business) names, the numbers in
 * blocks of up to 65,536 rows, and a directory of all of them at the end. Every chunk is
 * checked against the CRC-32 the directory gives for its uncompressed bytes.
 *
 * <p>A build reads the whole file into the table, one block at a time: at most one
 * decompressed block is held at once, plus the 8 MiB dictionary the XZ decoder needs for
 * any chunk, however small. {@link #lookup(long)} answers one number from one block, the
 * way the format was made for.
 *
 * <p>Plain Java on purpose, with nothing of Android in it, so that it can be checked against
 * files the reference implementation wrote without a phone.
 */
public final class YablReader implements Closeable {

    public static final int HEADER_SIZE = 38;

    private static final byte[] MAGIC = {'Y', 'A', 'B', 'L'};

    public static final int FORMAT_VERSION = 2;

    public static final int CODEC_STORE = 0;
    public static final int CODEC_LZMA = 1;

    private static final int TAG_CATEGORY_MASK = 0x1F;
    private static final int TAG_ESCAPE = 0x1F;
    private static final int TAG_POS = 0x20;
    private static final int TAG_NEG = 0x40;
    private static final int TAG_NEU = 0x80;

    /** More than any real chunk; a directory that says otherwise is not believed. */
    private static final long MAX_CHUNK = 256L * 1024 * 1024;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** A file that doesn't follow the format, with what was wrong with it. */
    public static class FormatException extends IOException {
        public FormatException(String message) {
            super(message);
        }
    }

    /** The 38 bytes at the front, which say what the file is without decompressing anything. */
    public static class Header {
        public int formatVersion;
        public int codec;
        public long dbVersion;
        public long numNumbers;
        public long numFeatured;
        public long blockRows;
        public long numBlocks;
        public long directoryOffset;
        public long directoryCrc32;
    }

    /** Where a chunk is, and what it has to come out as. */
    public static class Chunk {
        public long offset;
        public long compressedLen;
        public long uncompressedLen;
        public long crc32;
    }

    /** A block of numbers: a chunk, and the first number and row count it holds. */
    public static class Block extends Chunk {
        public long firstNumber;
        public int rowCount;
    }

    /** Told about everything in the file, section by section. */
    public interface Visitor {

        void onMeta(String key, String value);

        void onCategory(int id, String name);

        /** A number to take out of whatever is underneath. */
        void onDeleted(long number);

        /** Before each block of numbers, which is a place to say how far along it is. */
        void onBlock(int index, int total, long rowsSoFar);

        void onNumber(long number, int positive, int negative, int neutral, int unknown,
                      int category);

        void onName(long number, String name);

    }

    /** A visitor that does nothing, for the parts someone isn't interested in. */
    public static class SimpleVisitor implements Visitor {
        @Override public void onMeta(String key, String value) {}
        @Override public void onCategory(int id, String name) {}
        @Override public void onDeleted(long number) {}
        @Override public void onBlock(int index, int total, long rowsSoFar) {}
        @Override public void onNumber(long number, int positive, int negative, int neutral,
                                       int unknown, int category) {}
        @Override public void onName(long number, String name) {}
    }

    private final RandomAccessFile file;
    private final Header header;

    private final Chunk prelude = new Chunk();
    private final Chunk featured = new Chunk();
    private final List<Block> blocks = new ArrayList<>();

    /** Opens the file and reads its header and directory; nothing else is read yet. */
    public YablReader(File path) throws IOException {
        file = new RandomAccessFile(path, "r");

        try {
            byte[] bytes = new byte[HEADER_SIZE];
            if (file.length() < HEADER_SIZE) {
                throw new FormatException("the file is " + file.length()
                        + " bytes, shorter than a " + HEADER_SIZE + "-byte header");
            }
            file.readFully(bytes);

            header = parseHeader(bytes);

            readDirectory();
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    public Header getHeader() {
        return header;
    }

    public List<Block> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** Whether the file starts like one, by its first four bytes rather than its name. */
    public static boolean isYabl(File path) {
        if (path == null || !path.isFile() || path.length() < HEADER_SIZE) return false;

        byte[] start = new byte[MAGIC.length];

        try (InputStream in = new FileInputStream(path)) {
            int read = 0;
            while (read < start.length) {
                int count = in.read(start, read, start.length - read);
                if (count < 0) return false;
                read += count;
            }
        } catch (IOException e) {
            return false;
        }

        return startsWithMagic(start);
    }

    /** Whether the bytes start with the magic; for a header peeked off a stream. */
    public static boolean startsWithMagic(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length) return false;

        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) return false;
        }

        return true;
    }

    /** The one YABL file in a directory, or null when there is none. */
    public static File find(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;

        for (File each : files) {
            if (isYabl(each)) return each;
        }

        return null;
    }

    /** Only the header, for saying what a file is before reading it. */
    public static Header readHeader(File path) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(path, "r")) {
            if (in.length() < HEADER_SIZE) {
                throw new FormatException("shorter than a YABL header");
            }

            byte[] bytes = new byte[HEADER_SIZE];
            in.readFully(bytes);

            return parseHeader(bytes);
        }
    }

    private static Header parseHeader(byte[] b) throws FormatException {
        if (!startsWithMagic(b)) {
            throw new FormatException("not a YABL file: it starts with \""
                    + new String(b, 0, 4, Charset.forName("ISO-8859-1")) + "\"");
        }

        Header h = new Header();
        h.formatVersion = b[4] & 0xff;
        h.codec = b[5] & 0xff;

        if (h.formatVersion != FORMAT_VERSION) {
            throw new FormatException("YABL format version " + h.formatVersion
                    + ", this app reads version " + FORMAT_VERSION);
        }

        if (h.codec != CODEC_STORE && h.codec != CODEC_LZMA) {
            throw new FormatException("unknown YABL codec " + h.codec);
        }

        h.dbVersion = u32(b, 6);
        h.numNumbers = u32(b, 10);
        h.numFeatured = u32(b, 14);
        h.blockRows = u32(b, 18);
        h.numBlocks = u32(b, 22);
        h.directoryOffset = u32(b, 26) | (u32(b, 30) << 32);
        h.directoryCrc32 = u32(b, 34);

        return h;
    }

    private void readDirectory() throws IOException {
        long length = file.length();

        if (header.directoryOffset < HEADER_SIZE || header.directoryOffset >= length) {
            throw new FormatException("the directory offset " + header.directoryOffset
                    + " is outside the file of " + length + " bytes");
        }

        byte[] compressed = read(header.directoryOffset, length - header.directoryOffset);
        byte[] raw = decompress(compressed, -1);

        check(raw, header.directoryCrc32, "directory");

        Cursor c = new Cursor(raw, "directory");

        readChunkInfo(c, prelude);
        readChunkInfo(c, featured);

        long count = c.uvarint();
        if (count != header.numBlocks) {
            throw new FormatException("the directory lists " + count
                    + " blocks, the header says " + header.numBlocks);
        }

        long first = 0;
        long offset = featured.offset + featured.compressedLen;

        for (long i = 0; i < count; i++) {
            Block block = new Block();

            first += c.uvarint();
            block.firstNumber = first;
            block.offset = offset;
            block.compressedLen = c.uvarint();
            block.uncompressedLen = c.uvarint();
            block.rowCount = (int) c.uvarint();
            block.crc32 = c.uvarint();

            offset += block.compressedLen;

            blocks.add(block);
        }

        if (offset > header.directoryOffset) {
            throw new FormatException("the blocks run past the start of the directory");
        }
    }

    private static void readChunkInfo(Cursor c, Chunk chunk) throws FormatException {
        chunk.offset = c.uvarint();
        chunk.compressedLen = c.uvarint();
        chunk.uncompressedLen = c.uvarint();
        chunk.crc32 = c.uvarint();
    }

    /** The meta values, the categories and the deletions, in that order. */
    public void readPrelude(Visitor visitor) throws IOException {
        Cursor c = new Cursor(readChunk(prelude, "prelude"), "prelude");

        for (long i = 0, n = c.uvarint(); i < n; i++) {
            visitor.onMeta(c.text(), c.text());
        }

        for (long i = 0, n = c.uvarint(); i < n; i++) {
            int id = (int) c.uvarint();
            visitor.onCategory(id, c.text());
        }

        long number = 0;
        for (long i = 0, n = c.uvarint(); i < n; i++) {
            number += c.uvarint();
            visitor.onDeleted(number);
        }
    }

    /** The business names, ascending by number. */
    public void readFeatured(Visitor visitor) throws IOException {
        if (header.numFeatured == 0 && featured.uncompressedLen == 0) return;

        Cursor c = new Cursor(readChunk(featured, "featured"), "featured");

        Cursor[] columns = c.columns(3);
        Cursor deltas = columns[0];
        Cursor lengths = columns[1];
        Cursor blob = columns[2];

        long number = 0;
        for (long i = 0; i < header.numFeatured; i++) {
            number += deltas.uvarint();

            int length = (int) lengths.uvarint();
            visitor.onName(number, blob.string(length));
        }
    }

    /** Every block of numbers, one at a time. */
    public void readBlocks(Visitor visitor) throws IOException {
        long rows = 0;

        for (int i = 0; i < blocks.size(); i++) {
            visitor.onBlock(i, blocks.size(), rows);

            Block block = blocks.get(i);
            decodeBlock(readChunk(block, "block " + i), block, visitor, -1);

            rows += block.rowCount;
        }
    }

    /** All of it: the prelude, the numbers, then the names. */
    public void readAll(Visitor visitor) throws IOException {
        readPrelude(visitor);
        readBlocks(visitor);
        readFeatured(visitor);
    }

    /**
     * One number, out of the one block that can hold it.
     *
     * @return {@code {number, positive, negative, neutral, unknown, category}}, or null
     */
    public long[] lookup(long number) throws IOException {
        int low = 0, high = blocks.size() - 1, found = -1;

        // the last block whose first number is not above the one asked for
        while (low <= high) {
            int mid = (low + high) >>> 1;

            if (blocks.get(mid).firstNumber <= number) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (found < 0) return null;

        Block block = blocks.get(found);
        long[][] row = {null};

        decodeBlock(readChunk(block, "block " + found), block, new SimpleVisitor() {
            @Override
            public void onNumber(long n, int positive, int negative, int neutral, int unknown,
                                 int category) {
                if (n == number) row[0] = new long[]{n, positive, negative, neutral, unknown,
                        category};
            }
        }, number);

        return row[0];
    }

    /**
     * The six columns of a block, walked row by row.
     *
     * @param stopAfter a number past which nothing more is wanted, or -1 for all of it
     */
    private static void decodeBlock(byte[] raw, Block block, Visitor visitor, long stopAfter)
            throws FormatException {
        Cursor c = new Cursor(raw, "block");
        Cursor[] columns = c.columns(6);

        Cursor deltas = columns[0];
        Cursor tags = columns[1];
        Cursor escapes = columns[2];
        Cursor pos = columns[3];
        Cursor neg = columns[4];
        Cursor neu = columns[5];

        if (tags.remaining() != block.rowCount) {
            throw new FormatException("a block says " + block.rowCount
                    + " rows but its tag column holds " + tags.remaining());
        }

        // the chain starts again at 0 in every block, which is what makes one readable alone
        long number = 0;

        for (int i = 0; i < block.rowCount; i++) {
            number += deltas.uvarint();

            int tag = tags.u8();
            int category = tag & TAG_CATEGORY_MASK;
            long unknown = 0;

            if (category == TAG_ESCAPE) {
                category = (int) escapes.uvarint();
                unknown = escapes.uvarint();
            }

            int positive = (tag & TAG_POS) != 0 ? (int) pos.uvarint() : 0;
            int negative = (tag & TAG_NEG) != 0 ? (int) neg.uvarint() : 0;
            int neutral = (tag & TAG_NEU) != 0 ? (int) neu.uvarint() : 0;

            if (stopAfter >= 0 && number > stopAfter) return;

            visitor.onNumber(number, positive, negative, neutral, (int) unknown, category);
        }
    }

    private byte[] readChunk(Chunk chunk, String label) throws IOException {
        if (chunk.compressedLen > MAX_CHUNK || chunk.uncompressedLen > MAX_CHUNK) {
            throw new FormatException(label + " claims to be larger than any chunk can be");
        }

        byte[] raw = decompress(read(chunk.offset, chunk.compressedLen),
                (int) chunk.uncompressedLen);

        if (raw.length != chunk.uncompressedLen) {
            throw new FormatException(label + " is " + raw.length
                    + " bytes, the directory says " + chunk.uncompressedLen);
        }

        check(raw, chunk.crc32, label);

        return raw;
    }

    private byte[] read(long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > file.length()) {
            throw new FormatException("a chunk at " + offset + " of " + length
                    + " bytes runs past the end of the file");
        }

        byte[] bytes = new byte[(int) length];

        file.seek(offset);
        file.readFully(bytes);

        return bytes;
    }

    /** @param expected how long it comes out as, or -1 when that isn't known */
    private byte[] decompress(byte[] compressed, int expected) throws IOException {
        if (header.codec == CODEC_STORE) return compressed;

        /*
         * The XZ container says which filters and dictionary it was made with, so nothing
         * about the preset has to be known here. The array cache hands the 8 MiB dictionary
         * from one block to the next rather than making a new one for each of 200 blocks.
         */
        try (InputStream in = new XZInputStream(new ByteArrayInputStream(compressed), -1,
                true, BasicArrayCache.getInstance())) {
            if (expected >= 0) {
                byte[] out = new byte[expected];

                int read = 0;
                while (read < expected) {
                    int count = in.read(out, read, expected - read);
                    if (count < 0) break;
                    read += count;
                }

                if (read < expected || in.read() != -1) {
                    throw new FormatException("a chunk doesn't decompress to the "
                            + expected + " bytes the directory says");
                }

                return out;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];

            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            return out.toByteArray();
        }
    }

    private static void check(byte[] raw, long expected, String label) throws FormatException {
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length);

        if (crc.getValue() != expected) {
            throw new FormatException(label + ": CRC-32 is "
                    + Long.toHexString(crc.getValue()) + ", the file says "
                    + Long.toHexString(expected) + " - the file is damaged");
        }
    }

    private static long u32(byte[] b, int offset) {
        return (b[offset] & 0xffL)
                | ((b[offset + 1] & 0xffL) << 8)
                | ((b[offset + 2] & 0xffL) << 16)
                | ((b[offset + 3] & 0xffL) << 24);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** A place in a run of bytes, with the reads the chunks need. */
    private static final class Cursor {

        private final byte[] data;
        private final String label;
        private int pos;
        private final int end;

        Cursor(byte[] data, String label) {
            this(data, 0, data.length, label);
        }

        Cursor(byte[] data, int start, int end, String label) {
            this.data = data;
            this.pos = start;
            this.end = end;
            this.label = label;
        }

        int remaining() {
            return end - pos;
        }

        long uvarint() throws FormatException {
            long result = 0;
            int shift = 0;

            while (true) {
                if (pos >= end) throw new FormatException(label + ": truncated varint");

                int b = data[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;

                if ((b & 0x80) == 0) return result;

                shift += 7;
                if (shift > 63) throw new FormatException(label + ": varint wider than 64 bits");
            }
        }

        int u8() throws FormatException {
            if (pos >= end) throw new FormatException(label + ": column ends early");
            return data[pos++] & 0xff;
        }

        String string(int length) throws FormatException {
            if (length < 0 || pos + length > end) {
                throw new FormatException(label + ": a text runs past the end");
            }

            String text = new String(data, pos, length, UTF_8);
            pos += length;

            return text;
        }

        String text() throws FormatException {
            return string((int) uvarint());
        }

        /** The n length-prefixed columns that follow, each as a cursor of its own. */
        Cursor[] columns(int n) throws FormatException {
            long[] lengths = new long[n];
            for (int i = 0; i < n; i++) lengths[i] = uvarint();

            Cursor[] columns = new Cursor[n];

            for (int i = 0; i < n; i++) {
                if (lengths[i] < 0 || pos + lengths[i] > end) {
                    throw new FormatException(label + ": column " + i
                            + " runs past the end of the chunk");
                }

                columns[i] = new Cursor(data, pos, pos + (int) lengths[i], label);
                pos += (int) lengths[i];
            }

            return columns;
        }

    }

}
