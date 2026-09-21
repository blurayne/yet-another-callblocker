package dummydomain.yetanothercallblocker.utils;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.CallLogHelper;
import dummydomain.yetanothercallblocker.data.CallLogItem;
import dummydomain.yetanothercallblocker.data.NumberFilter;
import dummydomain.yetanothercallblocker.data.NumberUtils;
import dummydomain.yetanothercallblocker.data.numbers.NumberPrefixSet;

public class DbFilteringUtils {

    /**
     * The filter for the downloaded files, or null when they can't be filtered by name.
     *
     * <p>Null when nothing is being filtered, and also when the pattern says something the
     * file names can't answer - a filter with nothing to keep would throw away every file,
     * which is the opposite of what an unanswerable question should do. The numbers
     * themselves are sorted out as they are read either way.
     */
    public static NumberFilter getNumberFilter(Settings settings) {
        if (!settings.isDbFilteringEnabled()) return null;

        List<String> prefixes = getPrefixesToKeep(settings);
        if (prefixes.isEmpty()) return null;

        return new NumberFilter(prefixes,
                settings.isDbFilteringThorough(),
                settings.getDbFilteringKeepShortNumbers()
                        ? settings.getDbFilteringKeepShortNumbersMaxLength() : 0);
    }

    /**
     * The country codes the pattern keeps, when it is the sort of pattern that has any.
     *
     * <p>The files the database is downloaded as are one per country code, so they can be
     * left out by name - but only when the pattern says something about country codes and
     * nothing else: {@code +49*} and {@code +{49,43}*} do, {@code +4915*} does not, because
     * the file it would be in holds more than what is wanted. When nothing can be said, the
     * files are all kept and the numbers are sorted out one by one as they are read.
     */
    public static List<String> getPrefixesToKeep(Settings settings) {
        return parsePattern(settings.getDbFilteringPattern());
    }

    /** The prefixes a {@code +49*} or {@code +{49,43}*} pattern amounts to, or nothing. */
    public static List<String> parsePattern(String pattern) {
        List<String> prefixes = NumberPrefixSet.prefixesOf(pattern);

        return prefixes != null ? prefixes : Collections.<String>emptyList();
    }

    public static List<String> parsePrefixes(String prefixesString) {
        if (TextUtils.isEmpty(prefixesString)) return Collections.emptyList();

        List<String> prefixList = new ArrayList<>();

        for (String prefix : prefixesString.split("[,;]")) {
            prefix = prefix.replaceAll("[^0-9]", "");
            if (!prefix.isEmpty() && !prefixList.contains(prefix)) prefixList.add(prefix);
        }

        return prefixList;
    }

    public static String formatPrefixes(Collection<String> prefixes) {
        List<String> formattedPrefixes = new ArrayList<>(prefixes.size());

        for (String prefix : prefixes) {
            formattedPrefixes.add("+" + prefix);
        }

        return TextUtils.join(",", formattedPrefixes);
    }

    public static List<String> detectPrefixes(Context context, String countryCode) {
        Set<String> prefixes = new HashSet<>();

        List<CallLogItem> callLogItems = CallLogHelper.loadCalls(context, null, false, 100);

        for (CallLogItem callLogItem : callLogItems) {
            String number = NumberUtils.normalizeNumber(callLogItem.number, countryCode);

            if (number != null && number.startsWith("+") && number.length() > 1) {
                char firstDigit = number.charAt(1);
                if (firstDigit >= '0' && firstDigit <= '9') {
                    prefixes.add(String.valueOf(firstDigit));
                }
            }
        }

        List<String> prefixList = new ArrayList<>(prefixes);
        Collections.sort(prefixList);

        return prefixList;
    }

}
