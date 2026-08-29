package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import dummydomain.yetanothercallblocker.data.DbFilteringService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.DbFilterRevertedEvent;
import dummydomain.yetanothercallblocker.event.DbFilteringFinishedEvent;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.work.TaskService;

import static dummydomain.yetanothercallblocker.Settings.PREF_DB_FILTERING_PREFIXES_TO_KEEP;

public class DbFilteringSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN_DB_FILTERING = "dbFiltering";
    private static final String PREF_INFO = "dbFilteringInfo";
    private static final String PREF_FILTER_DB = "dbFilteringFilterDb";
    private static final String PREF_REVERT_TO_MASTER = "dbFilteringRevertToMaster";

    private final Settings settings = App.getSettings();

    private AsyncTask<Void, Void, List<String>> prefillPrefixesTask;

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN_DB_FILTERING;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.db_filtering_preferences;
    }

    @Override
    protected void initScreen() {
        if (!settings.isDbFilteringPrefixesPrefilled()) {
            settings.setDbFilteringPrefixesPrefilled(true);

            if (TextUtils.isEmpty(settings.getDbFilteringPrefixesToKeep())) {
                startPrefillPrefixesTask();
            }
        }

        requirePreference(PREF_INFO).setOnPreferenceClickListener(pref -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_screen_db_filtering)
                    .setMessage(pref.getSummary())
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        setPrefChangeListener(PREF_DB_FILTERING_PREFIXES_TO_KEEP, (pref, newValue) -> {
            String value = (String) newValue;

            String formattedPrefixes = DbFilteringUtils.formatPrefixes(
                    DbFilteringUtils.parsePrefixes(value));

            if (!TextUtils.equals(formattedPrefixes, value)) {
                ((EditTextPreference) pref).setText(formattedPrefixes);
                return false;
            }

            return true;
        });

        requirePreference(PREF_FILTER_DB).setOnPreferenceClickListener(preference -> {
            updateFilter();
            TaskService.start(requireContext(), TaskService.TASK_FILTER_DB);
            return true;
        });

        requirePreference(PREF_REVERT_TO_MASTER).setOnPreferenceClickListener(preference -> {
            TaskService.start(requireContext(), TaskService.TASK_REVERT_DB_FILTER);
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        EventUtils.register(this);

        updateMasterPreference();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onDbFilteringFinished(DbFilteringFinishedEvent event) {
        updateMasterPreference();

        showMessage(getFilteringMessage(event.result));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onDbFilterReverted(DbFilterRevertedEvent event) {
        updateMasterPreference();

        showMessage(getString(event.reverted
                ? R.string.db_filtering_reverted : R.string.db_filtering_revert_failed));
    }

    private String getFilteringMessage(DbFilteringService.Result result) {
        switch (result.status) {
            case FILTERED:
                return getString(R.string.db_filtering_result_filtered,
                        result.getRemovedEntries(), result.entriesBefore, result.entriesAfter);

            case NOTHING_FILTERED:
                return getString(R.string.db_filtering_result_nothing_filtered,
                        result.entriesBefore);

            case NO_FILTER:
                return getString(R.string.db_filtering_result_no_filter);

            case NO_DATABASE:
                return getString(R.string.db_filtering_result_no_database);

            default:
                return getString(R.string.error);
        }
    }

    private void showMessage(String message) {
        if (!isAdded()) return;

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_screen_db_filtering)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** The unfiltered database can only be restored while a copy of it is kept. */
    private void updateMasterPreference() {
        boolean hasMaster = new DbFilteringService(settings).hasMaster();

        Preference preference = requirePreference(PREF_REVERT_TO_MASTER);
        preference.setEnabled(hasMaster);
        preference.setSummary(hasMaster
                ? R.string.db_filtering_revert_to_master_summary
                : R.string.db_filtering_revert_to_master_summary_unavailable);
    }

    @Override
    public void onStop() {
        EventUtils.unregister(this);

        cancelPrefillPrefixesTask();

        updateFilter();

        super.onStop();
    }

    private void startPrefillPrefixesTask() {
        cancelPrefillPrefixesTask();
        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, List<String>> prefillPrefixesTask = this.prefillPrefixesTask
                = new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... voids) {
                return DbFilteringUtils.detectPrefixes(requireContext(),
                        settings.getCachedAutoDetectedCountryCode());
            }

            @Override
            protected void onPostExecute(List<String> prefixList) {
                if (!prefixList.isEmpty()) {
                    EditTextPreference preference = requirePreference(
                            PREF_DB_FILTERING_PREFIXES_TO_KEEP);

                    if (TextUtils.isEmpty(preference.getText())) {
                        preference.setText(DbFilteringUtils.formatPrefixes(prefixList));
                    }
                }
            }
        };
        prefillPrefixesTask.execute();
    }

    private void cancelPrefillPrefixesTask() {
        if (prefillPrefixesTask != null) {
            prefillPrefixesTask.cancel(true);
            prefillPrefixesTask = null;
        }
    }

    private void updateFilter() {
        YacbHolder.getDbManager().setNumberFilter(DbFilteringUtils.getNumberFilter(settings));
    }

}
