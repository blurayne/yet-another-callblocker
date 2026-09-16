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
 *
 * <p>Nothing here is allowed to throw: a pattern is read on every call and on every keystroke
 * of the screens that write one, so anything that can't be read is simply not a pattern.
 * For the same reason the only regular expressions built here are character classes, which
 * every regex engine reads the same way - a pattern with braces in it is checked by hand.
 */
public class BlacklistUtils {

    /**
     * The characters a pattern is made of, besides the groups of alternatives.
     *
     * <p>Both notations are accepted: the stored one ({@code %} and {@code _}) and the one the
     * user writes ({@code *} and {@code #}), so that a pattern matches whichever way it got
     * into the list.
     */
    private static final String PATTERN_CHARS = "0123456789%_*#";

    /** The characters a regular expression gives a meaning of its own. */
    private static final String REGEX_SPECIAL_CHARS = "\\^$.|?*+()[]{}";

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

    /**
     * Whether the pattern is one that can be matched: a number, with the wildcards and the
     * groups of alternatives in the places they are allowed.
     */
    public static boolean isValidPattern(String pattern) {
        if (TextUtils.isEmpty(pattern)) return false;

        int index = pattern.charAt(0) == '+' ? 1 : 0; // the country prefix
        boolean anything = false;

        while (index < pattern.length()) {
            char c = pattern.charAt(index);

            if (PATTERN_CHARS.indexOf(c) != -1) {
                anything = true;
                index++;
            } else if (c == '{') {
                int end = pattern.indexOf('}', index);
                if (end == -1) return false; // the group is never closed

                if (!isValidGroup(pattern.substring(index + 1, end))) return false;

                anything = true;
                index = end + 1;
            } else {
                return false;
            }
        }

        return anything;
    }

    /** Whether {@code 30,40,555} is a usable group: alternatives, none of them empty. */
    private static boolean isValidGroup(String group) {
        if (group.isEmpty()) return false;

        int alternativeLength = 0;

        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);

            if (c == ',') {
                if (alternativeLength == 0) return false; // an empty alternative
                alternativeLength = 0;
            } else if (PATTERN_CHARS.indexOf(c) != -1 || c == '+') {
                alternativeLength++;
            } else {
                return false;
            }
        }

        return alternativeLength != 0; // the last alternative isn't empty either
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

    /** Whether the pattern covers the number, with every wildcard the app knows. */
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
        if (TextUtils.isEmpty(pattern)) return null;

        StringBuilder builder = new StringBuilder(pattern.length() * 2);

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end == -1) {
                    LOG.warn("compilePattern() a group isn't closed");
                    return null;
                }

                appendAlternatives(builder, pattern.substring(i + 1, end));

                i = end;
            } else {
                appendChar(builder, c);
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
                appendChar(builder, alternative.charAt(i));
            }
        }

        builder.append(')');
    }

    /** Appends one character of a pattern: a wildcard as one, anything else as itself. */
    private static void appendChar(StringBuilder builder, char c) {
        if (c == '%' || c == '*') {
            builder.append(".*"); // any digits, or none
        } else if (c == '_' || c == '#') {
            builder.append('.'); // exactly one
        } else {
            if (REGEX_SPECIAL_CHARS.indexOf(c) != -1) builder.append('\\');
            builder.append(c);
        }
    }

}
