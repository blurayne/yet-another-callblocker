package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.NumberFormat;
import java.util.List;

import dummydomain.yetanothercallblocker.data.BlacklistUtils;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.work.TaskService;

/**
 * What of the database is worth keeping.
 *
 * <p>Nothing here changes the database that is already there: the filter is asked about
 * every number as it is read, while the database is being built, so what is set here takes
 * effect the next time it is built - which is what the button at the bottom is for. Nothing
 * is filtered out of a finished database and nothing is kept aside to undo it with.
 */
public class DbFilteringSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN_DB_FILTERING = "dbFiltering";
    private static final String PREF_STATUS = "dbFilteringStatus";
    private static final String PREF_INFO = "dbFilteringInfo";
    private static final String PREF_REBUILD = "dbFilteringRebuild";

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
        /*
         * The pattern has a default that suits the phone this was written for; the call log
         * knows better, so it is asked once and what it says is offered instead.
         */
        if (!settings.isDbFilteringPrefixesPrefilled()) {
            settings.setDbFilteringPrefixesPrefilled(true);

            startPrefillPrefixesTask();
        }

        requirePreference(Settings.PREF_DB_FILTERING_PATTERN).setSummary(
                getString(R.string.db_filtering_pattern_summary,
                        Settings.DEFAULT_DB_FILTERING_PATTERN));

        requirePreference(PREF_INFO).setOnPreferenceClickListener(pref -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_screen_db_filtering)
                    .setMessage(pref.getSummary())
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        setPrefChangeListener(Settings.PREF_DB_FILTERING_ENABLED, (pref, newValue) -> {
            // the value is written after this returns, so the status is read after that
            requireView().post(this::updateStatusPreference);
            return true;
        });

        setPrefChangeListener(Settings.PREF_DB_FILTERING_PATTERN, (pref, newValue) -> {
            String value = ((String) newValue).trim();

            // a pattern that can't be read would quietly filter nothing at all
            if (!TextUtils.isEmpty(value) && BlacklistUtils.compilePattern(
                    BlacklistUtils.patternFromHumanReadable(value)) == null) {
                Toast.makeText(requireContext(), R.string.db_filtering_pattern_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }

            requireView().post(this::updateStatusPreference);

            return true;
        });

        /*
         * The only way a changed filter reaches the database: it is asked as each source is
         * read, so the database has to be read again for it to make any difference.
         */
        requirePreference(PREF_REBUILD).setOnPreferenceClickListener(preference -> {
            updateFilter();

            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.db_filtering_rebuild)
                    .setMessage(R.string.db_filtering_rebuild_confirm)
                    .setPositiveButton(R.string.db_filtering_rebuild, (dialog, which) ->
                            BuildStarter.start(requireActivity(),
                                    TaskService.TASK_DOWNLOAD_MAIN_DB))
                    .setNegativeButton(R.string.back, null)
                    .show();

            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        EventUtils.register(this);

        updateStatusPreference();
    }

    /** A build has just been through the filter, so what the status says has changed. */
    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onDbBuildFinished(MainDbDownloadFinishedEvent event) {
        updateStatusPreference();
    }

    /** What the database is right now, in a line: what is kept, and how much of it there is. */
    @SuppressLint("StaticFieldLeak") // a short read, and the screen is checked afterwards
    private void updateStatusPreference() {
        if (!isAdded()) return;

        /*
         * How many numbers are in there is asked of the table, and while a build is running
         * the table answers when it can - which the main thread cannot wait for without the
         * app looking like it has stopped.
         */
        NumbersCompiler compiler = new NumbersCompiler(requireContext());

        AsyncTask<Void, Void, Long> task = new AsyncTask<Void, Void, Long>() {
            @Override
            protected Long doInBackground(Void... voids) {
                return compiler.getCount();
            }

            @Override
            protected void onPostExecute(Long count) {
                if (isAdded()) showStatus(count != null ? count : -1);
            }
        };

        task.execute();
    }

    private void showStatus(long count) {
        String state;
        if (!settings.isDbFilteringEnabled()) {
            state = getString(R.string.db_filtering_status_not_filtered);
        } else if (TextUtils.isEmpty(settings.getDbFilteringPattern())) {
            state = getString(R.string.db_filtering_status_nothing_set);
        } else {
            state = getString(R.string.db_filtering_status_filtered,
                    settings.getDbFilteringPattern());
        }

        StringBuilder summary = new StringBuilder(state);

        // how many numbers are actually in there, which is what filtering is about
        if (count >= 0) {
            summary.append(" · ").append(getString(R.string.db_filtering_status_numbers,
                    NumberFormat.getInstance().format(count)));
        }

        requirePreference(PREF_STATUS).setSummary(summary.toString());
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
                if (!isAdded() || prefixList.isEmpty()) return;

                EditTextPreference preference
                        = requirePreference(Settings.PREF_DB_FILTERING_PATTERN);

                // only when nothing has been set by hand; the default is not "set by hand"
                if (!TextUtils.isEmpty(preference.getText())) return;

                preference.setText(prefixList.size() == 1
                        ? "+" + prefixList.get(0) + "*"
                        : "+{" + TextUtils.join(",", prefixList) + "}*");

                updateStatusPreference();
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

    /**
     * What the library is told to leave out while downloading.
     *
     * <p>Not the same filter: this one is asked about whole files by their names, so that a
     * database that is only wanted for two countries isn't downloaded in full. The numbers
     * themselves are sorted out as the table is built.
     */
    private void updateFilter() {
        YacbHolder.getDbManager().setNumberFilter(DbFilteringUtils.getNumberFilter(settings));
    }

}
