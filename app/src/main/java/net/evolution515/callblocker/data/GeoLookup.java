package net.evolution515.callblocker.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.LruCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Where a number is from: the place its prefix belongs to, and the country.
 *
 * <p>Answered out of {@code assets/geo.db}, which {@code tools/build-geo-db.py} makes from
 * Google's libphonenumber (Apache License 2.0). Nothing is asked of any server: the whole
 * table is in the app, a few megabytes, and a number is one indexed query away.
 *
 * <p>SQLite can't open a file inside the APK, so the asset is copied out once - and again
 * after the app was updated, since the update may have brought newer data.
 *
 * <p>The place comes in German where libphonenumber has a German name for it and the phone
 * speaks German, and in English otherwise; the country is named in the phone's language by
 * the system itself, from its ISO code.
 */
public class GeoLookup {

    private static final Logger LOG = LoggerFactory.getLogger(GeoLookup.class);

    private static final String ASSET = "geo.db";

    /** The longest prefix the data has; nothing past it is worth asking about. */
    private static final int MAX_PREFIX_DIGITS = 12;

    /** Where a number is from, in the parts a screen may want. */
    public static class Origin {

        /** The city or region its prefix belongs to, or null when that isn't known. */
        public final String place;

        /** The country, in the phone's language, or null when that isn't known. */
        public final String country;

        Origin(String place, String country) {
            this.place = place;
            this.country = country;
        }

        /**
         * "Berlin, Deutschland", or whichever of the two is known; null when neither is.
         * A place that is only the country again is said once.
         */
        public String describe() {
            if (!TextUtils.isEmpty(place) && !TextUtils.isEmpty(country)
                    && !place.equalsIgnoreCase(country)) {
                return place + ", " + country;
            }

            if (!TextUtils.isEmpty(country)) return country;

            return !TextUtils.isEmpty(place) ? place : null;
        }

    }

    private static final Origin NONE = new Origin(null, null);

    private final Context context;

    private SQLiteDatabase db;
    private boolean tried;

    /** A call log is the same few dozen numbers over and over. */
    private final LruCache<String, Origin> cache = new LruCache<>(256);

    public GeoLookup(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Where the number is from.
     *
     * @param number preferably in international form ("+4930..."); "0049..." works too, a
     *               national number doesn't - it has no country in it to go by
     * @return never null; its parts are null when they aren't known
     */
    public Origin lookup(String number) {
        String digits = digitsOf(number);
        if (digits == null) return NONE;

        Origin cached = cache.get(digits);
        if (cached != null) return cached;

        Origin origin = query(digits);
        cache.put(digits, origin);

        return origin;
    }

    /** The same, said in one line: "Berlin, Deutschland", "Deutschland", or null. */
    public String describe(String number) {
        return lookup(number).describe();
    }

    /** Copies the data out and opens it, so that the first call doesn't have to. */
    public synchronized void prepare() {
        open();
    }

    /** The table itself, open read-only, for the statistics that walk it; null when missing. */
    public SQLiteDatabase database() {
        return open();
    }

    private synchronized Origin query(String digits) {
        SQLiteDatabase db = open();
        if (db == null) return NONE;

        int count = Math.min(digits.length(), MAX_PREFIX_DIGITS);
        String[] args = new String[count];
        StringBuilder marks = new StringBuilder();

        for (int i = 0; i < count; i++) {
            args[i] = digits.substring(0, i + 1);
            if (i > 0) marks.append(',');
            marks.append('?');
        }

        try {
            String region = null;

            // a longer prefix of the same number is always the larger integer
            try (Cursor cursor = db.rawQuery("SELECT region FROM regions WHERE prefix IN ("
                    + marks + ") ORDER BY prefix DESC LIMIT 1", args)) {
                if (cursor.moveToFirst()) region = cursor.getString(0);
            }

            String column = "de".equals(Locale.getDefault().getLanguage())
                    ? "COALESCE(de, en)" : "en";

            String place = null;

            try (Cursor cursor = db.rawQuery("SELECT (SELECT name FROM names WHERE id = "
                    + column + ") FROM places WHERE prefix IN (" + marks
                    + ") ORDER BY prefix DESC LIMIT 1", args)) {
                if (cursor.moveToFirst()) place = cursor.getString(0);
            }

            String country = null;
            if (!TextUtils.isEmpty(region)) {
                country = new Locale("", region).getDisplayCountry();

                // a code the system has no name for comes back as the code itself
                if (TextUtils.isEmpty(country) || country.equalsIgnoreCase(region)) {
                    country = null;
                }
            }

            return new Origin(place, country);
        } catch (Exception e) {
            LOG.warn("query() failed for {}", digits, e);
            return NONE;
        }
    }

    /** The digits of an international number without its "+", or null for anything else. */
    static String digitsOf(String number) {
        if (TextUtils.isEmpty(number)) return null;

        String text = number.trim();

        if (text.startsWith("+")) {
            text = text.substring(1);
        } else if (text.startsWith("00")) {
            text = text.substring(2);
        } else {
            return null;
        }

        StringBuilder digits = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != ' ' && c != '-' && c != '(' && c != ')' && c != '/' && c != '.') {
                return null;
            }
        }

        // no calling code starts with 0, and fewer than four digits say nothing
        if (digits.length() < 4 || digits.charAt(0) == '0') return null;

        return digits.toString();
    }

    private synchronized SQLiteDatabase open() {
        if (db != null && db.isOpen()) return db;
        if (tried) return null;
        tried = true;

        File file = context.getDatabasePath(ASSET);

        try {
            if (!file.exists() || file.lastModified() < installedAt()) copy(file);

            db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            LOG.info("open() {} is open", file);
        } catch (Exception e) {
            LOG.warn("open() couldn't open {}", file, e);
            db = null;
        }

        return db;
    }

    /** When the app was installed or last updated, which is when the asset can have changed. */
    private long installedAt() {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private void copy(File target) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("couldn't create " + dir);
        }

        File temp = new File(target.getPath() + ".tmp");

        try (InputStream in = context.getAssets().open(ASSET);
             OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];

            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        if (target.exists() && !target.delete()) LOG.warn("copy() couldn't delete {}", target);

        if (!temp.renameTo(target)) throw new IOException("couldn't put " + target + " in place");

        LOG.info("copy() {} copied out of the assets", target);
    }

}
