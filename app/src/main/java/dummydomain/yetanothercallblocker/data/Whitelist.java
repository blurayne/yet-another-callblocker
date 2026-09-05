package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.Settings;

/**
 * The numbers that are never blocked, whatever else is known about them.
 *
 * <p>Written the way the blacklist patterns are written ({@code *} for any digits, {@code #} for
 * one), one per line, and kept in the settings rather than in the database: a whitelist is a
 * handful of numbers, and it has to be readable before the device is unlocked, like everything
 * else a call is decided by.
 */
public class Whitelist {

    private static final Pattern SEPARATOR = Pattern.compile("[\\n,;]");

    private static final Logger LOG = LoggerFactory.getLogger(Whitelist.class);

    private final Settings settings;

    private String source;
    private List<String> entries = Collections.emptyList();
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
    public synchronized String getMatch(String number) {
        if (TextUtils.isEmpty(number)) return null;

        checkParsed();
        if (patterns.isEmpty()) return null;

        String cleanNumber = BlacklistUtils.cleanNumber(number);

        String match = null;

        for (int i = 0; i < patterns.size(); i++) {
            if (!patterns.get(i).matcher(cleanNumber).matches()) continue;

            String entry = entries.get(i);
            if (entry.equals(cleanNumber)) return entry; // the number itself

            if (match == null) match = entry;
        }

        if (match != null) LOG.debug("getMatch() the number is whitelisted");

        return match;
    }

    /** Turns what the user typed into an entry, or an empty string if nothing is left. */
    public static String normalize(String entry) {
        if (TextUtils.isEmpty(entry)) return "";

        return BlacklistUtils.patternToHumanReadable(BlacklistUtils.cleanPattern(entry.trim()));
    }

    /** Whether the entry is one that can match something. */
    public static boolean isValid(String entry) {
        return BlacklistUtils.isValidPattern(BlacklistUtils.patternFromHumanReadable(entry));
    }

    /** The entries as they are kept, in order. */
    public static List<String> parse(String value) {
        List<String> result = new ArrayList<>();

        if (TextUtils.isEmpty(value)) return result;

        for (String entry : SEPARATOR.split(value)) {
            entry = normalize(entry);
            if (!entry.isEmpty() && !result.contains(entry)) result.add(entry);
        }

        return result;
    }

    /** The entries in the order the screens show them. */
    public static List<String> parseSorted(String value) {
        List<String> entries = parse(value);
        Collections.sort(entries);
        return entries;
    }

    /** Turns entries back into what is kept in the settings. */
    public static String join(List<String> entries) {
        return TextUtils.join("\n", entries);
    }

    /** Adds an entry, keeping what is already there. */
    public static String add(String value, String entry) {
        List<String> entries = parseSorted(value);

        entry = normalize(entry);
        if (entry.isEmpty() || entries.contains(entry)) return value;

        entries.add(entry);
        Collections.sort(entries);

        return join(entries);
    }

    /** Removes an entry, if it is there. */
    public static String remove(String value, String entry) {
        List<String> entries = parseSorted(value);

        if (!entries.remove(normalize(entry))) return value;

        return join(entries);
    }

    /** Replaces an entry with another one (the same one edited, usually). */
    public static String replace(String value, String oldEntry, String newEntry) {
        List<String> entries = parseSorted(value);

        entries.remove(normalize(oldEntry));

        newEntry = normalize(newEntry);
        if (!newEntry.isEmpty() && !entries.contains(newEntry)) entries.add(newEntry);

        Collections.sort(entries);

        return join(entries);
    }

    /** Whether the entry is there as it is (rather than covered by a pattern). */
    public static boolean contains(String value, String entry) {
        return parse(value).contains(normalize(entry));
    }

    private void checkParsed() {
        String value = settings.getWhitelist();
        if (TextUtils.equals(value, source)) return;

        source = value;

        List<String> entries = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();

        for (String entry : parse(value)) {
            Pattern pattern = toPattern(BlacklistUtils.patternFromHumanReadable(entry));
            if (pattern == null) continue;

            entries.add(entry);
            patterns.add(pattern);
        }

        this.entries = entries;
        this.patterns = patterns;

        LOG.debug("checkParsed() {} patterns", patterns.size());
    }

    /** Turns a pattern into one that can be matched here, the way the database matches them. */
    private static Pattern toPattern(String likePattern) {
        StringBuilder builder = new StringBuilder(likePattern.length() * 2);

        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);

            if (c == '%') {
                builder.append(".*"); // any digits, or none
            } else if (c == '_') {
                builder.append('.'); // exactly one
            } else {
                builder.append(Pattern.quote(String.valueOf(c)));
            }
        }

        try {
            return Pattern.compile(builder.toString());
        } catch (Exception e) {
            LOG.warn("toPattern() couldn't use {}", likePattern, e);
            return null;
        }
    }

}
