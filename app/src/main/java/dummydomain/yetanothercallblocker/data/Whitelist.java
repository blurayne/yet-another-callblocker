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
    private List<Pattern> patterns = Collections.emptyList();

    public Whitelist(Settings settings) {
        this.settings = settings;
    }

    /** Whether the number is one the user always wants to hear from. */
    public synchronized boolean matches(String number) {
        if (TextUtils.isEmpty(number)) return false;

        checkParsed();
        if (patterns.isEmpty()) return false;

        String cleanNumber = BlacklistUtils.cleanNumber(number);

        for (Pattern pattern : patterns) {
            if (pattern.matcher(cleanNumber).matches()) {
                LOG.debug("matches() the number is whitelisted");
                return true;
            }
        }

        return false;
    }

    /** The patterns as the user wrote them, for the screens that show them. */
    public static List<String> parse(String value) {
        List<String> result = new ArrayList<>();

        if (TextUtils.isEmpty(value)) return result;

        for (String entry : SEPARATOR.split(value)) {
            entry = BlacklistUtils.cleanPattern(entry.trim());
            if (!entry.isEmpty() && !result.contains(entry)) result.add(entry);
        }

        return result;
    }

    /** Adds a number to the list, keeping what is already there. */
    public static String add(String value, String number) {
        List<String> entries = parse(value);

        String entry = BlacklistUtils.cleanPattern(number);
        if (entry.isEmpty() || entries.contains(entry)) return value;

        entries.add(entry);

        return TextUtils.join("\n", entries);
    }

    private void checkParsed() {
        String value = settings.getWhitelist();
        if (TextUtils.equals(value, source)) return;

        source = value;

        List<Pattern> patterns = new ArrayList<>();
        for (String entry : parse(value)) {
            Pattern pattern = toPattern(BlacklistUtils.patternFromHumanReadable(entry));
            if (pattern != null) patterns.add(pattern);
        }

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
