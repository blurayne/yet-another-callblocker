package dummydomain.yetanothercallblocker;

import android.app.Activity;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.utils.DebuggingUtils;
import dummydomain.yetanothercallblocker.utils.FileUtils;
import dummydomain.yetanothercallblocker.utils.SystemUtils;

public class AdvancedSettingsFragment extends BaseSettingsFragment {

    private static final Logger LOG = LoggerFactory.getLogger(AdvancedSettingsFragment.class);

    private static final String PREF_SCREEN_ADVANCED = "screenAdvanced";
    private static final String PREF_COUNTRY_CODES_INFO = "countryCodesInfo";
    private static final String PREF_EXPORT_LOGCAT = "exportLogcat";
    private static final String PREF_SHARE_CRASH_REPORTS = "shareCrashReports";
    private static final String PREF_CATEGORY_LIMITED_MODE = "categoryLimitedMode";
    private static final String PREF_LIMITED_MODE_INFO = "limitedModeInfo";

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN_ADVANCED;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.advanced_preferences;
    }

    @Override
    protected void initScreen() {
        requirePreference(PREF_EXPORT_LOGCAT).setOnPreferenceClickListener(preference -> {
            exportLogcat();
            return true;
        });

        requirePreference(PREF_SHARE_CRASH_REPORTS).setOnPreferenceClickListener(preference -> {
            shareCrashReports();
            return true;
        });

        /*
         * Direct Boot is what the setting below is about, so the explanation of it sits with
         * the setting. Without file based encryption there is no such mode on this phone, and
         * the whole section would only raise questions.
         */
        if (SystemUtils.isFileBasedEncryptionEnabled()) {
            requirePreference(Settings.PREF_BLOCK_IN_LIMITED_MODE).setSummaryProvider(
                    (Preference.SummaryProvider<MultiSelectListPreference>) preference ->
                            getString(R.string.block_in_limited_mode_summary) + ".\n"
                                    + UiUtils.getSummary(requireContext(), preference));

            String limitedModeExplanation = getString(R.string.limited_mode_info_summary)
                    + "\n\n" + getString(R.string.limited_mode_info_option);

            requirePreference(PREF_LIMITED_MODE_INFO).setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(requireActivity())
                        .setTitle(R.string.settings_category_limited_mode)
                        .setMessage(limitedModeExplanation)
                        .setNegativeButton(R.string.back, null)
                        .show();
                return true;
            });
        } else {
            requirePreference(PREF_CATEGORY_LIMITED_MODE).setVisible(false);
        }

        String countryCodesExplanationSummary = getString(R.string.country_codes_info_summary)
                + ". " + getString(R.string.country_codes_info_summary_addition,
                App.getSettings().getCachedAutoDetectedCountryCode());

        Preference countryCodesInfoPreference = requirePreference(PREF_COUNTRY_CODES_INFO);
        countryCodesInfoPreference.setSummary(countryCodesExplanationSummary);
        countryCodesInfoPreference.setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_category_country_codes)
                    .setMessage(countryCodesExplanationSummary)
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        Preference.OnPreferenceChangeListener countryCodeChangeListener
                = (preference, newValue) -> {
            String value = (String) newValue;
            if (TextUtils.isEmpty(value) || Pattern.matches("^[a-zA-Z]{2}$", value)) {
                return true;
            }

            Toast.makeText(requireActivity(), R.string.country_code_incorrect_format,
                    Toast.LENGTH_SHORT).show();
            return false;
        };

        setPrefChangeListener(Settings.PREF_COUNTRY_CODE_OVERRIDE, countryCodeChangeListener);
        setPrefChangeListener(Settings.PREF_COUNTRY_CODE_FOR_REVIEWS_OVERRIDE,
                countryCodeChangeListener);
    }

    /**
     * Hands over what the app wrote down when it last crashed.
     *
     * <p>The log exported above is this run's, and a crash is by definition not in it: an app
     * may only read its own process's log, and the process that crashed is not the one doing
     * the reading. What the app writes for itself when it goes down is the only thing that
     * survives it, and until now there was no way to get at it without adb.
     */
    private void shareCrashReports() {
        Activity activity = requireActivity();

        List<File> reports = DebuggingUtils.listReports(activity);

        if (reports.isEmpty()) {
            Toast.makeText(activity, R.string.share_crash_reports_none, Toast.LENGTH_LONG).show();
            return;
        }

        // the newest few; everything ever written would be a long list of old news
        FileUtils.shareFiles(activity, reports.subList(0, Math.min(reports.size(), 5)));
    }

    /** Puts the log of this run in a file and offers to share it. */
    private void exportLogcat() {
        Activity activity = requireActivity();

        String path = null;
        try {
            path = DebuggingUtils.saveLogcatInCache(activity);
            DebuggingUtils.appendDeviceInfo(path);
        } catch (IOException | InterruptedException e) {
            LOG.warn("exportLogcat()", e);
        }

        if (path != null) {
            FileUtils.shareFile(activity, new File(path));
        }
    }

}
