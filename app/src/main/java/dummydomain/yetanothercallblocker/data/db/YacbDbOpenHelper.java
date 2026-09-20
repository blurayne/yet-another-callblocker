package dummydomain.yetanothercallblocker.data.db;

import android.content.Context;
import android.database.Cursor;

import org.greenrobot.greendao.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to an existing database when the app is updated.
 *
 * <p>Nothing is ever dropped or rewritten here: a blacklist is something the user built by
 * hand over years, and an update is no reason to lose it. A new version only ever adds what
 * it needs, and only when it isn't there already, so an upgrade that ran halfway (or twice)
 * does no harm either.
 */
public class YacbDbOpenHelper extends DaoMaster.OpenHelper {

    private static final Logger LOG = LoggerFactory.getLogger(YacbDbOpenHelper.class);

    public YacbDbOpenHelper(Context context, String name) {
        super(context, name);
    }

    @Override
    public void onUpgrade(Database db, int oldVersion, int newVersion) {
        LOG.info("onUpgrade() oldVersion={}, newVersion={}", oldVersion, newVersion);

        // 2: what the user wants to remember about a number
        addColumn(db, BlacklistItemDao.TABLENAME,
                BlacklistItemDao.Properties.Notes.columnName, "TEXT");

        LOG.info("onUpgrade() finished");
    }

    /**
     * What happens when a newer database meets an older app: nothing.
     *
     * <p>The default is to refuse, which means the app cannot open its own database any more.
     * An older version reads the columns it knows by name and is none the wiser about the
     * rest, so letting it through costs a column it ignores and saves the whole blacklist.
     */
    @Override
    public void onDowngrade(Database db, int oldVersion, int newVersion) {
        LOG.info("onDowngrade() oldVersion={}, newVersion={}", oldVersion, newVersion);
    }

    /** Adds a column unless the table has it, because adding it twice is an error. */
    private void addColumn(Database db, String table, String column, String type) {
        if (hasColumn(db, table, column)) {
            LOG.debug("addColumn() {}.{} is already there", table, column);
            return;
        }

        LOG.info("addColumn() adding {}.{}", table, column);

        try {
            db.execSQL("ALTER TABLE \"" + table + "\" ADD COLUMN \"" + column + "\" " + type);
        } catch (Exception e) {
            // the entries are still readable without it; losing them would be the worse outcome
            LOG.error("addColumn() couldn't add {}.{}", table, column, e);
        }
    }

    private boolean hasColumn(Database db, String table, String column) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(\"" + table + "\")", null);

            int nameIndex = cursor.getColumnIndex("name");
            if (nameIndex == -1) return false;

            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameIndex))) return true;
            }
        } catch (Exception e) {
            LOG.warn("hasColumn() couldn't read the columns of {}", table, e);
        } finally {
            if (cursor != null) cursor.close();
        }

        return false;
    }

}
