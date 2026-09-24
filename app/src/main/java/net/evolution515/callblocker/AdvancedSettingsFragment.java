package net.evolution515.callblocker;

import android.app.Activity;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.evolution515.callblocker.utils.DebuggingUtils;
import net.evolution515.callblocker.utils.ExitReasons;
import net.evolution515.callblocker.utils.FileUtils;
import net.evolution515.callblocker.utils.SystemUtils;

public class AdvancedSettingsFragment extends BaseSettingsFragment {

    private static final Logger LOG = LoggerFactory.getLogger(AdvancedSettingsFragment.class);

    private static final String PREF_SCREEN_ADVANCED = "screenAdvanced";
    private static final String PREF_COUNTRY_CODES_INFO = "countryCodesInfo";
    private static final String PREF_VIEW_LOGCAT = "viewLogcat";
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
        requirePreference(PREF_VIEW_LOGCAT).setOnPreferenceClickListener(preference -> {
            startActivity(LogcatActivity.getIntent(requireContext()));
            return true;
        });

        requirePreference(PREF_EXPORT_LOGCAT).setOnPreferenceClickListener(preference -> {
            exportLogcat();
            return true;
        });

        Preference shareCrashReports = requirePreference(PREF_SHARE_CRASH_REPORTS);
        shareCrashReports.setOnPreferenceClickListener(preference -> {
            shareCrashReports();
            return true;
        });

        /*
         * How the app last stopped running, said here rather than only inside the file that
         * gets shared: when it was killed for memory there is no file at all, and that is
         * exactly the case worth knowing about.
         */
        String lastExit = ExitReasons.getLastAbnormal(requireContext());
        if (lastExit != null) {
            shareCrashReports.setSummary(getString(R.string.share_crash_reports_last_exit,
                    lastExit));
        }

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

        List<File> reports = new ArrayList<>(DebuggingUtils.listReports(activity));

        // the newest few; everything ever written would be a long list of old news
        if (reports.size() > 5) reports = new ArrayList<>(reports.subList(0, 5));

        // and how the app stopped running, which is all there is when it was simply killed
        File exitReasons = writeExitReasons(activity);
        if (exitReasons != null) reports.add(exitReasons);

        if (reports.isEmpty()) {
            Toast.makeText(activity, R.string.share_crash_reports_none, Toast.LENGTH_LONG).show();
            return;
        }

        FileUtils.shareFiles(activity, reports);
    }

    /**
     * What the app has to share about its own endings, as a file.
     *
     * <p>Written fresh every time it is asked for: it is the system's answer, not the app's,
     * and it changes without the app being involved.
     */
    private File writeExitReasons(Activity activity) {
        String reasons = ExitReasons.get(activity);
        if (reasons == null) return null;

        File file = new File(activity.getCacheDir(), "exit_reasons.txt");

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), Charset.forName("UTF-8"))) {
            writer.write(reasons);
        } catch (IOException e) {
            LOG.warn("writeExitReasons()", e);
            return null;
        }

        return file;
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
