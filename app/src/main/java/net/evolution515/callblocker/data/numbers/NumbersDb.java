package net.evolution515.callblocker.data.numbers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import dummydomain.yetanothercallblocker.sia.model.NumberCategory;

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

    /**
     * What older versions kept beside the table: a copy made before it was filtered.
     *
     * <p>Nothing writes it any more - the filter is asked as each number is read, so nothing
     * that doesn't belong is ever written and there is nothing to go back to. The name is
     * still here to find the file an older version left behind and delete it.
     */
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

    private static final int VERSION = 2;

    /** The first id a category that nobody had before is given. */
    private static final int FIRST_ADDED_CATEGORY = 32;

    /** And the last one a row has room for: the flags keep seven bits for this. */
    private static final int LAST_CATEGORY = 127;

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

    /** How many business names it holds, written down for the same reason. */
    public static final String META_NAMES = "names";

    /** The tables themselves; what a build fills. */
    private static final String[] TABLES_SQL = {
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

            "CREATE TABLE names ("
                    + "number INTEGER PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "source INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE)",

            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",

            /*
             * What a category id means, so that the table is self-describing.
             *
             * <p>The library has an enum of nineteen and numbers them itself; a source that
             * brings its own has its own numbering, and the same name can be 3 in one and 11
             * in the other. Keeping the names here is what lets the two be reconciled by
             * name rather than by a number neither of them agreed on - and what lets a
             * category nobody had before simply be added.
             */
            "CREATE TABLE categories ("
                    + "id INTEGER PRIMARY KEY,"
                    + "name TEXT NOT NULL UNIQUE)",
    };

    /**
     * What is kept up to date beside the tables, made after they are filled rather than while.
     *
     * <p>An index that exists while nine million rows are written is nine million insertions
     * into a second tree, in an order that has nothing to do with its own. Built afterwards
     * it is one pass over rows that are already there, which is both faster and tidier.
     *
     * <p>Neither of them is used for looking a number up - the number is the table's own key
     * - only for counting what each source contributed and for taking a source's rows out
     * with it.
     */
    private static final String[] INDEXES_SQL = {
            "CREATE INDEX IF NOT EXISTS numbers_source ON numbers(source)",
            "CREATE INDEX IF NOT EXISTS names_source ON names(source)",
    };

    /** Everything {@link #recreate} clears out; a table missing here is one a rebuild
     * would try to create a second time. */
    private static final String[] TABLES
            = {"names", "numbers", "sources", "meta", "categories"};

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

        createTables(db);
        createIndexes(db);
    }

    public static void createTables(SQLiteDatabase db) {
        for (String statement : TABLES_SQL) {
            db.execSQL(statement);
        }

        seedCategories(db);
    }

    /**
     * Writes down what the library's own category numbers mean.
     *
     * <p>Every table starts knowing these, so a source that brings categories of its own is
     * matched against them by name before anything new is added. The names are the enum's,
     * not the translated ones: this is what two databases agree on, not what a screen shows.
     */
    public static void seedCategories(SQLiteDatabase db) {
        for (NumberCategory category : NumberCategory.values()) {
            db.execSQL("INSERT OR IGNORE INTO categories (id, name) VALUES (?, ?)",
                    new Object[]{category.getId(), category.name()});
        }
    }

    /**
     * The id this table uses for a category of that name, adding it when it is new.
     *
     * <p>The id a source used is its own business. What matters is the name, because that is
     * the only thing two databases built by different people mean the same by.
     *
     * @return the id to write into a row, or 0 - "nothing is known" - when there is no room
     */
    /** What files call the absence of a category, which is not a category of its own. */
    private static final java.util.Set<String> NO_CATEGORY = new java.util.HashSet<>(
            java.util.Arrays.asList("none", "unknown", "unbekannt", "keine", "n/a", "-", "?"));

    /**
     * Whether a category name only says that there is none.
     *
     * <p>Such a category would be added like any other and then written over a real one:
     * "UNKNOWN" from one source in place of "SCAM" from another. It is taken for 0 instead,
     * which a later source never writes over anything.
     */
    public static boolean isNoCategory(String name) {
        return name == null || NO_CATEGORY.contains(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static int categoryFor(SQLiteDatabase db, String name) {
        if (name == null || name.trim().isEmpty()) return 0;

        String cleaned = name.trim();

        try (Cursor cursor = db.rawQuery("SELECT id FROM categories WHERE name = ?",
                new String[]{cleaned})) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Exception e) {
            LOG.warn("categoryFor({})", cleaned, e);
            return 0;
        }

        int id = freeCategoryId(db);

        if (id <= 0) {
            LOG.warn("categoryFor() no room left for {}", cleaned);
            return 0;
        }

        db.execSQL("INSERT OR IGNORE INTO categories (id, name) VALUES (?, ?)",
                new Object[]{id, cleaned});

        LOG.info("categoryFor() {} is now {}", cleaned, id);

        return id;
    }

    /**
     * An id no category has yet.
     *
     * <p>Above the library's own block and below the ones it keeps for "this number is
     * fine", so that a new category can never be mistaken for either. Seven bits is what a
     * row has for this, which leaves room for far more categories than anyone will have.
     */
    private static int freeCategoryId(SQLiteDatabase db) {
        for (int id = FIRST_ADDED_CATEGORY; id <= LAST_CATEGORY; id++) {
            if (id >= 100 && id <= 102) continue; // the library's "safe" ones

            try (Cursor cursor = db.rawQuery("SELECT 1 FROM categories WHERE id = ?",
                    new String[]{String.valueOf(id)})) {
                if (!cursor.moveToFirst()) return id;
            } catch (Exception e) {
                LOG.warn("freeCategoryId()", e);
                return 0;
            }
        }

        return 0;
    }

    /** What every category id in this table means: {@code {id: name}}. */
    public static SparseArray<String> getCategories(SQLiteDatabase db) {
        SparseArray<String> categories = new SparseArray<>();

        try (Cursor cursor = db.rawQuery("SELECT id, name FROM categories", null)) {
            while (cursor.moveToNext()) {
                categories.put(cursor.getInt(0), cursor.getString(1));
            }
        } catch (Exception e) {
            LOG.warn("getCategories()", e);
        }

        return categories;
    }

    /** Made when the table is full; see {@link #INDEXES_SQL}. */
    public static void createIndexes(SQLiteDatabase db) {
        for (String statement : INDEXES_SQL) {
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

            /*
             * The database a build assembles is written as fast as the phone can take it:
             * no waiting for the flash to confirm every commit, and a journal that lives in
             * memory rather than beside the file. Both are safe here for the same reason -
             * this file is thrown away if anything goes wrong with it, and what it replaces
             * stays untouched until it is finished.
             */
            if (BUILD_FILE_NAME.equals(getDatabaseName()) && !db.isReadOnly()) {
                db.execSQL("PRAGMA synchronous = OFF");
                db.execSQL("PRAGMA journal_mode = MEMORY");
            }
        } catch (Exception e) {
            LOG.warn("onOpen() couldn't set the database up for writing", e);
        }
    }

    /**
     * Empties everything, which is how a compile starts.
     *
     * @param withIndexes whether the indexes are made now or left until the table is full
     */
    public void recreate(SQLiteDatabase db, boolean withIndexes) {
        for (String table : TABLES) {
            db.execSQL("DROP TABLE IF EXISTS " + table);
        }

        createTables(db);

        if (withIndexes) createIndexes(db);
    }

    public void recreate(SQLiteDatabase db) {
        recreate(db, true);
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

    /** How many business names the database holds, or 0 when it can't say. */
    public static long getNamesCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM names", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } catch (Exception e) {
            LOG.warn("getNamesCount()", e);
            return 0;
        }
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
