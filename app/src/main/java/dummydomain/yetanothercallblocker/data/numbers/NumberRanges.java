package dummydomain.yetanothercallblocker.data.numbers;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "keep these country codes" into something a database can answer.
 *
 * <p>Numbers are stored as the digits they are made of, so "starts with 49" is not a string
 * comparison but a set of ranges: 49 itself, 490 to 499, 4900 to 4999, and so on up to the
 * fifteen digits a phone number can have. That is a handful of comparisons against the
 * primary key, which is the one thing the table is sorted by.
 */
public class NumberRanges {

    /** What a phone number can have at most, sign and country code included. */
    private static final int MAX_DIGITS = 15;

    private NumberRanges() {
    }

    /** Every range of numbers that starts with these digits, longest number last. */
    public static List<long[]> forPrefix(String prefix) {
        List<long[]> ranges = new ArrayList<>();

        if (prefix == null || prefix.isEmpty()) return ranges;

        long value;
        try {
            value = Long.parseLong(prefix);
        } catch (NumberFormatException e) {
            return ranges;
        }

        for (int digits = prefix.length(); digits <= MAX_DIGITS; digits++) {
            long span = pow10(digits - prefix.length());

            long from = value * span;
            ranges.add(new long[]{from, from + span - 1});
        }

        return ranges;
    }

    /**
     * What is kept, as a condition for a query, or null when everything is.
     *
     * @param prefixes the country codes to keep, without the plus
     * @param shortMaxLength numbers of at most this many digits are kept whatever their
     *                       prefix, or 0 when that doesn't apply
     */
    public static String keepCondition(List<String> prefixes, int shortMaxLength) {
        if (prefixes == null || prefixes.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();

        if (shortMaxLength > 0 && shortMaxLength < MAX_DIGITS) {
            sb.append("number < ").append(pow10(shortMaxLength));
        }

        for (String prefix : prefixes) {
            for (long[] range : forPrefix(prefix)) {
                if (sb.length() != 0) sb.append(" OR ");

                sb.append("number BETWEEN ").append(range[0]).append(" AND ").append(range[1]);
            }
        }

        return sb.length() != 0 ? sb.toString() : null;
    }

    private static long pow10(int exponent) {
        long value = 1;

        for (int i = 0; i < exponent; i++) {
            value *= 10;
        }

        return value;
    }

}
