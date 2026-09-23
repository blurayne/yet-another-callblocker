package net.evolution515.callblocker.data.numbers;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.Closeable;

/**
 * Writes what a source says into the table, a field at a time.
 *
 * <p>A source only ever sets what it knows. The community database brings a rating and a
 * category, a phone book brings a name, a list of numbers to block brings nothing but the
 * fact that it is on the list - so a layer must be able to change the rating of a number
 * without erasing the category another source gave it, which is why a merge reads the row
 * first rather than writing over it.
 *
 * <p>The database itself is the exception: it is written into an empty table and has nothing
 * to merge with, so it goes in with one statement per row and no read at all. That is the
 * difference between a build that takes a minute and one that takes ten.
 *
 * <p>A number is in the table once, however many times it is written. It is the key of the
 * table, so writing it again lands on the row that is already there - the second of two
 * sources that know the same number changes that row rather than adding another, and a slice
 * that holds a number twice ends up with one of it either way.
 */
public class NumbersWriter implements Closeable {

    private static final String INSERT_NUMBER
            = "INSERT OR REPLACE INTO numbers (number, flags, score, source, updated)"
            + " VALUES (?, ?, ?, ?, ?)";

    private static final String DELETE_NUMBER = "DELETE FROM numbers WHERE number = ?";

    private static final String INSERT_NAME
            = "INSERT OR REPLACE INTO names (number, name, source) VALUES (?, ?, ?)";

    private static final String DELETE_NAME = "DELETE FROM names WHERE number = ?";

    private final SQLiteDatabase db;

    private final SQLiteStatement insertNumber;
    private final SQLiteStatement deleteNumber;
    private final SQLiteStatement insertName;
    private final SQLiteStatement deleteName;

    /** Days since the epoch: what "when did this arrive" is written as. */
    private final int today;

    /** How many rows have actually gone away, which only the statement can say. */
    private long deletedRows;

    /**
     * Which category ids have been written, for the source being read and for the whole run.
     *
     * <p>Noted here because every row goes through here, whatever it was read out of. The
     * ids fit in seven bits, so this is two arrays of 128 and a flag set per row.
     */
    private final boolean[] sourceCategories = new boolean[128];
    private final boolean[] runCategories = new boolean[128];

    public NumbersWriter(SQLiteDatabase db) {
        this.db = db;

        insertNumber = db.compileStatement(INSERT_NUMBER);
        deleteNumber = db.compileStatement(DELETE_NUMBER);
        insertName = db.compileStatement(INSERT_NAME);
        deleteName = db.compileStatement(DELETE_NAME);

        today = (int) (System.currentTimeMillis() / (24L * 60 * 60 * 1000));
    }

    /**
     * The row a source already has in the table, or -1 when it has none.
     *
     * <p>Wanted when something is written into a table that is already built - the library's
     * own update, which arrives between builds - and has to be written down as coming from
     * the source it belongs to rather than as a source of its own.
     */
    public int findSource(String uuid) {
        try (android.database.Cursor cursor = db.query("sources", new String[]{"id"},
                "uuid = ?", new String[]{uuid}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Puts a source into the table the rows point at, and says which row it is. */
    public int addSource(String uuid, String name, int type, int layer) {
        try (SQLiteStatement statement = db.compileStatement(
                "INSERT INTO sources (uuid, name, type, layer) VALUES (?, ?, ?, ?)")) {
            bindString(statement, 1, uuid);
            bindString(statement, 2, name);
            statement.bindLong(3, type);
            statement.bindLong(4, layer);

            return (int) statement.executeInsert();
        }
    }

    /**
     * Writes a row without looking at what was there - for the source that fills an empty
     * table.
     */
    public void put(long number, int flags, int score, int sourceId) {
        if (number <= 0) return;

        noteCategory(NumberFlags.getCategory(flags));

        insertNumber.bindLong(1, number);
        insertNumber.bindLong(2, flags);
        insertNumber.bindLong(3, score);
        insertNumber.bindLong(4, sourceId);
        insertNumber.bindLong(5, today);

        insertNumber.executeInsert();
    }

    /**
     * Writes what this source knows and leaves the rest of the row as it is.
     *
     * @param rating the rating, or null when this source doesn't have one
     * @param category the category, or null when this source doesn't have one
     * @param score how strongly it feels, or null when it doesn't say
     * @param personal whether this is the user's own word rather than a stranger's
     */
    public void merge(long number, Integer rating, Integer category, Integer score,
                      boolean personal, int sourceId) {
        if (number <= 0) return;

        /*
         * Not knowing is not something to say over what is known. A source that has the
         * number without a category - 0, "none", or a category its file calls unknown - leaves
         * the category an earlier source gave it; one without any ratings leaves the rating,
         * and the score that goes with it. What it does know still goes in.
         */
        if (category != null && category <= 0) category = null;

        if (rating != null && rating == NumberFlags.RATING_UNKNOWN) {
            rating = null;
            score = null;
        }

        if (category != null) noteCategory(category);

        int flags = 0;
        int currentScore = 0;

        try (Cursor cursor = db.rawQuery("SELECT flags, score FROM numbers WHERE number = ?",
                new String[]{String.valueOf(number)})) {
            if (cursor.moveToFirst()) {
                flags = cursor.getInt(0);
                currentScore = cursor.getInt(1);
            }
        }

        if (rating != null) flags = NumberFlags.withRating(flags, rating);
        if (category != null) flags = NumberFlags.withCategory(flags, category);
        if (personal) flags = NumberFlags.withFlag(flags, NumberFlags.FLAG_PERSONAL, true);

        // a merge never leaves the row marked as taken out: this source has just spoken for it
        flags = NumberFlags.withFlag(flags, NumberFlags.FLAG_DELETED, false);

        put(number, flags, score != null ? score : currentScore, sourceId);
    }

    /** Takes a number out, which is what a source says when it disagrees with the one below. */
    public void delete(long number) {
        if (number <= 0) return;

        deleteNumber.bindLong(1, number);

        // a number a source takes out that wasn't there is not a number that went away
        deletedRows += deleteNumber.executeUpdateDelete();

        deleteName.bindLong(1, number);
        deleteName.executeUpdateDelete();
    }

    private void noteCategory(int category) {
        // 0 is "none", which is not a category anyone would count
        if (category <= 0 || category >= sourceCategories.length) return;

        sourceCategories[category] = true;
        runCategories[category] = true;
    }

    /** Starts counting categories again, for the next source. */
    public void startSource() {
        java.util.Arrays.fill(sourceCategories, false);
    }

    /** How many different categories the source being read has written. */
    public int getSourceCategories() {
        return count(sourceCategories);
    }

    /** How many different categories have been written since this writer was made. */
    public int getRunCategories() {
        return count(runCategories);
    }

    private static int count(boolean[] seen) {
        int count = 0;
        for (boolean each : seen) if (each) count++;
        return count;
    }

    /** How many rows have been taken out since this writer was made. */
    public long getDeletedRows() {
        return deletedRows;
    }

    /** The name a phone book has for the number, or nothing when it has none. */
    public void putName(long number, String name, int sourceId) {
        if (number <= 0 || name == null) return;

        // an empty name, or one of blanks, leaves the name an earlier source gave
        name = name.trim();
        if (name.isEmpty()) return;

        insertName.bindLong(1, number);
        insertName.bindString(2, name);
        insertName.bindLong(3, sourceId);

        insertName.executeInsert();
    }

    private static void bindString(SQLiteStatement statement, int index, String value) {
        if (value != null) {
            statement.bindString(index, value);
        } else {
            statement.bindNull(index);
        }
    }

    @Override
    public void close() {
        insertNumber.close();
        deleteNumber.close();
        insertName.close();
        deleteName.close();
    }

}
