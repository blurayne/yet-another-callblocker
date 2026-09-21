package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.DbImporterExporter;
import dummydomain.yetanothercallblocker.data.SiaConstants;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.utils.FileUtils;
import dummydomain.yetanothercallblocker.work.TaskService;
import dummydomain.yetanothercallblocker.work.UpdateScheduler;

/**
 * The database the sources are built into: what is in it, and the few things one can do to it.
 *
 * <p>There is no main database any more - nothing here is "the" database that was downloaded
 * once. Every number in it came from a source in the list, so the questions this screen
 * answers are how much came from where, how old it is, and how to have it built again.
 */
public class DbManagementSettingsFragment extends BaseSettingsFragment {

    private static final Logger LOG = LoggerFactory.getLogger(DbManagementSettingsFragment.class);

    private static final String PREF_SCREEN_DB_MANAGEMENT = "dbManagement";
    private static final String PREF_STATUS = "dbStatus";
    private static final String PREF_SOURCES = "dbSources";
    private static final String PREF_BUILD = "dbBuild";
    private static final String PREF_UPDATE = "dbUpdate";
    private static final String PREF_AUTO_UPDATE = "autoUpdateEnabled";
    private static final String PREF_NOTIFY_AUTO_UPDATES = "notifyAutoUpdates";
    private static final String PREF_FILTERING = "dbFiltering";
    private static final String PREF_EXPORT = "dbExport";
    private static final String PREF_IMPORT = "dbImport";
    private static final String PREF_RESET_UPDATES = "dbResetUpdates";
    private static final String PREF_DELETE = "dbDelete";

    // 128-133 are taken by the permission helpers and the backup
    private static final int REQUEST_CODE_IMPORT_DB = 140;

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN_DB_MANAGEMENT;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.db_management_preferences;
    }

    private final UpdateScheduler updateScheduler = UpdateScheduler.get(App.getInstance());

    @Override
    protected void initScreen() {
        /*
         * Whether the database keeps itself current is a question about the database, so it
         * is asked here rather than in a list of unrelated switches one screen up.
         */
        SwitchPreferenceCompat autoUpdate = requirePreference(PREF_AUTO_UPDATE);
        autoUpdate.setChecked(updateScheduler.isAutoUpdateScheduled());
        autoUpdate.setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                updateScheduler.scheduleAutoUpdates();
            } else {
                updateScheduler.cancelAutoUpdateWorker();
            }
            return true;
        });

        SwitchPreferenceCompat notifyAutoUpdates = requirePreference(PREF_NOTIFY_AUTO_UPDATES);
        notifyAutoUpdates.setChecked(App.getSettings().getNotifyAutoUpdates());
        notifyAutoUpdates.setOnPreferenceChangeListener((preference, newValue) -> {
            App.getSettings().setNotifyAutoUpdates(Boolean.TRUE.equals(newValue));
            return true;
        });

        requirePreference(PREF_SOURCES).setOnPreferenceClickListener(preference -> {
            startActivity(NumberSourcesActivity.getIntent(requireContext()));
            return true;
        });

        requirePreference(PREF_BUILD).setOnPreferenceClickListener(preference -> {
            // the start and the end of it are said by TaskNotices, wherever it is started
            TaskService.start(requireContext(), TaskService.TASK_DOWNLOAD_MAIN_DB);
            return true;
        });

        requirePreference(PREF_UPDATE).setOnPreferenceClickListener(preference -> {
            TaskService.start(requireContext(), TaskService.TASK_UPDATE_SECONDARY_DB);
            return true;
        });

        requirePreference(PREF_EXPORT).setOnPreferenceClickListener(preference -> {
            exportDb();
            return true;
        });

        requirePreference(PREF_IMPORT).setOnPreferenceClickListener(preference -> {
            pickDbToImport();
            return true;
        });

        requirePreference(PREF_RESET_UPDATES).setOnPreferenceClickListener(preference -> {
            confirm(R.string.db_management_reset_updates, R.string.db_management_reset_updates_message,
                    () -> {
                        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();

                        Toast.makeText(requireContext(), R.string.db_management_reset_updates_done,
                                Toast.LENGTH_SHORT).show();

                        updateStatus();
                    });
            return true;
        });

        requirePreference(PREF_DELETE).setOnPreferenceClickListener(preference -> {
            confirm(R.string.db_management_delete, R.string.db_management_delete_message,
                    this::deleteDb);
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        EventUtils.register(this);

        updateStatus();
    }

    @Override
    public void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        updateStatus();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        updateStatus();
    }

    /** What the database is right now: how big, how old, and where it came from. */
    private void updateStatus() {
        if (!isAdded()) return;

        NumbersCompiler compiler = new NumbersCompiler(requireContext());

        long count = compiler.getCount();
        long compiled = compiler.getCompiledTime();

        List<String> parts = new ArrayList<>(3);

        if (count > 0) {
            parts.add(getString(R.string.db_filtering_status_numbers,
                    NumberFormat.getInstance().format(count)));

            // the file exists either way; its size only says something once it holds numbers
            long size = compiler.getSize() + compiler.getShadowSize();
            if (size > 0) parts.add(Formatter.formatShortFileSize(requireContext(), size));

            if (compiled > 0) {
                parts.add(getString(R.string.db_management_status_built,
                        DateUtils.getRelativeTimeSpanString(compiled, System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS)));
            }
        } else {
            parts.add(getString(R.string.db_management_status_empty));
        }

        String status = TextUtils.join(" · ", parts);

        /*
         * A build that went wrong is the first thing this screen should say: it ran in a
         * service, its notification is long gone, and what is in the database is whatever the
         * build before it left there.
         */
        String error = App.getSettings().getLastDbBuildError();
        if (!TextUtils.isEmpty(error)) {
            long when = App.getSettings().getLastDbBuildErrorTime();

            status += "\n" + getString(R.string.db_management_status_failed,
                    when > 0 ? DateUtils.getRelativeTimeSpanString(when,
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            : getString(R.string.db_management_status_failed_when_unknown),
                    error);
        }

        requirePreference(PREF_STATUS).setSummary(status);

        updateSources();
        updateFiltering(compiler);
    }

    /** What the filter is doing right now, said where the filter lives. */
    private void updateFiltering(NumbersCompiler compiler) {
        List<String> prefixes = DbFilteringUtils.getPrefixesToKeep(App.getSettings());

        requirePreference(PREF_FILTERING).setSummary(compiler.isFiltered() && !prefixes.isEmpty()
                ? getString(R.string.db_filtering_status_filtered,
                        DbFilteringUtils.formatPrefixes(prefixes))
                : getString(R.string.db_filtering_status_not_filtered));
    }

    /**
     * Who contributed what, and when they last did.
     *
     * <p>Read from the sources themselves rather than from the table: each of them keeps what
     * the last build found out about it, so this is the same answer without a query.
     */
    private void updateSources() {
        SourceService sourceService = YacbHolder.getSourceService();

        List<NumberSource> sources = sourceService != null
                ? sourceService.getSources() : new ArrayList<>();

        List<String> lines = new ArrayList<>(sources.size());

        for (NumberSource source : sources) {
            if (!source.isEnabled()) continue;

            StringBuilder line = new StringBuilder(!TextUtils.isEmpty(source.getName())
                    ? source.getName()
                    : getString(NumberSourcesActivity.getTypeName(source.getType())));

            if (source.getEntries() > 0) {
                line.append(": ").append(NumberFormat.getInstance().format(source.getEntries()));
            }

            if (source.getLastUpdate() > 0) {
                line.append(" \u00b7 ").append(DateUtils.getRelativeTimeSpanString(
                        source.getLastUpdate(), System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS));
            } else {
                line.append(" \u00b7 ").append(getString(R.string.source_never_fetched));
            }

            lines.add(line.toString());
        }

        requirePreference(PREF_SOURCES).setSummary(lines.isEmpty()
                ? getString(R.string.db_management_sources_summary)
                : TextUtils.join("\n", lines));
    }

    private void confirm(int title, int message, Runnable action) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
                .setNegativeButton(R.string.back, null)
                .show();
    }

    /** Throws the database away; the sources are what it was built from. */
    private void deleteDb() {
        new NumbersCompiler(requireContext()).clear();

        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();
        YacbHolder.getDbManager().removeMainDb();

        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();

        Toast.makeText(requireContext(), R.string.db_management_delete_done,
                Toast.LENGTH_SHORT).show();

        updateStatus();
    }

    private void pickDbToImport() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_DB);
        } catch (ActivityNotFoundException e) {
            LOG.warn("pickDbToImport()", e);
            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_IMPORT_DB && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();

            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.db_management_import_db)
                    .setMessage(R.string.db_management_import_confirmation)
                    .setPositiveButton(R.string.db_management_import_confirm,
                            (dialog, which) -> startImport(uri))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void exportDb() {
        Toast.makeText(requireContext(), R.string.db_management_exporting,
                Toast.LENGTH_SHORT).show();

        @SuppressLint("StaticFieldLeak") // the export doesn't outlive the screen by much
        AsyncTask<Void, Void, File> task = new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... voids) {
                return writeExport();
            }

            @Override
            protected void onPostExecute(File file) {
                if (!isAdded()) return;

                if (file != null) {
                    FileUtils.shareFile(requireActivity(), file);
                } else {
                    Toast.makeText(requireContext(), R.string.db_management_export_failed,
                            Toast.LENGTH_LONG).show();
                }
            }
        };

        task.execute();
    }

    private File writeExport() {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

        DbImporterExporter.Versions versions = new DbImporterExporter.Versions(
                communityDatabase.getBaseDbVersion(),
                YacbHolder.getSiaSettings().getSecondaryDbVersion());

        File file = new File(requireContext().getCacheDir(), "YetAnotherCallBlocker_db_"
                + communityDatabase.getEffectiveDbVersion() + ".zip");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            if (new DbImporterExporter().export(
                    getDbDir(SiaConstants.SIA_PATH_PREFIX),
                    getDbDir(SiaConstants.SIA_SECONDARY_PATH_PREFIX),
                    versions, outputStream)) {
                return file;
            }
        } catch (Exception e) {
            LOG.warn("writeExport()", e);
        }

        return null;
    }

    private void startImport(Uri uri) {
        Toast.makeText(requireContext(), R.string.db_management_importing,
                Toast.LENGTH_SHORT).show();

        @SuppressLint("StaticFieldLeak") // the import has to finish even if the screen is closed
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return readImport(uri);
            }

            @Override
            protected void onPostExecute(Boolean imported) {
                if (!isAdded()) return;

                Toast.makeText(requireContext(), Boolean.TRUE.equals(imported)
                                ? R.string.db_management_import_result
                                : R.string.db_management_import_failed,
                        Toast.LENGTH_LONG).show();

                updateStatus();
            }
        };

        task.execute();
    }

    private boolean readImport(Uri uri) {
        DbImporterExporter.Versions versions;

        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return false;

            versions = new DbImporterExporter().importDb(inputStream,
                    new File(YacbHolder.getStorage().getDataDirPath()),
                    getDbDirName(SiaConstants.SIA_PATH_PREFIX),
                    getDbDirName(SiaConstants.SIA_SECONDARY_PATH_PREFIX));
        } catch (Exception e) {
            LOG.warn("readImport()", e);
            return false;
        }

        if (versions == null) return false;

        /*
         * The versions have to be stored before the database is loaded: the library resets the
         * secondary database when the loaded base version doesn't match the stored one,
         * which would drop the updates that came with the archive.
         */
        YacbHolder.getSiaSettings().setBaseDbVersion(versions.baseDbVersion);
        YacbHolder.getSiaSettings().setSecondaryDbVersion(versions.secondaryDbVersion);

        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();

        return true;
    }

    private File getDbDir(String pathPrefix) {
        return new File(YacbHolder.getStorage().getDataDirPath(), pathPrefix);
    }

    private static String getDbDirName(String pathPrefix) {
        return pathPrefix.endsWith("/")
                ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;
    }

}
