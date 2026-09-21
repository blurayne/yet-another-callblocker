package dummydomain.yetanothercallblocker.data.numbers;

import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.BlacklistUtils;

/**
 * Which numbers are worth keeping, asked of every number as it arrives.
 *
 * <p>A community database holds nine million numbers from everywhere; a phone in Germany has
 * use for a fraction of them. The question used to be asked of the finished table, which
 * meant writing all nine million down first and deleting most of them afterwards - minutes of
 * work and a few hundred megabytes, for numbers nobody was going to look up.
 *
 * <p>So it is asked here instead, of each number as its source hands it over, and the ones
 * that don't match are never written at all. The same filter applies to every source: what
 * the database is for doesn't change from one source to the next.
 *
 * <p>Nine million times is often enough that how it is asked matters. Nearly every pattern
 * is a set of prefixes - {@code +49*}, {@code +{31,43,41,49}*} - and that question is
 * answered with a division and a comparison, without a string or a matcher in sight. A
 * pattern that says something else falls back to a regular expression, and even then nothing
 * is allocated per number: one buffer and one matcher are filled again and again.
 *
 * <p>Not thread-safe, for that reason. A build is one thread and asks in a loop.
 */
public class NumbersFilter {

    /** The fast answer, for the patterns that are a set of prefixes. Null when it isn't one. */
    private final NumberPrefixSet prefixes;

    /** The general answer, for everything else. Null when the fast one covers it. */
    private final Matcher matcher;

    /** What the number is written into for the matcher, reused rather than made each time. */
    private final StringBuilder buffer;

    /**
     * Numbers below this are kept whatever the pattern says, or 0 when they are not.
     *
     * <p>Short numbers are the emergency and service numbers. They have no country code to
     * match on, so a pattern about country codes would throw all of them away.
     */
    private final long shortNumberLimit;

    /**
     * The filter the settings describe, or null when nothing is being filtered.
     *
     * <p>Null rather than a filter that keeps everything, so that the build can tell the
     * difference and say so.
     */
    public static NumbersFilter of(Settings settings) {
        if (settings == null || !settings.isDbFilteringEnabled()) return null;

        String pattern = settings.getDbFilteringPattern();
        if (TextUtils.isEmpty(pattern)) return null;

        int shortNumberMaxLength = settings.getDbFilteringKeepShortNumbers()
                ? settings.getDbFilteringKeepShortNumbersMaxLength() : 0;

        NumberPrefixSet prefixes = NumberPrefixSet.of(pattern);
        if (prefixes != null) return new NumbersFilter(prefixes, null, shortNumberMaxLength);

        Pattern compiled = BlacklistUtils.compilePattern(
                BlacklistUtils.patternFromHumanReadable(pattern));

        // a pattern that can't be read filters nothing, rather than everything
        if (compiled == null) return null;

        return new NumbersFilter(null, compiled, shortNumberMaxLength);
    }

    private NumbersFilter(NumberPrefixSet prefixes, Pattern pattern, int shortNumberMaxLength) {
        this.prefixes = prefixes;

        this.matcher = pattern != null ? pattern.matcher("") : null;
        this.buffer = pattern != null ? new StringBuilder(24) : null;

        this.shortNumberLimit = shortNumberMaxLength > 0 && shortNumberMaxLength < 18
                ? pow10(shortNumberMaxLength) : 0;
    }

    /**
     * Whether this number belongs in the database.
     *
     * <p>The number is held against the pattern in the form it is written in when it is
     * written in full - {@code +4930123456} - because that is the form that has a country
     * code in it to match on.
     */
    public boolean keep(long number) {
        if (number <= 0) return false;

        // a length test without the length: a number of n digits is smaller than 10^n
        if (shortNumberLimit != 0 && number < shortNumberLimit) return true;

        if (prefixes != null) return prefixes.matches(number);

        buffer.setLength(0);
        buffer.append('+').append(number);

        matcher.reset(buffer);

        return matcher.matches();
    }

    private static long pow10(int exponent) {
        long value = 1;

        for (int i = 0; i < exponent; i++) {
            value *= 10;
        }

        return value;
    }

    /** What it is, for the log and for the screen that sets it. */
    public static String describe(Settings settings) {
        String pattern = settings != null ? settings.getDbFilteringPattern() : null;

        return !TextUtils.isEmpty(pattern) ? pattern : "";
    }

}
