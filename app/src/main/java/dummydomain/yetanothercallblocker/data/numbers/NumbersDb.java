package dummydomain.yetanothercallblocker.data.numbers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * The one table every source ends up in.
 *
 * <p>A row is a number, what is known about it packed into an integer, how strongly the
 * community feels, and which source said so. The number is the primary key, which in SQLite
 * means it <em>is</em> the tree the table is stored in - no second index, no lookup that
 * loads anything, and a country filter is a range over that key.
 *
 * <p>The sources are a table of their own and the rows point at it, so removing a source
 * removes its numbers with it. Names live apart because only a few numbers have one.
 *
 * <p>It is a file of its own rather than a table in the app's database on purpose: this one
 * is thrown away and built again, and nothing the user typed may sit in a file that a rebuild
 * replaces.
 */
public class NumbersDb extends SQLiteOpenHelper {

    private static final Logger LOG = LoggerFactory.getLogger(NumbersDb.class);

    public static final String FILE_NAME = "numbers.db";

    /** The copy kept before filtering, which is what "unfiltered" means afterwards. */
    public static final String SHADOW_FILE_NAME = "numbers-shadow.db";

    /**
     * Where a build is assembled.
     *
     * <p>A build writes here and nowhere else, and only takes the place of the database in
     * use when it is finished and filtered. Until then the app keeps answering out of the
     * database it already had: nothing waits for the build, nothing is locked by it, and a
     * build that fails or is killed leaves what worked exactly where it was.
     */
    public static final String BUILD_FILE_NAME = "numbers-build.db";

    private static final int VERSION = 1;

    /** Whether the database in use has been filtered. */
    public static final String META_FILTERED = "filtered";

    /** When it was built, as milliseconds. */
    public static final String META_COMPILED = "compiled";

    /**
     * How many numbers it holds.
     *
     * <p>Written down rather than counted: counting two million rows walks the whole key and
     * is not something a screen can ask for while the user is looking at it.
     */
    public static final String META_COUNT = "count";

    private static final String[] SCHEMA = {
            "CREATE TABLE sources ("
                    + "id INTEGER PRIMARY KEY,"
                    + "uuid TEXT NOT NULL UNIQUE,"
                    + "name TEXT,"
                    + "type INTEGER NOT NULL,"
                    + "layer INTEGER NOT NULL,"
                    // how many rows are left pointing at it, written down when that changes
                    + "count INTEGER NOT NULL DEFAULT 0)",

            "CREATE TABLE numbers ("
                    + "number INTEGER PRIMARY KEY,"
                    + "flags INTEGER NOT NULL DEFAULT 0,"
                    + "score INTEGER NOT NULL DEFAULT 0,"
                    + "source INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,"
                    + "updated INTEGER NOT NULL DEFAULT 0)",

            "CREATE INDEX numbers_source ON numbers(source)",

            "CREATE TABLE names ("
                    + "number INTEGER PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "source INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE)",

            "CREATE INDEX names_source ON names(source)",

            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
    };

    private static final String[] TABLES = {"names", "numbers", "sources", "meta"};

    public NumbersDb(Context context) {
        this(context, FILE_NAME);
    }

    /** @param fileName which of the databases this is: the one in use, or one being built */
    public NumbersDb(Context context, String fileName) {
        super(context, fileName, null, VERSION);
    }

    /** Where the file is, for copying it aside and putting it back. */
    public static File getFile(Context context) {
        return context.getDatabasePath(FILE_NAME);
    }

    public static File getShadowFile(Context context) {
        return getFile(context, SHADOW_FILE_NAME);
    }

    public static File getBuildFile(Context context) {
        return getFile(context, BUILD_FILE_NAME);
    }

    public static File getFile(Context context, String fileName) {
        return new File(getFile(context).getParentFile(), fileName);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        LOG.info("onCreate() creating the tables");

        for (String statement : SCHEMA) {
            db.execSQL(statement);
        }
    }

    /**
     * There is nothing to migrate: every row came from a source and can be fetched again, so
     * a new layout starts from an empty table rather than from a careful rewrite.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        LOG.info("onUpgrade() {} -> {}, building the tables again", oldVersion, newVersion);

        recreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    @Override
    public void onConfigure(@NonNull SQLiteDatabase db) {
        super.onConfigure(db);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            db.setForeignKeyConstraintsEnabled(true);
        }
    }

    @Override
    public void onOpen(@NonNull SQLiteDatabase db) {
        super.onOpen(db);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN && !db.isReadOnly()) {
            db.execSQL("PRAGMA foreign_keys = ON");
        }

        /*
         * A small cache on purpose. Every page SQLite keeps is memory the app is holding
         * outside its heap, where nothing warns about it and nothing can be caught: a phone
         * that runs low kills the app instead. Two megabytes is plenty for looking a number
         * up, and a build is bounded by how often it commits rather than by how much it can
         * keep.
         */
        try {
            db.execSQL("PRAGMA cache_size = -2000"); // negative: kibibytes rather than pages
        } catch (Exception e) {
            LOG.warn("onOpen() couldn't set the cache size", e);
        }
    }

    /** Empties everything, which is how a compile starts. */
    public void recreate(SQLiteDatabase db) {
        for (String table : TABLES) {
            db.execSQL("DROP TABLE IF EXISTS " + table);
        }

        onCreate(db);
    }

    public static String getMeta(SQLiteDatabase db, String key, String defaultValue) {
        try (Cursor cursor = db.rawQuery("SELECT value FROM meta WHERE key = ?",
                new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : defaultValue;
        } catch (Exception e) {
            LOG.warn("getMeta({})", key, e);
            return defaultValue;
        }
    }

    public static void setMeta(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                new Object[]{key, value});
    }

    /** How many numbers the database holds, or -1 when it can't say. */
    public static long getCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM numbers", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        } catch (Exception e) {
            LOG.warn("getCount()", e);
            return -1;
        }
    }

}
