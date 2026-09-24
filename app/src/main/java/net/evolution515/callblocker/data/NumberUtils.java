package net.evolution515.callblocker.data;

import android.os.Build;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NumberUtils {

    private static final Set<String> HIDDEN_NUMBERS = new HashSet<>(Arrays.asList(
            "-1", "-2", "UNAVAILABLE", "ABSENT NUMBER", "NNN", "PRIVATE NUMBER", "ANONYMOUS"
    ));

    public static boolean isHiddenNumber(String number) {
        if (TextUtils.isEmpty(number) || TextUtils.getTrimmedLength(number) == 0) return true;
        return HIDDEN_NUMBERS.contains(number.toUpperCase(Locale.ENGLISH));
    }

    public static String normalizeNumber(String number, String countryCode) {
        String normalizedNumber = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // TODO: Android 4.* support
            normalizedNumber = PhoneNumberUtils.formatNumberToE164(number, countryCode);
        }

        if (normalizedNumber == null) {
            normalizedNumber = PhoneNumberUtils.stripSeparators(number);
        }

        return normalizedNumber;
    }

    /**
     * The ways the same number can be written, so that an entry saved in one form is found for
     * a call logged in another: "+4922147258578", "004922147258578" and "022147258578" are the
     * same number, but only one of them is what the phone put in the call log.
     *
     * <p>The national form is not guessed from a table of calling codes: the candidates are put
     * back through the normalizer, and a candidate is kept only if it gives this number again.
     *
     * @return the number itself first, then its other forms; all of them cleaned for matching
     */
    public static List<String> getVariants(String number, String normalizedNumber,
                                           String countryCode) {
        List<String> variants = new ArrayList<>(4);

        addVariant(variants, number);

        if (TextUtils.isEmpty(number)) return variants;

        String e164 = normalizedNumber;
        if (TextUtils.isEmpty(e164) || e164.charAt(0) != '+') {
            // the normalizer gave up (or is not available); the "00" form is still unambiguous
            String cleanNumber = BlacklistUtils.cleanNumber(number);
            e164 = cleanNumber.startsWith("00") ? "+" + cleanNumber.substring(2) : null;
        }

        if (TextUtils.isEmpty(e164) || e164.charAt(0) != '+' || e164.length() < 4) return variants;

        addVariant(variants, e164);
        addVariant(variants, "00" + e164.substring(1));

        if (TextUtils.isEmpty(countryCode)) return variants;

        String digits = e164.substring(1);
        for (int i = 1; i <= 3 && i < digits.length(); i++) { // the calling code is 1 to 3 digits
            String national = digits.substring(i);

            // the trunk prefix first: it is the form most phones log
            boolean found = addNationalVariant(variants, "0" + national, e164, countryCode);
            found |= addNationalVariant(variants, national, e164, countryCode);

            if (found) break;
        }

        return variants;
    }

    private static boolean addNationalVariant(List<String> variants, String candidate,
                                              String e164, String countryCode) {
        if (!e164.equals(normalizeNumber(candidate, countryCode))) return false;

        addVariant(variants, candidate);
        return true;
    }

    private static void addVariant(List<String> variants, String number) {
        if (TextUtils.isEmpty(number)) return;

        String cleanNumber = BlacklistUtils.cleanNumber(number);
        if (!TextUtils.isEmpty(cleanNumber) && !variants.contains(cleanNumber)) {
            variants.add(cleanNumber);
        }
    }

}
