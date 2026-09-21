package dummydomain.yetanothercallblocker.data.numbers;

import android.text.TextUtils;

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
 * that don't match are never written at all. The same pattern applies to every source: what
 * the database is for doesn't change from one source to the next.
 */
public class NumbersFilter {

    /** The pattern every number is held against, as a regular expression. */
    private final Pattern pattern;

    /**
     * Short numbers are kept whatever the pattern says, up to this many digits, or 0 when
     * they are not. They are the emergency and service numbers, which have no country code to
     * match and are worth knowing about wherever one is.
     */
    private final int shortNumberMaxLength;

    /**
     * The filter the settings describe, or null when nothing is being filtered.
     *
     * <p>Null rather than a filter that keeps everything, so that the build can tell the
     * difference and say so.
     */
    public static NumbersFilter of(Settings settings) {
        if (settings == null || !settings.isDbFilteringEnabled()) return null;

        Pattern pattern = BlacklistUtils.compilePattern(BlacklistUtils.patternFromHumanReadable(
                settings.getDbFilteringPattern()));

        if (pattern == null) return null; // a pattern that can't be read filters nothing

        return new NumbersFilter(pattern, settings.getDbFilteringKeepShortNumbers()
                ? settings.getDbFilteringKeepShortNumbersMaxLength() : 0);
    }

    public NumbersFilter(Pattern pattern, int shortNumberMaxLength) {
        this.pattern = pattern;
        this.shortNumberMaxLength = shortNumberMaxLength;
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

        String digits = Long.toString(number);

        if (shortNumberMaxLength > 0 && digits.length() <= shortNumberMaxLength) return true;

        return pattern.matcher("+" + digits).matches();
    }

    /** What it is, for the log and for the screen that sets it. */
    public static String describe(Settings settings) {
        String pattern = settings != null ? settings.getDbFilteringPattern() : null;

        return !TextUtils.isEmpty(pattern) ? pattern : "";
    }

}
