package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * The number patterns both lists are written in.
 *
 * <p>A pattern is a number with three things allowed in it: {@code *} for any digits (none
 * included), {@code #} for exactly one digit, and {@code {30,40,555}} for one of several
 * alternatives. They are stored with the database's own wildcards ({@code %} and {@code _}),
 * because the blacklist is matched by the database, and shown the way the user writes them.
 */
public class BlacklistUtils {

    /** What a pattern may be made of: digits, wildcards, and groups of alternatives. */
    private static final Pattern BLACKLIST_ITEM_VALID_PATTERN
            = Pattern.compile("\\+?(?:[0-9%_]|\\{[+0-9%_]+(?:,[+0-9%_]+)*})+");

    private static final Pattern PATTERN_CLEANING_PATTERN = Pattern.compile("[^+0-9%_*#{},]");
    private static final Pattern NUMBER_CLEANING_PATTERN = Pattern.compile("[^+0-9]");

    private static final Logger LOG = LoggerFactory.getLogger(BlacklistUtils.class);

    public static String patternToHumanReadable(String pattern) {
        return pattern.replace('%', '*').replace('_', '#');
    }

    public static String patternFromHumanReadable(String pattern) {
        return pattern.replace('*', '%').replace('#', '_');
    }

    public static String cleanPattern(String pattern) {
        return PATTERN_CLEANING_PATTERN.matcher(pattern).replaceAll("");
    }

    public static String cleanNumber(String number) {
        return NUMBER_CLEANING_PATTERN.matcher(number).replaceAll("");
    }

    public static boolean isValidPattern(String pattern) {
        return BLACKLIST_ITEM_VALID_PATTERN.matcher(pattern).matches();
    }

    /**
     * Whether the pattern offers alternatives, which the database can't match.
     *
     * <p>The database matches patterns with {@code LIKE}, which knows {@code %} and {@code _}
     * but nothing about {@code {30,40}} - those are matched in the app instead.
     */
    public static boolean hasAlternatives(String pattern) {
        return !TextUtils.isEmpty(pattern) && pattern.indexOf('{') != -1;
    }

    /** Whether the pattern covers the number, the way the database would match it. */
    public static boolean matches(String pattern, String cleanNumber) {
        if (TextUtils.isEmpty(pattern) || TextUtils.isEmpty(cleanNumber)) return false;

        Pattern compiled = compilePattern(pattern);
        return compiled != null && compiled.matcher(cleanNumber).matches();
    }

    /**
     * Turns a stored pattern into one that can be matched in the app.
     *
     * @return null if the pattern can't be used
     */
    public static Pattern compilePattern(String pattern) {
        StringBuilder builder = new StringBuilder(pattern.length() * 2);

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '%') {
                builder.append(".*"); // any digits, or none
            } else if (c == '_') {
                builder.append('.'); // exactly one
            } else if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end == -1) {
                    LOG.warn("compilePattern() a group isn't closed");
                    return null;
                }

                appendAlternatives(builder, pattern.substring(i + 1, end));

                i = end;
            } else {
                builder.append(Pattern.quote(String.valueOf(c)));
            }
        }

        try {
            return Pattern.compile(builder.toString());
        } catch (Exception e) {
            LOG.warn("compilePattern() couldn't use {}", pattern, e);
            return null;
        }
    }

    /** Turns {@code 30,40,555} into a group that matches any one of them. */
    private static void appendAlternatives(StringBuilder builder, String alternatives) {
        builder.append("(?:");

        boolean first = true;
        for (String alternative : alternatives.split(",", -1)) {
            if (!first) builder.append('|');
            first = false;

            for (int i = 0; i < alternative.length(); i++) {
                char c = alternative.charAt(i);

                // the wildcards work inside a group as well
                if (c == '%') {
                    builder.append(".*");
                } else if (c == '_') {
                    builder.append('.');
                } else {
                    builder.append(Pattern.quote(String.valueOf(c)));
                }
            }
        }

        builder.append(')');
    }

}
