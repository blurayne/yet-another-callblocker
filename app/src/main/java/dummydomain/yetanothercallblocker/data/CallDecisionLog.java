package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.Settings;

/**
 * What the app did about the calls it saw: let them through, silenced them or blocked them.
 *
 * <p>Android's call log doesn't keep any of that. A blocked call is logged as blocked, but a
 * silenced one looks exactly like a missed one, and a call that was let through on purpose
 * looks like any other. The app writes down its own decision, so that the call log can say
 * afterwards what happened to a call.
 *
 * <p>The decisions live in the preferences, like the whitelist and for the same reason: they
 * are written while the call comes in, which can be before the phone has been unlocked. Only
 * the last {@value #MAX_ENTRIES} calls are kept - this is a log to look at, not a history.
 */
public class CallDecisionLog {

    /** What the app did about a call. */
    public enum Decision {
        ALLOWED, SILENCED, BLOCKED;

        static Decision byOrdinal(int ordinal) {
            Decision[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        }
    }

    /** One call, as the app decided about it. */
    public static class Entry {
        public final String number;
        public final long time;
        public final Decision decision;

        Entry(String number, long time, Decision decision) {
            this.number = number;
            this.time = time;
            this.decision = decision;
        }
    }

    /** How many calls the app saw, and what it did about them. */
    public static class Stats {
        public int allowed;
        public int silenced;
        public int blocked;

        public int getTotal() {
            return allowed + silenced + blocked;
        }
    }

    private static final int MAX_ENTRIES = 200;

    /**
     * How far the app's record may be from the call in the call log and still be about that
     * call. The app decides as the call comes in, so the two times are seconds apart at most;
     * the window is wide enough for a phone whose clocks disagree a little.
     */
    private static final long MATCH_WINDOW = 5 * 60 * 1000;

    /** The fewest digits a number must have for its form to be worth looking through. */
    private static final int MIN_SIGNIFICANT_DIGITS = 5;

    /** The most a country code may add in front of a number written without one. */
    private static final int MAX_COUNTRY_CODE_LENGTH = 4;

    private static final Logger LOG = LoggerFactory.getLogger(CallDecisionLog.class);

    private final Settings settings;

    public CallDecisionLog(Settings settings) {
        this.settings = settings;
    }

    /** Writes down what the app did about a call. */
    public synchronized void record(String number, long time, Decision decision) {
        if (TextUtils.isEmpty(number) || decision == null) return;

        try {
            List<Entry> entries = getEntries();
            entries.add(0, new Entry(BlacklistUtils.cleanNumber(number), time, decision));

            while (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }

            settings.setCallDecisions(serialize(entries));

            LOG.debug("record() {} call", decision);
        } catch (Exception e) { // a call must not fail over its own bookkeeping
            LOG.warn("record()", e);
        }
    }

    /** The decisions, newest first - the call log matches a whole page of calls against them. */
    public synchronized List<Entry> getEntries() {
        List<Entry> entries = new ArrayList<>();

        String value = settings.getCallDecisions();
        if (TextUtils.isEmpty(value)) return entries;

        try {
            JSONArray array = new JSONArray(value);

            for (int i = 0; i < array.length(); i++) {
                JSONArray entry = array.optJSONArray(i);
                if (entry == null || entry.length() < 3) continue;

                Decision decision = Decision.byOrdinal(entry.optInt(2, -1));
                if (decision == null) continue;

                String number = entry.optString(0);
                if (TextUtils.isEmpty(number)) continue;

                entries.add(new Entry(number, entry.optLong(1), decision));
            }
        } catch (Exception e) {
            LOG.warn("getEntries() couldn't read the decisions", e);
        }

        return entries;
    }

    /**
     * What the app did about the calls it saw since a point in time.
     *
     * <p>It only knows about the calls it still has a record of, so the count says "at least
     * this many" for anyone who gets more than {@value #MAX_ENTRIES} calls in the period.
     */
    public synchronized Stats getStats(long since) {
        Stats stats = new Stats();

        for (Entry entry : getEntries()) {
            if (entry.time < since) continue;

            switch (entry.decision) {
                case BLOCKED: stats.blocked++; break;
                case SILENCED: stats.silenced++; break;
                default: stats.allowed++; break;
            }
        }

        return stats;
    }

    /**
     * What the app did about the call from this number at this time, or null if it didn't see
     * the call (it was before this version, or the app wasn't screening calls then).
     *
     * @param entries the decisions, as {@link #getEntries()} returns them
     */
    public static Decision find(List<Entry> entries, String number, long time) {
        if (entries.isEmpty() || TextUtils.isEmpty(number)) return null;

        String cleanNumber = BlacklistUtils.cleanNumber(number);

        Entry closest = null;
        long closestDistance = 0;

        for (Entry entry : entries) {
            long distance = Math.abs(entry.time - time);
            if (distance > MATCH_WINDOW) continue;

            if (!isSameNumber(entry.number, cleanNumber)) continue;

            if (closest == null || distance < closestDistance) {
                closest = entry;
                closestDistance = distance;
            }
        }

        return closest != null ? closest.decision : null;
    }

    /**
     * Whether two cleaned numbers are the same number. The call may have been logged in a
     * different form than it came in as ("+4922147258578" and "022147258578"), so what only
     * says how the number was written is taken off both before they are compared.
     *
     * <p>What is left may still differ by a country code, and only by that: two numbers that
     * merely end alike ("+4930123456" and "+4940123456") are not the same number.
     */
    private static boolean isSameNumber(String a, String b) {
        if (a.equals(b)) return true;

        String significantA = significant(a);
        String significantB = significant(b);

        if (significantA.equals(significantB)) return !significantA.isEmpty();

        String longer = significantA.length() >= significantB.length() ? significantA : significantB;
        String shorter = significantA.length() >= significantB.length() ? significantB : significantA;

        if (shorter.length() < MIN_SIGNIFICANT_DIGITS) return false;
        if (longer.length() - shorter.length() > MAX_COUNTRY_CODE_LENGTH) return false;

        return longer.endsWith(shorter);
    }

    /** The number without what only says how it was written: the "+", the "00", the trunk "0". */
    private static String significant(String number) {
        if (number.startsWith("+")) {
            number = number.substring(1);
        } else if (number.startsWith("00")) {
            number = number.substring(2);
        }

        return number.startsWith("0") ? number.substring(1) : number;
    }

    private static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();

        for (Entry entry : entries) {
            JSONArray item = new JSONArray();
            item.put(entry.number);
            item.put(entry.time);
            item.put(entry.decision.ordinal());
            array.put(item);
        }

        return array.toString();
    }

}
