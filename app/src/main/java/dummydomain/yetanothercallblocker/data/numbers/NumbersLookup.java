package dummydomain.yetanothercallblocker.data.numbers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.SparseArray;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;

/**
 * Looks a number up in the table the build fills.
 *
 * <p>This is what every source is built into, so it is what a number has to be asked of: a
 * source that hands over a whole directory of its own is in this table and nowhere else, and
 * until the question was asked here it was built and then never consulted.
 *
 * <p>One seek and no loading. The number is the table's own key, which in SQLite means it is
 * the tree the rows are stored in - there is no index to walk, nothing is read into memory,
 * and a phone answers this while the call is still ringing.
 *
 * <p>What comes back is shaped like what the library's own files answer with, because that
 * is what everything downstream already reads. The counts are made up from the rating and
 * the score the table holds rather than kept one by one: the app only ever asks which way
 * they point and how strongly, and two numbers say that in eight bytes instead of twelve.
 */
public class NumbersLookup {

    private static final Logger LOG = LoggerFactory.getLogger(NumbersLookup.class);

    private static final String[] COLUMNS = {"flags", "score"};
    private static final String[] NAME_COLUMNS = {"name"};

    private final Context context;

    /** Opened when the first number is asked and kept; null when there is no table yet. */
    private SQLiteDatabase db;

    /** So that a missing table isn't opened again for every call. */
    private boolean tried;

    /** How many numbers it holds, as the build wrote it down; 0 when there is nothing. */
    private long count;

    /** What each category id in there means, read once and kept; it is a couple of dozen rows. */
    private SparseArray<String> categories;

    public NumbersLookup(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Whether the table can be asked at all.
     *
     * <p>Said separately because a table that has been built is the whole answer, deletions
     * included: a number a later source took out is a number nothing knows about, and asking
     * the library afterwards would put back exactly what the user's order took away. So the
     * library is asked instead of this, never after it.
     */
    public synchronized boolean isReady() {
        return open() != null && count > 0;
    }

    /**
     * What the table says about the number, the way the library is asked.
     *
     * @param numberString international, with or without the plus
     */
    public CommunityDatabaseItem get(String numberString) {
        if (numberString == null || numberString.isEmpty()) return null;

        if (numberString.startsWith("+")) numberString = numberString.substring(1);

        try {
            return get(Long.parseLong(numberString));
        } catch (NumberFormatException e) {
            LOG.debug("get() not a number: {}", numberString);
            return null;
        }
    }

    /**
     * What the table says about the number, or null when it says nothing.
     *
     * @param number the number as the databases key it: international, without the plus
     */
    public synchronized CommunityDatabaseItem get(long number) {
        if (number <= 0) return null;

        SQLiteDatabase db = open();
        if (db == null) return null;

        try (Cursor cursor = db.query("numbers", COLUMNS, "number = ?",
                new String[]{String.valueOf(number)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;

            int flags = cursor.getInt(0);

            // taken out by a later source, which means the sources below it don't count
            if (NumberFlags.isDeleted(flags)) return null;

            return itemOf(number, flags, cursor.getInt(1));
        } catch (Exception e) {
            LOG.warn("get() couldn't look {} up", number, e);
            return null;
        }
    }

    /**
     * The business name a source had for the number, or null when none had one.
     *
     * <p>Kept apart from {@link #get(long)} because a name is only wanted for a screen, and
     * a call is answered without one. One seek in the names table, which is keyed the same
     * way as the numbers; the name is whatever the last source in the order said.
     *
     * @param numberString international, with or without the plus
     */
    public String getName(String numberString) {
        if (numberString == null || numberString.isEmpty()) return null;

        if (numberString.startsWith("+")) numberString = numberString.substring(1);

        try {
            return getName(Long.parseLong(numberString));
        } catch (NumberFormatException e) {
            LOG.debug("getName() not a number: {}", numberString);
            return null;
        }
    }

    public synchronized String getName(long number) {
        if (number <= 0) return null;

        SQLiteDatabase db = open();
        if (db == null) return null;

        try (Cursor cursor = db.query("names", NAME_COLUMNS, "number = ?",
                new String[]{String.valueOf(number)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;

            String name = cursor.getString(0);

            return name != null && !name.isEmpty() ? name : null;
        } catch (Exception e) {
            LOG.warn("getName() couldn't look {} up", number, e);
            return null;
        }
    }

    /**
     * Forgets the open table, so that the next number opens whatever is there now.
     *
     * <p>A finished build replaces the file rather than writing into it, and a handle that
     * was open across that is still reading the file that was replaced - which would answer
     * out of the old database for as long as the app stays running.
     */
    public synchronized void reload() {
        close();

        tried = false;
        count = 0;
        categories = null;
    }

    public synchronized void close() {
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                LOG.warn("close() failed", e);
            }

            db = null;
        }
    }

    private SQLiteDatabase open() {
        if (db != null && db.isOpen()) return db;

        db = null;

        if (tried) return null;
        tried = true;

        /*
         * Read-only and only if it is already there: a lookup must never be what creates the
         * table, both because an empty one answers nothing and because the first call would
         * otherwise pay for making it.
         */
        File file = NumbersDb.getFile(context);
        if (!file.exists()) return null;

        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            count = readCount(db);

            LOG.info("open() the table holds {} numbers", count);
        } catch (Exception e) {
            LOG.warn("open() couldn't open {}", file, e);
            db = null;
        }

        return db;
    }

    /**
     * How many numbers are in there, as the build wrote it down.
     *
     * <p>Read rather than counted: counting the rows walks the whole key, and this is asked
     * on the way to answering the first call of the day.
     */
    private static long readCount(SQLiteDatabase db) {
        try (Cursor cursor = db.query("meta", new String[]{"value"}, "key = ?",
                new String[]{NumbersDb.META_COUNT}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return 0;

            return Long.parseLong(cursor.getString(0));
        } catch (Exception e) {
            LOG.warn("readCount() failed", e);
            return 0;
        }
    }

    /**
     * What a category id means, for the ones the library's own list doesn't have.
     *
     * <p>A source can bring categories nobody had before, and they are given ids here as
     * they arrive. The names are the source's own - untranslated, because nobody has
     * translated a category that didn't exist until this morning - which is better than
     * showing a number or nothing at all.
     *
     * @return the name as the table has it, or null when it doesn't know the id either
     */
    public synchronized String categoryName(int id) {
        SQLiteDatabase db = open();
        if (db == null) return null;

        if (categories == null) categories = NumbersDb.getCategories(db);

        return categories.get(id);
    }

    /**
     * The row, said the way the rest of the app reads it.
     *
     * <p>The counts are worked back out of the rating so that whoever compares them reaches
     * the same verdict the build did. The score is how far apart they were, so it is used as
     * the size of the count that won; a rating with no score behind it is still one voice,
     * never none, because none would read as "nothing is known".
     */
    private static CommunityDatabaseItem itemOf(long number, int flags, int score) {
        int rating = NumberFlags.getRating(flags);
        if (rating == NumberFlags.RATING_UNKNOWN) return null;

        CommunityDatabaseItem item = new CommunityDatabaseItem();

        item.setNumber(number);
        item.setCategory(NumberFlags.getCategory(flags));

        switch (rating) {
            case NumberFlags.RATING_NEGATIVE:
                item.setNegativeRatingsCount(Math.max(1, score));
                break;

            case NumberFlags.RATING_POSITIVE:
                item.setPositiveRatingsCount(Math.max(1, -score));
                break;

            default:
                /*
                 * One each way: it is what "the community is divided" amounts to, and the
                 * one positive is also what keeps the entry from reading as empty - the
                 * library counts an entry as having ratings by its positive and negative
                 * ones alone.
                 */
                item.setPositiveRatingsCount(1);
                item.setNeutralRatingsCount(1);
                break;
        }

        return item;
    }

}
