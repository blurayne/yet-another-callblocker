package dummydomain.yetanothercallblocker;

import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.sia.model.database.DbManager;
import dummydomain.yetanothercallblocker.utils.SystemUtils;

public class AdvancedSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN_ADVANCED = "screenAdvanced";
    private static final String PREF_COUNTRY_CODES_INFO = "countryCodesInfo";

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
        // an unset URL means the default one, so that is what is shown instead of nothing
        EditTextPreference databaseUrlPref = requirePreference(Settings.PREF_DATABASE_DOWNLOAD_URL);
        databaseUrlPref.setSummaryProvider(
                (Preference.SummaryProvider<EditTextPreference>) preference ->
                        App.getSettings().getDatabaseDownloadUrl());
        databaseUrlPref.setOnBindEditTextListener(editText -> {
            if (TextUtils.isEmpty(editText.getText())) {
                editText.setText(DbManager.DEFAULT_URL);
                editText.setSelection(editText.getText().length());
            }
        });

        Preference blockInLimitedModePref =
                requirePreference(Settings.PREF_BLOCK_IN_LIMITED_MODE);
        if (SystemUtils.isFileBasedEncryptionEnabled()) {
            blockInLimitedModePref.setSummaryProvider(
                    (Preference.SummaryProvider<MultiSelectListPreference>) preference ->
                            getString(R.string.block_in_limited_mode_summary) + ".\n"
                                    + UiUtils.getSummary(requireContext(), preference));
        } else {
            blockInLimitedModePref.setVisible(false);
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

}
