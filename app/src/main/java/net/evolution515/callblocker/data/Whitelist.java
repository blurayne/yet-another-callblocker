package net.evolution515.callblocker.data;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import net.evolution515.callblocker.Settings;

/**
 * The numbers that are never blocked, whatever else is known about them.
 *
 * <p>The entries are written the way the blacklist patterns are ({@code *} for any digits,
 * {@code #} for one) and are kept in the settings rather than in the database: a whitelist is a
 * handful of entries, and it has to be readable before the device is unlocked, like everything
 * else a call is decided by.
 */
public class Whitelist {

    /** Named entries first, by name; the rest by pattern - the way the blacklist is ordered. */
    private static final Comparator<WhitelistItem> ORDER = (a, b) -> {
        boolean aNamed = !TextUtils.isEmpty(a.getName());
        boolean bNamed = !TextUtils.isEmpty(b.getName());

        if (aNamed != bNamed) return aNamed ? -1 : 1;

        int result = aNamed
                ? a.getName().compareToIgnoreCase(b.getName()) : 0;

        return result != 0 ? result : a.getPattern().compareTo(b.getPattern());
    };

    private static final String JSON_NAME = "name";
    private static final String JSON_NOTES = "notes";
    private static final String JSON_PATTERN = "pattern";

    /** How the entries were written before they could have names. */
    private static final Pattern LEGACY_SEPARATOR = Pattern.compile("[\\n,;]");

    private static final Logger LOG = LoggerFactory.getLogger(Whitelist.class);

    private final Settings settings;

    private String source;
    private List<WhitelistItem> items = Collections.emptyList();
    private List<Pattern> patterns = Collections.emptyList();

    public Whitelist(Settings settings) {
        this.settings = settings;
    }

    /** Whether the number is one the user always wants to hear from. */
    public boolean matches(String number) {
        return getMatch(number) != null;
    }

    /**
     * The entry that lets the number through, or null if none does.
     *
     * <p>An entry that is the number itself wins over a pattern that merely covers it, so that
     * the screens can say which one applies.
     */
    public synchronized WhitelistItem getMatch(String number) {
        if (TextUtils.isEmpty(number)) return null;

        return getCleanMatch(BlacklistUtils.cleanNumber(number));
    }

    /**
     * The entry that lets the number through, looked up for every form the number can be
     * written in - an entry saved as "+4922147258578" also lets "022147258578" through.
     *
     * @param numberVariants the forms of the number, cleaned, the number itself first
     */
    public synchronized WhitelistItem getMatch(List<String> numberVariants) {
        if (numberVariants == null || numberVariants.isEmpty()) return null;

        for (String number : numberVariants) {
            WhitelistItem item = getCleanMatch(number);
            if (item != null) return item;
        }

        return null;
    }

    private WhitelistItem getCleanMatch(String cleanNumber) {
        if (TextUtils.isEmpty(cleanNumber)) return null;

        checkParsed();
        if (patterns.isEmpty()) return null;

        WhitelistItem match = null;

        for (int i = 0; i < patterns.size(); i++) {
            if (!patterns.get(i).matcher(cleanNumber).matches()) continue;

            WhitelistItem item = items.get(i);
            if (item.isExactly(cleanNumber)) return item; // the number itself

            if (match == null) match = item;
        }

        if (match != null) LOG.debug("getMatch() the number is whitelisted");

        return match;
    }

    /**
     * The entry that covers the number without being it: the rule the number falls under.
     *
     * @param numberVariants the forms of the number, cleaned, the number itself first
     * @return null when nothing covers the number, or when the only entry for it is the
     * number itself
     */
    public synchronized WhitelistItem getRuleMatch(List<String> numberVariants) {
        if (numberVariants == null || numberVariants.isEmpty()) return null;

        checkParsed();

        for (String number : numberVariants) {
            for (int i = 0; i < patterns.size(); i++) {
                WhitelistItem item = items.get(i);
                if (BlacklistUtils.isLiteralPattern(item.getPattern())) continue; // the number

                if (patterns.get(i).matcher(number).matches()) return item;
            }
        }

        return null;
    }

    /** Turns what the user typed into a pattern, or an empty string if nothing is left. */
    public static String normalize(String pattern) {
        if (TextUtils.isEmpty(pattern)) return "";

        return BlacklistUtils.patternToHumanReadable(BlacklistUtils.cleanPattern(pattern.trim()));
    }

    /** The entries, in the order the screens show them. */
    public static List<WhitelistItem> parse(String value) {
        List<WhitelistItem> items = new ArrayList<>();

        if (TextUtils.isEmpty(value)) return items;

        if (value.trim().startsWith("[")) {
            try {
                JSONArray array = new JSONArray(value);

                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.optJSONObject(i);
                    if (entry == null) continue;

                    // an entry written before there were notes simply has none
                    add(items, new WhitelistItem(entry.optString(JSON_NAME),
                            entry.optString(JSON_PATTERN), entry.optString(JSON_NOTES)));
                }
            } catch (Exception e) {
                LOG.error("parse() couldn't read the whitelist", e);
            }
        } else {
            // the entries as they were written before they could have names
            for (String pattern : LEGACY_SEPARATOR.split(value)) {
                add(items, new WhitelistItem(null, pattern));
            }
        }

        Collections.sort(items, ORDER);

        return items;
    }

    /** Turns the entries back into what is kept in the settings. */
    public static String serialize(List<WhitelistItem> items) {
        JSONArray array = new JSONArray();

        for (WhitelistItem item : items) {
            if (item.getPattern().isEmpty()) continue;

            try {
                JSONObject entry = new JSONObject();
                entry.put(JSON_PATTERN, item.getPattern());
                if (!TextUtils.isEmpty(item.getName())) entry.put(JSON_NAME, item.getName());
                if (!TextUtils.isEmpty(item.getNotes())) entry.put(JSON_NOTES, item.getNotes());

                array.put(entry);
            } catch (Exception e) {
                LOG.error("serialize() couldn't write an entry", e);
            }
        }

        return array.length() != 0 ? array.toString() : "";
    }

    /** @return whether the entry was added (it isn't when it's empty or already there) */
    private static boolean add(List<WhitelistItem> items, WhitelistItem item) {
        if (item.getPattern().isEmpty()) return false;

        for (WhitelistItem existing : items) {
            if (existing.getPattern().equals(item.getPattern())) return false;
        }

        return items.add(item);
    }

    private void checkParsed() {
        String value = settings.getWhitelist();
        if (TextUtils.equals(value, source)) return;

        source = value;

        List<WhitelistItem> items = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();

        for (WhitelistItem item : parse(value)) {
            Pattern pattern = BlacklistUtils.compilePattern(
                    BlacklistUtils.patternFromHumanReadable(item.getPattern()));
            if (pattern == null) continue;

            items.add(item);
            patterns.add(pattern);
        }

        this.items = items;
        this.patterns = patterns;

        LOG.debug("checkParsed() {} patterns", patterns.size());
    }

}
