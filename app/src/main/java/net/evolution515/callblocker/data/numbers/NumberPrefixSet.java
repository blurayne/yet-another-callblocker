package net.evolution515.callblocker.data.numbers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pattern most people write, answered with arithmetic instead of a regular expression.
 *
 * <p>Nearly every filter is "keep these country codes": {@code +49*}, {@code +{31,43,41,49}*},
 * {@code +4915*}. All of them say the same thing - the number starts with one of these digits
 * - and a number is a number, so that question is a division and a comparison rather than a
 * match against text.
 *
 * <p>It matters because of how often it is asked. A community database hands over nine
 * million numbers, and the alternative costs two strings and a matcher for each of them:
 * a minute of a build spent turning longs into text so that a regular expression can turn
 * them back into digits.
 *
 * <p>Nothing here is Android-specific on purpose: what it does can be tried out anywhere.
 */
public final class NumberPrefixSet {

    /** 10^0 to 10^18, which is as far as a long goes. */
    private static final long[] POW10 = new long[19];

    static {
        long value = 1;
        for (int i = 0; i < POW10.length; i++) {
            POW10[i] = value;
            if (i + 1 < POW10.length) value *= 10;
        }
    }

    /** The lengths that occur, in ascending order. */
    private final int[] lengths;

    /** The prefixes of each of those lengths, as numbers. */
    private final long[][] prefixes;

    /**
     * The set a pattern describes, or null when the pattern says something else.
     *
     * <p>Only the shape this can answer: a plus, then either digits or {@code {digits,digits}},
     * then a star and nothing more. Anything else - a star in the middle, a {@code #}, a
     * group that isn't all digits - belongs to the general machinery.
     */
    public static NumberPrefixSet of(String pattern) {
        List<String> parts = prefixesOf(pattern);

        return parts != null ? new NumberPrefixSet(parts) : null;
    }

    /**
     * The prefixes a pattern amounts to, or null when it amounts to none.
     *
     * <p>The one place that decides what counts as a prefix pattern, so that the fast path
     * here and the deciding of which downloaded files are worth keeping never disagree about
     * what a pattern means.
     */
    public static List<String> prefixesOf(String pattern) {
        if (pattern == null) return null;

        String rest = pattern.trim();

        if (!rest.startsWith("+") || !rest.endsWith("*")) return null;

        rest = rest.substring(1, rest.length() - 1);

        if (rest.startsWith("{") && rest.endsWith("}")) {
            rest = rest.substring(1, rest.length() - 1);
        }

        if (rest.isEmpty()) return null;

        List<String> parts = new ArrayList<>();

        for (String part : rest.split(",", -1)) {
            part = part.trim();

            if (part.isEmpty() || part.length() > 18) return null;

            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return null;
            }

            if (!parts.contains(part)) parts.add(part);
        }

        return parts;
    }

    private NumberPrefixSet(List<String> parts) {
        int[] distinct = new int[parts.size()];
        int count = 0;

        for (String part : parts) {
            boolean known = false;
            for (int i = 0; i < count; i++) {
                if (distinct[i] == part.length()) {
                    known = true;
                    break;
                }
            }

            if (!known) distinct[count++] = part.length();
        }

        lengths = Arrays.copyOf(distinct, count);
        Arrays.sort(lengths);

        prefixes = new long[count][];

        for (int i = 0; i < count; i++) {
            List<Long> values = new ArrayList<>();

            for (String part : parts) {
                if (part.length() == lengths[i]) values.add(Long.parseLong(part));
            }

            long[] array = new long[values.size()];
            for (int k = 0; k < array.length; k++) {
                array[k] = values.get(k);
            }

            prefixes[i] = array;
        }
    }

    /**
     * Whether the number starts with one of them.
     *
     * <p>One pass to find out how many digits the number has, then one division per length
     * that occurs - usually exactly one, because country codes in a list tend to be the same
     * length - and a look through a handful of longs.
     */
    public boolean matches(long number) {
        if (number <= 0) return false;

        int digits = digitCount(number);

        for (int i = 0; i < lengths.length; i++) {
            int length = lengths[i];

            // ascending, so once the number is shorter than one of them it is shorter than the rest
            if (digits < length) return false;

            long head = number / POW10[digits - length];

            for (long prefix : prefixes[i]) {
                if (prefix == head) return true;
            }
        }

        return false;
    }

    /** How many digits a positive number is written with. */
    static int digitCount(long number) {
        int digits = 1;

        while (digits < POW10.length && number >= POW10[digits]) digits++;

        return digits;
    }

}
