package dummydomain.yetanothercallblocker.data.numbers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Figures about the table the sources were built into.
 *
 * <p>Read-only, and opened for the question and closed again: this is asked from a screen,
 * not from a call, and a screen can wait a second for a count over a few million rows. What
 * it can't do is hold a handle open across a build that replaces the file.
 *
 * <p>The "top" lists sort the whole table by score when every number is asked for, which is
 * one pass with a small heap - SQLite keeps only as many rows as the limit. Asked for the
 * named ones only, it starts from the names table instead, which is a few thousand rows.
 */
public class NumbersStats {

    private static final Logger LOG = LoggerFactory.getLogger(NumbersStats.class);

    /** One number of a top list. */
    public static class Entry {

        public final long number;
        /** The business name, or null when no source had one. */
        public final String name;
        public final int rating;
        public final int category;
        /** Negative ratings minus positive ones, as the build wrote it down. */
        public final int score;

        Entry(long number, String name, int rating, int category, int score) {
            this.number = number;
            this.name = name;
            this.rating = rating;
            this.category = category;
            this.score = score;
        }

    }

    /** A name and how many, for the lists that count by something. */
    public static class Count {

        public final String name;
        public final long count;

        Count(String name, long count) {
            this.name = name;
            this.count = count;
        }

    }

    /** The whole table in a few figures. */
    public static class Overview {

        public final long numbers;
        public final long names;
        public final long negative;
        public final long positive;
        public final long neutral;
        /** Rows a later source took out of what was underneath. */
        public final long deleted;
        /** Numbers that have a business name and a negative rating - the interesting ones. */
        public final long namedNegative;
        public final long compiledTime;
        public final long size;
        public final boolean filtered;

        Overview(long numbers, long names, long negative, long positive, long neutral,
                 long deleted, long namedNegative, long compiledTime, long size,
                 boolean filtered) {
            this.numbers = numbers;
            this.names = names;
            this.negative = negative;
            this.positive = positive;
            this.neutral = neutral;
            this.deleted = deleted;
            this.namedNegative = namedNegative;
            this.compiledTime = compiledTime;
            this.size = size;
            this.filtered = filtered;
        }

    }

    private final Context context;

    public NumbersStats(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Whether there is a built table to ask at all. */
    public boolean isAvailable() {
        File file = NumbersDb.getFile(context);
        return file.exists() && file.length() > 0;
    }

    public Overview overview() {
        SQLiteDatabase db = open();
        if (db == null) return null;

        try {
            long numbers = parse(NumbersDb.getMeta(db, NumbersDb.META_COUNT, "0"));
            long names = parse(NumbersDb.getMeta(db, NumbersDb.META_NAMES, "0"));
            long compiled = parse(NumbersDb.getMeta(db, NumbersDb.META_COMPILED, "0"));
            boolean filtered = "1".equals(NumbersDb.getMeta(db, NumbersDb.META_FILTERED, "0"));

            /*
             * One pass for the three ratings and the deletions rather than four: the rating
             * is two bits of the flags, so grouping by them counts all of it at once.
             */
            long negative = 0, positive = 0, neutral = 0, deleted = 0;

            try (Cursor cursor = db.rawQuery("SELECT flags & 3, (flags & " + NumberFlags.FLAG_DELETED
                    + ") != 0, COUNT(*) FROM numbers GROUP BY 1, 2", null)) {
                while (cursor.moveToNext()) {
                    long count = cursor.getLong(2);

                    if (cursor.getInt(1) != 0) {
                        deleted += count;
                        continue;
                    }

                    switch (cursor.getInt(0)) {
                        case NumberFlags.RATING_NEGATIVE: negative += count; break;
                        case NumberFlags.RATING_POSITIVE: positive += count; break;
                        case NumberFlags.RATING_NEUTRAL: neutral += count; break;
                        default: break;
                    }
                }
            }

            long namedNegative = scalar(db, "SELECT COUNT(*) FROM names m JOIN numbers n"
                    + " ON n.number = m.number WHERE (n.flags & 3) = " + NumberFlags.RATING_NEGATIVE
                    + " AND (n.flags & " + NumberFlags.FLAG_DELETED + ") = 0");

            return new Overview(numbers, names, negative, positive, neutral, deleted,
                    namedNegative, compiled, NumbersDb.getFile(context).length(), filtered);
        } catch (Exception e) {
            LOG.warn("overview() failed", e);
            return null;
        } finally {
            db.close();
        }
    }

    /**
     * The numbers rated most strongly one way.
     *
     * @param rating    {@link NumberFlags#RATING_NEGATIVE} or {@link NumberFlags#RATING_POSITIVE}
     * @param namedOnly only the numbers some source has a business name for
     */
    public List<Entry> top(int rating, boolean namedOnly, int limit) {
        List<Entry> entries = new ArrayList<>();

        SQLiteDatabase db = open();
        if (db == null) return entries;

        // the score counts negative ratings up, so the most negative is the largest
        String order = rating == NumberFlags.RATING_NEGATIVE ? "DESC" : "ASC";

        String sql = namedOnly
                ? "SELECT n.number, m.name, n.flags, n.score FROM names m"
                        + " JOIN numbers n ON n.number = m.number"
                : "SELECT n.number, m.name, n.flags, n.score FROM numbers n"
                        + " LEFT JOIN names m ON m.number = n.number";

        sql += " WHERE (n.flags & 3) = " + rating
                + " AND (n.flags & " + NumberFlags.FLAG_DELETED + ") = 0"
                + " ORDER BY n.score " + order + ", n.number LIMIT " + limit;

        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                int flags = cursor.getInt(2);

                entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                        NumberFlags.getRating(flags), NumberFlags.getCategory(flags),
                        cursor.getInt(3)));
            }
        } catch (Exception e) {
            LOG.warn("top() failed", e);
        } finally {
            db.close();
        }

        return entries;
    }

    /**
     * How many numbers each category has, most first.
     *
     * @return the category ids as names, resolved by the caller; 0 is "no category"
     */
    public List<Count> categories() {
        List<Count> counts = new ArrayList<>();

        SQLiteDatabase db = open();
        if (db == null) return counts;

        try (Cursor cursor = db.rawQuery("SELECT (flags >> 2) & 127, COUNT(*) FROM numbers"
                + " WHERE (flags & " + NumberFlags.FLAG_DELETED + ") = 0"
                + " GROUP BY 1 ORDER BY 2 DESC", null)) {
            while (cursor.moveToNext()) {
                counts.add(new Count(String.valueOf(cursor.getInt(0)), cursor.getLong(1)));
            }
        } catch (Exception e) {
            LOG.warn("categories() failed", e);
        } finally {
            db.close();
        }

        return counts;
    }

    /** How many numbers each source contributed, in the order they were built in. */
    public List<Count> sources() {
        List<Count> counts = new ArrayList<>();

        SQLiteDatabase db = open();
        if (db == null) return counts;

        try (Cursor cursor = db.rawQuery(
                "SELECT name, count FROM sources ORDER BY layer", null)) {
            while (cursor.moveToNext()) {
                counts.add(new Count(cursor.getString(0), cursor.getLong(1)));
            }
        } catch (Exception e) {
            LOG.warn("sources() failed", e);
        } finally {
            db.close();
        }

        return counts;
    }

    private SQLiteDatabase open() {
        File file = NumbersDb.getFile(context);
        if (!file.exists()) return null;

        try {
            return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            LOG.warn("open() couldn't open {}", file, e);
            return null;
        }
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static long parse(String value) {
        try {
            return value != null ? Long.parseLong(value.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
