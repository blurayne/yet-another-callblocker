package net.evolution515.callblocker.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.SparseArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

import net.evolution515.callblocker.data.numbers.NumberFlags;
import net.evolution515.callblocker.data.numbers.NumbersDb;

/**
 * The database by where its numbers are from: countries, the places in a country, and the
 * numbers - companies first - of a country or a place.
 *
 * <p>The two databases are joined here rather than in SQL. The countries are a few hundred
 * prefixes and fit in two arrays; the places are only loaded for the one country that is
 * opened, as sorted arrays too. So a number's country or place is a few binary searches in
 * memory, and counting every number in the table by country is one pass over it - not a
 * query per number, and not a whole place table held in memory on a phone that has little.
 *
 * <p>A country's numbers are read by ranges of the table's own key: every number that starts
 * with 49 is between 49 000 and 49 999, or 490 000 and 499 999, and so on for each length a
 * number can have. So opening Germany reads Germany, not the world.
 */
public class OriginStats {

    private static final Logger LOG = LoggerFactory.getLogger(OriginStats.class);

    /**
     * The most digits a number in the table can have. E.164 says fifteen, but the sources
     * carry longer ones, up to what a long holds.
     */
    private static final int MAX_DIGITS = 19;

    /** The longest place prefix in the data. */
    private static final int MAX_PLACE_DIGITS = 12;

    /** The place id of the numbers no place is known for. */
    public static final int NO_PLACE = 0;

    private static final long[] POW10 = new long[19];

    static {
        POW10[0] = 1;
        for (int i = 1; i < POW10.length; i++) POW10[i] = POW10[i - 1] * 10;
    }

    /** A country, or a place, and how many numbers it has with the rating asked about. */
    public static class Count {
        public final String region;
        public final int placeId;
        public final String name;
        public final long count;

        Count(String region, int placeId, String name, long count) {
            this.region = region;
            this.placeId = placeId;
            this.name = name;
            this.count = count;
        }
    }

    /** A number of a country or place, with the company name when a source had one. */
    public static class Entry {
        public final long number;
        public final String name;
        public final int category;
        public final int score;

        Entry(long number, String name, int category, int score) {
            this.number = number;
            this.name = name;
            this.category = category;
            this.score = score;
        }
    }

    /** The places of one country, as sorted prefixes and the name each one stands for. */
    private static class PlaceIndex {
        final long[] prefixes;
        final int[] ids;

        PlaceIndex(long[] prefixes, int[] ids) {
            this.prefixes = prefixes;
            this.ids = ids;
        }
    }

    /** The country lists are one pass over the whole table: kept per build and rating. */
    private static final Map<String, List<Count>> COUNTRY_CACHE = new HashMap<>();

    private final Context context;
    private final GeoLookup geo;

    private long[] regionPrefixes;
    private String[] regionCodes;

    public OriginStats(Context context, GeoLookup geo) {
        this.context = context.getApplicationContext();
        this.geo = geo;
    }

    /** Every country with numbers of that rating, most first; "" stands for unknown. */
    public List<Count> countries(int rating) {
        SQLiteDatabase numbers = openNumbers();
        if (numbers == null || !loadRegions()) return Collections.emptyList();

        try {
            String key = compiledTime(numbers) + ":" + rating;

            synchronized (COUNTRY_CACHE) {
                List<Count> cached = COUNTRY_CACHE.get(key);
                if (cached != null) return cached;
            }

            Map<String, long[]> counts = new HashMap<>();

            try (Cursor cursor = numbers.rawQuery("SELECT number FROM numbers"
                    + " WHERE (flags & 3) = ? AND (flags & " + NumberFlags.FLAG_DELETED + ") = 0",
                    new String[]{String.valueOf(rating)})) {
                while (cursor.moveToNext()) {
                    String region = regionOf(cursor.getLong(0));
                    if (region == null) region = "";

                    long[] count = counts.get(region);
                    if (count == null) counts.put(region, count = new long[1]);
                    count[0]++;
                }
            }

            List<Count> list = new ArrayList<>(counts.size());
            for (Map.Entry<String, long[]> e : counts.entrySet()) {
                list.add(new Count(e.getKey(), NO_PLACE, countryName(e.getKey()), e.getValue()[0]));
            }
            sortByCount(list);

            synchronized (COUNTRY_CACHE) {
                COUNTRY_CACHE.put(key, list);
            }

            return list;
        } catch (Exception e) {
            LOG.warn("countries() failed", e);
            return Collections.emptyList();
        } finally {
            numbers.close();
        }
    }

    /** The places of a country with numbers of that rating, most first. */
    public List<Count> places(String region, int rating) {
        SQLiteDatabase numbers = openNumbers();
        if (numbers == null || !loadRegions()) return Collections.emptyList();

        try {
            PlaceIndex index = placesOf(region);
            Map<Integer, long[]> counts = new HashMap<>();

            scan(numbers, region, rating, (number, flags, score) -> {
                int id = placeOf(index, number);

                long[] count = counts.get(id);
                if (count == null) counts.put(id, count = new long[1]);
                count[0]++;
            });

            SparseArray<String> names = names(counts.keySet());

            List<Count> list = new ArrayList<>(counts.size());
            for (Map.Entry<Integer, long[]> e : counts.entrySet()) {
                list.add(new Count(region, e.getKey(), names.get(e.getKey()), e.getValue()[0]));
            }
            sortByCount(list);

            return list;
        } catch (Exception e) {
            LOG.warn("places() failed for {}", region, e);
            return Collections.emptyList();
        } finally {
            numbers.close();
        }
    }

    /**
     * The numbers of a country, or of one place in it, strongest rating first.
     *
     * @param placeId   a place, {@link #NO_PLACE} for the numbers without one, or -1 for all
     * @param namedOnly only the numbers a company is known for - unless there are none, in
     *                  which case the strongest numbers are the answer and {@code fellBack}
     *                  says so
     */
    public List<Entry> numbers(String region, int placeId, int rating, boolean namedOnly,
                               int limit, boolean[] fellBack) {
        SQLiteDatabase numbers = openNumbers();
        if (numbers == null || !loadRegions()) return Collections.emptyList();

        try {
            PlaceIndex index = placeId >= 0 ? placesOf(region) : null;

            // the most negative score is the strongest positive rating
            Comparator<Entry> strongest = rating == NumberFlags.RATING_NEGATIVE
                    ? (a, b) -> Integer.compare(b.score, a.score)
                    : (a, b) -> Integer.compare(a.score, b.score);

            if (namedOnly) {
                List<Entry> named = new ArrayList<>();

                // the named ones are a few thousand at most: asked all at once, sorted out here
                try (Cursor cursor = numbers.rawQuery("SELECT m.number, m.name, n.flags, n.score"
                        + " FROM names m JOIN numbers n ON n.number = m.number"
                        + " WHERE (n.flags & 3) = ? AND (n.flags & " + NumberFlags.FLAG_DELETED
                        + ") = 0", new String[]{String.valueOf(rating)})) {
                    while (cursor.moveToNext()) {
                        long number = cursor.getLong(0);

                        if (!region.equals(regionOf(number))) continue;
                        if (index != null && placeOf(index, number) != placeId) continue;

                        named.add(new Entry(number, cursor.getString(1),
                                NumberFlags.getCategory(cursor.getInt(2)), cursor.getInt(3)));
                    }
                }

                if (!named.isEmpty()) {
                    Collections.sort(named, strongest);
                    return named.size() > limit ? named.subList(0, limit) : named;
                }

                if (fellBack != null) fellBack[0] = true;
            }

            // the strongest `limit` of them, without keeping the rest
            PriorityQueue<Entry> top = new PriorityQueue<>(limit + 1,
                    Collections.reverseOrder(strongest));

            scan(numbers, region, rating, (number, flags, score) -> {
                if (index != null && placeOf(index, number) != placeId) return;

                top.add(new Entry(number, null, NumberFlags.getCategory(flags), score));
                if (top.size() > limit) top.poll();
            });

            List<Entry> list = new ArrayList<>(top);
            Collections.sort(list, strongest);

            return withNames(numbers, list);
        } catch (Exception e) {
            LOG.warn("numbers() failed for {} {}", region, placeId, e);
            return Collections.emptyList();
        } finally {
            numbers.close();
        }
    }

    /** The country's name in the phone's language, or "" for an unknown one. */
    public static String countryName(String region) {
        if (region == null || region.isEmpty()) return "";

        String name = new Locale("", region).getDisplayCountry();
        return name != null && !name.isEmpty() ? name : region;
    }

    // ---- the table

    private interface RowVisitor {
        void onRow(long number, int flags, int score);
    }

    /** Every number of the country with that rating, read by key ranges. */
    private void scan(SQLiteDatabase numbers, String region, int rating, RowVisitor visitor) {
        for (int i = 0; i < regionCodes.length; i++) {
            if (!regionCodes[i].equals(region)) continue;

            long prefix = regionPrefixes[i];
            int length = digits(prefix);

            for (int total = length + 1; total <= MAX_DIGITS; total++) {
                long scale = POW10[total - length];

                // past what a long holds there are no numbers, and the bounds would overflow
                if (prefix > Long.MAX_VALUE / scale) break;

                long low = prefix * scale;
                long high = prefix + 1 > Long.MAX_VALUE / scale
                        ? Long.MAX_VALUE : (prefix + 1) * scale - 1;

                try (Cursor cursor = numbers.rawQuery("SELECT number, flags, score FROM numbers"
                        + " WHERE number BETWEEN ? AND ?",
                        new String[]{String.valueOf(low), String.valueOf(high)})) {
                    while (cursor.moveToNext()) {
                        int flags = cursor.getInt(1);

                        if ((flags & 3) != rating || NumberFlags.isDeleted(flags)) continue;

                        long number = cursor.getLong(0);

                        // a longer prefix can belong to another country: +44 1481 is Guernsey
                        if (!region.equals(regionOf(number))) continue;

                        visitor.onRow(number, flags, cursor.getInt(2));
                    }
                }
            }
        }
    }

    private List<Entry> withNames(SQLiteDatabase numbers, List<Entry> list) {
        if (list.isEmpty()) return list;

        StringBuilder marks = new StringBuilder();
        String[] args = new String[list.size()];

        for (int i = 0; i < list.size(); i++) {
            if (i > 0) marks.append(',');
            marks.append('?');
            args[i] = String.valueOf(list.get(i).number);
        }

        Map<Long, String> names = new HashMap<>();

        try (Cursor cursor = numbers.rawQuery("SELECT number, name FROM names WHERE number IN ("
                + marks + ")", args)) {
            while (cursor.moveToNext()) names.put(cursor.getLong(0), cursor.getString(1));
        }

        List<Entry> named = new ArrayList<>(list.size());
        for (Entry e : list) {
            named.add(new Entry(e.number, names.get(e.number), e.category, e.score));
        }

        return named;
    }

    private SQLiteDatabase openNumbers() {
        File file = NumbersDb.getFile(context);
        if (!file.exists()) return null;

        try {
            return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            LOG.warn("openNumbers() couldn't open {}", file, e);
            return null;
        }
    }

    private static String compiledTime(SQLiteDatabase numbers) {
        return NumbersDb.getMeta(numbers, NumbersDb.META_COMPILED, "0");
    }

    // ---- the places

    private synchronized boolean loadRegions() {
        if (regionPrefixes != null) return true;

        SQLiteDatabase db = geo != null ? geo.database() : null;
        if (db == null) return false;

        List<long[]> prefixes = new ArrayList<>();
        List<String> codes = new ArrayList<>();

        try (Cursor cursor = db.rawQuery("SELECT prefix, region FROM regions ORDER BY prefix",
                null)) {
            while (cursor.moveToNext()) {
                prefixes.add(new long[]{cursor.getLong(0)});
                codes.add(cursor.getString(1));
            }
        }

        regionPrefixes = new long[prefixes.size()];
        regionCodes = codes.toArray(new String[0]);
        for (int i = 0; i < regionPrefixes.length; i++) regionPrefixes[i] = prefixes.get(i)[0];

        return true;
    }

    /** The country of a number by its longest prefix, or null. */
    String regionOf(long number) {
        int length = digits(number);

        for (int k = Math.min(length, 8); k >= 1; k--) {
            long prefix = number / POW10[length - k];

            int at = Arrays.binarySearch(regionPrefixes, prefix);
            if (at >= 0) return regionCodes[at];
        }

        return null;
    }

    /** The places whose prefixes lie within the country's, sorted. */
    private PlaceIndex placesOf(String region) {
        SQLiteDatabase db = geo.database();

        String column = "de".equals(Locale.getDefault().getLanguage()) ? "COALESCE(de, en)" : "en";

        List<long[]> rows = new ArrayList<>();

        for (int i = 0; i < regionCodes.length; i++) {
            if (!regionCodes[i].equals(region)) continue;

            long prefix = regionPrefixes[i];
            int length = digits(prefix);

            for (int total = length; total <= MAX_PLACE_DIGITS; total++) {
                long low = prefix * POW10[total - length];
                long high = (prefix + 1) * POW10[total - length] - 1;

                try (Cursor cursor = db.rawQuery("SELECT prefix, " + column
                        + " FROM places WHERE prefix BETWEEN ? AND ?",
                        new String[]{String.valueOf(low), String.valueOf(high)})) {
                    while (cursor.moveToNext()) {
                        rows.add(new long[]{cursor.getLong(0), cursor.getLong(1)});
                    }
                }
            }
        }

        Collections.sort(rows, (a, b) -> Long.compare(a[0], b[0]));

        long[] prefixes = new long[rows.size()];
        int[] ids = new int[rows.size()];

        for (int i = 0; i < prefixes.length; i++) {
            prefixes[i] = rows.get(i)[0];
            ids[i] = (int) rows.get(i)[1];
        }

        return new PlaceIndex(prefixes, ids);
    }

    private static int placeOf(PlaceIndex index, long number) {
        int length = digits(number);

        for (int k = Math.min(length, MAX_PLACE_DIGITS); k >= 1; k--) {
            int at = Arrays.binarySearch(index.prefixes, number / POW10[length - k]);
            if (at >= 0) return index.ids[at];
        }

        return NO_PLACE;
    }

    private SparseArray<String> names(Iterable<Integer> ids) {
        SparseArray<String> names = new SparseArray<>();

        List<String> args = new ArrayList<>();
        for (int id : ids) if (id != NO_PLACE) args.add(String.valueOf(id));

        // a few hundred at a time: SQLite takes only so many parameters
        for (int from = 0; from < args.size(); from += 500) {
            List<String> chunk = args.subList(from, Math.min(args.size(), from + 500));

            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) marks.append(i > 0 ? ",?" : "?");

            try (Cursor cursor = geo.database().rawQuery("SELECT id, name FROM names WHERE id IN ("
                    + marks + ")", chunk.toArray(new String[0]))) {
                while (cursor.moveToNext()) names.put(cursor.getInt(0), cursor.getString(1));
            }
        }

        return names;
    }

    private static int digits(long number) {
        int length = 1;
        while (length < POW10.length && number >= POW10[length]) length++;
        return length;
    }

    private static void sortByCount(List<Count> list) {
        Collections.sort(list, (a, b) -> Long.compare(b.count, a.count));
    }

}
