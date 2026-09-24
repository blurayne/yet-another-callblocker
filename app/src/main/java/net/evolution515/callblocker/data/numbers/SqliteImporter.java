package net.evolution515.callblocker.data.numbers;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.evolution515.callblocker.data.DbCompileService;

/**
 * Reads a source that hands over a SQLite database rather than slice files.
 *
 * <p>The shape is the one the update packages are built in: a {@code numbers} table keyed by
 * the number with the four counts and a category, a {@code categories} table saying what the
 * category numbers mean, a {@code deletions} table of numbers to take out of whatever is
 * underneath, a {@code featured} table of business names, and {@code meta} for the version.
 *
 * <p>The categories are the reason this isn't a plain copy. Two databases built by different
 * people number their categories differently - the same name is 3 in one and 11 in the other
 * - so the ids in the file mean nothing on their own. What they mean is in the file's own
 * categories table, and that is matched against the table being built <em>by name</em>: an
 * id that already means the same thing is left alone, and a category nobody had before is
 * added and given an id here. It is why every database carries that table.
 */
public class SqliteImporter {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteImporter.class);

    /** The first bytes of every SQLite file there has ever been. */
    private static final byte[] MAGIC = {
            'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0};

    /** What the file calls the version it was published as, in the order they are believed. */
    private static final String[] VERSION_KEYS = {
            "our_db_version", "db_version", "sia_secondary_db_version", "sia_base_db_version"};

    /** How many rows are read before the work so far is handed to the writer's own counting. */
    private static final int REPORT_EVERY = 25_000;

    /** Told how far along the reading is, so that a screen can say. */
    interface Progress {
        void onRead(long rows);
    }

    /** What reading one database came to. */
    static class Result {

        /** Everything the file held, before the filter had a say. */
        final long read;

        /** How many of those were numbers to take out rather than to write. */
        final long deletions;

        /** And how many the filter kept out. */
        final long skipped;

        /** How many business names it brought, beside the numbers. */
        final long names;

        /** How many categories the file names, and which of them nobody had before. */
        final int categories;
        final java.util.List<String> newCategories;

        /** What the file says it is, or 0 when it doesn't say. */
        final int version;

        Result(long read, long deletions, long skipped, long names, int categories,
               java.util.List<String> newCategories, int version) {
            this.read = read;
            this.deletions = deletions;
            this.skipped = skipped;
            this.names = names;
            this.categories = categories;
            this.newCategories = newCategories;
            this.version = version;
        }

    }

    private SqliteImporter() {
    }

    /** Whether the file is a SQLite database, by its first bytes rather than its name. */
    public static boolean isDatabase(File file) {
        if (file == null || !file.isFile() || file.length() < MAGIC.length) return false;

        byte[] header = new byte[MAGIC.length];

        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < header.length) {
                int count = in.read(header, read, header.length - read);
                if (count == -1) return false;
                read += count;
            }
        } catch (IOException e) {
            LOG.debug("isDatabase() couldn't read {}", file, e);
            return false;
        }

        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) return false;
        }

        return true;
    }

    /** The one SQLite database in a directory, or null when there is none. */
    public static File find(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;

        for (File file : files) {
            if (isDatabase(file)) return file;
        }

        return null;
    }

    /**
     * Reads the database into the table being built.
     *
     * @param target where it is going, open and inside a transaction
     * @param asLayer whether what is there already has a say, or this writes into an empty table
     * @param filter which numbers are worth keeping at all, or null for all of them
     */
    static Result read(File file, SQLiteDatabase target, NumbersWriter writer, int sourceId,
                       boolean asLayer, NumbersFilter filter, Progress progress) {
        SQLiteDatabase source = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);

        try {
            java.util.List<String> added = new java.util.ArrayList<>();
            int[] named = {0};

            SparseIntArray categories = mapCategories(source, target, named, added);

            long[] counts = readNumbers(source, target, writer, sourceId, asLayer, filter,
                    categories, progress);

            long deletions = readDeletions(source, writer);

            long names = readNames(source, writer, sourceId, filter);

            return new Result(counts[0] + deletions, deletions, counts[1], names, named[0],
                    added, versionOf(source));
        } finally {
            source.close();
        }
    }

    /**
     * What each of the file's category ids means in the table being built.
     *
     * <p>Only the ones that have to change are in it: an id that already means the same name
     * here is not worth a lookup for every row that uses it, so it is left out and the id
     * goes in as it stands. What is left is the ones that differ, and the ones nobody had
     * before - those are added to the table as they are found.
     */
    private static SparseIntArray mapCategories(SQLiteDatabase source, SQLiteDatabase target,
                                                int[] named, java.util.List<String> added) {
        return mapCategories(NumbersDb.getCategories(source), target, named, added);
    }

    /** The same, for a file that says what its categories are called some other way. */
    static SparseIntArray mapCategories(SparseArray<String> theirs, SQLiteDatabase target,
                                        int[] named, java.util.List<String> added) {
        SparseIntArray mapping = new SparseIntArray();

        named[0] = theirs.size();

        if (theirs.size() == 0) {
            LOG.info("mapCategories() the file says nothing about its categories");
            return mapping;
        }

        SparseArray<String> ours = NumbersDb.getCategories(target);

        for (int i = 0; i < theirs.size(); i++) {
            int theirId = theirs.keyAt(i);
            String name = theirs.valueAt(i);

            // "unknown" is the absence of a category, not one to add and write over others
            if (NumbersDb.isNoCategory(name)) {
                if (theirId != 0) mapping.put(theirId, 0);
                continue;
            }

            // the same name under the same number: nothing to do for any row that uses it
            if (name != null && name.equals(ours.get(theirId))) continue;

            int ourId = NumbersDb.categoryFor(target, name);

            // an id the table didn't have a name for before is one this file just brought
            if (ourId > 0 && ours.get(ourId) == null) {
                added.add(name.trim());
                ours.put(ourId, name.trim());
            }

            if (ourId != theirId) mapping.put(theirId, ourId);
        }

        LOG.info("mapCategories() {} of {} categories are numbered differently here",
                mapping.size(), theirs.size());

        return mapping;
    }

    /** @return {@code {read, skipped}} */
    private static long[] readNumbers(SQLiteDatabase source, SQLiteDatabase target,
                                      NumbersWriter writer, int sourceId, boolean asLayer,
                                      NumbersFilter filter, SparseIntArray categories,
                                      Progress progress) {
        long read = 0;
        long skipped = 0;

        try (Cursor cursor = source.rawQuery(
                "SELECT number, positive, negative, neutral, category FROM numbers", null)) {
            while (cursor.moveToNext()) {
                read++;

                long number = cursor.getLong(0);

                // the ones that don't belong are never written, not written and deleted again
                if (filter != null && !filter.keep(number)) {
                    skipped++;
                    continue;
                }

                int positive = cursor.getInt(1);
                int negative = cursor.getInt(2);
                int neutral = cursor.getInt(3);
                int category = categories.get(cursor.getInt(4), cursor.getInt(4));

                int rating = NumberFlags.ratingOf(positive, negative, neutral);
                int score = NumberFlags.scoreOf(positive, negative);

                if (asLayer) {
                    writer.merge(number, rating, category, score, false, sourceId);
                } else {
                    writer.put(number, NumberFlags.of(rating, category, 0), score, sourceId);
                }

                if (progress != null && read % REPORT_EVERY == 0) progress.onRead(read);
            }
        } catch (DbCompileService.Cancelled e) {
            throw e; // asked to stop, which is not the file's fault
        } catch (Exception e) {
            LOG.error("readNumbers() failed after {} rows", read, e);

            throw new RuntimeException(e);
        }

        return new long[]{read, skipped};
    }

    /**
     * The numbers the file says to take out of what is underneath it.
     *
     * <p>Not filtered: taking a number out is not a claim about it, and a number the filter
     * would have kept out isn't there to be taken out anyway.
     */
    private static long readDeletions(SQLiteDatabase source, NumbersWriter writer) {
        long deleted = 0;

        try (Cursor cursor = source.rawQuery("SELECT number FROM deletions", null)) {
            while (cursor.moveToNext()) {
                writer.delete(cursor.getLong(0));
                deleted++;
            }
        } catch (Exception e) {
            // a file without the table simply has nothing to take out
            LOG.debug("readDeletions() no deletions in this one", e);
        }

        return deleted;
    }

    /** The tables a file may keep its business names in, in the order they are looked for. */
    private static final String[] NAME_TABLES = {"featured", "names"};

    /**
     * The business names the file has for its numbers.
     *
     * <p>Read after the deletions, so that a name this file brings is not one it takes out
     * again in the same breath. A later source overwrites a name an earlier one gave, which
     * is the same rule the numbers follow: the order decides. Filtered like the numbers,
     * because a name for a number the filter keeps out is a name nothing will ask for.
     *
     * @return how many went in
     */
    private static long readNames(SQLiteDatabase source, NumbersWriter writer, int sourceId,
                                  NumbersFilter filter) {
        for (String table : NAME_TABLES) {
            long names = 0;

            try (Cursor cursor = source.rawQuery(
                    "SELECT number, name FROM " + table + " WHERE name IS NOT NULL", null)) {
                while (cursor.moveToNext()) {
                    long number = cursor.getLong(0);

                    if (filter != null && !filter.keep(number)) continue;

                    String name = cursor.getString(1);
                    if (name == null) continue;

                    name = name.trim();
                    if (name.isEmpty()) continue;

                    writer.putName(number, name, sourceId);
                    names++;
                }
            } catch (Exception e) {
                // a file without the table has no names to bring; the next name is tried
                LOG.debug("readNames() no {} table in this one", table, e);
                continue;
            }

            LOG.info("readNames() {} names from {}", names, table);

            return names;
        }

        return 0;
    }

    /** What the file says it is, by whichever of the version keys it carries. */
    private static int versionOf(SQLiteDatabase source) {
        for (String key : VERSION_KEYS) {
            String value = NumbersDb.getMeta(source, key, null);
            if (value == null) continue;

            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                LOG.debug("versionOf() {} isn't a number: {}", key, value);
            }
        }

        return 0;
    }

}
