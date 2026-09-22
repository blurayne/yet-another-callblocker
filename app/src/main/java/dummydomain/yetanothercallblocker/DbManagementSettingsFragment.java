package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
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

import dummydomain.yetanothercallblocker.data.DatabaseBackup;
import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.DbImporterExporter;
import dummydomain.yetanothercallblocker.data.SiaConstants;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.event.DbCompileProgressEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.PhoneBlockUpdateFinishedEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
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
    private static final String PREF_BUILD_LOG = "dbBuildLog";
    private static final String PREF_BUILD = "dbBuild";
    private static final String PREF_UPDATE = "dbUpdate";
    private static final String PREF_AUTO_UPDATE = "autoUpdateEnabled";
    private static final String PREF_NOTIFY_AUTO_UPDATES = "notifyAutoUpdates";
    private static final String PREF_BACKGROUND_WORK = "dbBackgroundWork";
    private static final String PREF_FILTERING = "dbFiltering";
    private static final String PREF_EXPORT = "dbExport";
    private static final String PREF_IMPORT = "dbImport";
    private static final String PREF_DELETE = "dbDelete";

    // 128-133 are taken by the permission helpers and the backup
    private static final int REQUEST_CODE_IMPORT_DB = 140;
    private static final int REQUEST_CODE_BACKUP_DIRECTORY = 141;

    /** What to do once the user has picked a directory to keep the backup in. */
    private boolean backUpAfterPicking;
    private boolean restoreAfterPicking;

    /** Whether a build is running right now, in which case it is what this screen says. */
    private boolean building;

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

        requirePreference(PREF_BUILD_LOG).setOnPreferenceClickListener(preference -> {
            startActivity(LogcatActivity.getBuildLogIntent(requireContext(), null));
            return true;
        });

        requirePreference(PREF_SOURCES).setOnPreferenceClickListener(preference -> {
            startActivity(NumberSourcesActivity.getIntent(requireContext()));
            return true;
        });

        requirePreference(PREF_BACKGROUND_WORK).setOnPreferenceClickListener(preference -> {
            BackgroundWorkHelper.openSettings(requireContext());
            return true;
        });

        requirePreference(PREF_BUILD).setOnPreferenceClickListener(preference -> {
            build();
            return true;
        });

        /*
         * The same build as the one below, asked a different question: only the sources
         * whose schedule has come round are fetched first, and the rest are read as they
         * are. It used to ask the community database's own server what had changed, which
         * is the one source in the list nobody put there.
         */
        requirePreference(PREF_UPDATE).setOnPreferenceClickListener(preference -> {
            BuildStarter.start(requireActivity(), TaskService.TASK_DOWNLOAD_MAIN_DB,
                    DbCompileService.Trigger.SCHEDULED);
            return true;
        });

        requirePreference(PREF_EXPORT).setOnPreferenceClickListener(preference -> {
            backUpDb();
            return true;
        });

        requirePreference(PREF_IMPORT).setOnPreferenceClickListener(preference -> {
            restoreDb();
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

        // a build may have been started from here and be running still
        building = EventUtils.bus().getStickyEvent(MainDbDownloadingEvent.class) != null;

        if (building) {
            requirePreference(PREF_STATUS).setSummary(R.string.sources_compiling);
        }

        updateStatus();

        // it is changed in the system settings, which is somewhere this screen has just been
        requirePreference(PREF_BACKGROUND_WORK)
                .setSummary(BackgroundWorkHelper.getStatus(requireContext()));
    }

    /**
     * Starts a build, after saying what will stop it if anything will.
     *
     * <p>It takes minutes and carries on with the app closed, so a phone that doesn't let
     * this app work in the background will kill it somewhere in the middle - and what that
     * looks like from here is a build that never finishes and never says why.
     */
    private void build() {
        if (!BackgroundWorkHelper.needsAttention(requireContext())) {
            // the start and the end of it are said by TaskNotices, wherever it is started
            BuildStarter.start(requireActivity(), TaskService.TASK_DOWNLOAD_MAIN_DB);
            return;
        }

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.background_work)
                .setMessage(getString(R.string.background_work_before_build,
                        BackgroundWorkHelper.getStatus(requireContext())))
                .setPositiveButton(R.string.background_work_open_settings,
                        (d, w) -> BackgroundWorkHelper.openSettings(requireContext()))
                .setNegativeButton(R.string.background_work_build_anyway, (d, w) ->
                        BuildStarter.start(requireActivity(), TaskService.TASK_DOWNLOAD_MAIN_DB))
                .show();
    }

    @Override
    public void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        building = false;

        updateStatus();
    }

    /**
     * What the build is doing, while it does it.
     *
     * <p>It takes minutes, and every source writes down how it went as it is fetched, so this
     * screen has something new to say every few seconds. Saying "nothing built yet" for all
     * of that is the same as saying nothing is happening.
     */
    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onDbCompileProgress(DbCompileProgressEvent event) {
        building = true;

        if (!isAdded()) return;

        showProgress(event);
        updateSources();
    }

    private void showProgress(DbCompileProgressEvent event) {
        String text = getString(event.titleResId);

        if (event.total > 0) {
            NumberFormat format = NumberFormat.getInstance();

            text += " \u00b7 " + format.format(event.current) + " / " + format.format(event.total);
        }

        requirePreference(PREF_STATUS).setSummary(text);
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        updateStatus();
    }

    /** A list fetched on its own is a source that has just said something about itself. */
    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onPhoneBlockUpdateFinished(PhoneBlockUpdateFinishedEvent event) {
        updateStatus();
    }

    /**
     * What the database is right now: how big, how old, and where it came from.
     *
     * <p>Read on a background thread, because reading it can take a while: while a build is
     * running the table is being written to, and a question put to it waits until it can be
     * answered. On the main thread that wait is the app not responding.
     */
    @SuppressLint("StaticFieldLeak") // the task is short and the screen is checked afterwards
    private void updateStatus() {
        if (!isAdded()) return;

        updateSources(); // the sources say what they say without asking the table

        NumbersCompiler compiler = new NumbersCompiler(requireContext());

        AsyncTask<Void, Void, NumbersCompiler.Info> task
                = new AsyncTask<Void, Void, NumbersCompiler.Info>() {
            @Override
            protected NumbersCompiler.Info doInBackground(Void... voids) {
                return compiler.getInfo();
            }

            @Override
            protected void onPostExecute(NumbersCompiler.Info info) {
                if (isAdded()) showStatus(info);
            }
        };

        task.execute();
    }

    private void showStatus(NumbersCompiler.Info info) {
        updateFiltering(info);

        // a build says what it is doing; what the table held before it started is old news
        if (building) return;

        List<String> parts = new ArrayList<>(3);

        if (info.count > 0) {
            parts.add(getString(R.string.db_filtering_status_numbers,
                    NumberFormat.getInstance().format(info.count)));

            // only when some source brought any; most don't, and "0 names" says nothing
            if (info.names > 0) {
                parts.add(getString(R.string.db_management_status_names,
                        NumberFormat.getInstance().format(info.names)));
            }

            // the file exists either way; its size only says something once it holds numbers
            if (info.size > 0) {
                parts.add(Formatter.formatShortFileSize(requireContext(), info.size));
            }

            if (info.compiledTime > 0) {
                parts.add(getString(R.string.db_management_status_built,
                        DateUtils.getRelativeTimeSpanString(info.compiledTime,
                                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            }
        } else {
            parts.add(getString(info.readable
                    ? R.string.db_management_status_empty
                    : R.string.db_management_status_unreadable));
        }

        String status = TextUtils.join(" \u00b7 ", parts);

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
    }

    /** What the filter is doing right now, said where the filter lives. */
    private void updateFiltering(NumbersCompiler.Info info) {
        Settings settings = App.getSettings();

        requirePreference(PREF_FILTERING).setSummary(settings.isDbFilteringEnabled()
                ? getString(R.string.db_filtering_status_filtered,
                        settings.getDbFilteringPattern())
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

            /*
             * Which version of it is on the phone, when the source says: a database that is
             * published in numbered versions is otherwise impossible to place, and "fetched
             * two days ago" doesn't say whether that fetch brought anything new.
             */
            if (source.getVersion() > 0) {
                line.append(" \u00b7 ").append(getString(R.string.source_version,
                        source.getVersion()));
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

        // the table it was looking numbers up in is the one that was just deleted
        if (YacbHolder.getNumbersLookup() != null) YacbHolder.getNumbersLookup().reload();
        if (YacbHolder.getNumberInfoCache() != null) YacbHolder.getNumberInfoCache().clear();

        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();
        YacbHolder.getDbManager().removeMainDb();

        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();

        Toast.makeText(requireContext(), R.string.db_management_delete_done,
                Toast.LENGTH_SHORT).show();

        updateStatus();
    }

    /**
     * Writes the database into the backup directory, or hands it on when there is none.
     *
     * <p>The backup the app keeps is a directory the user picked, and the database belongs
     * beside the rest of it rather than in a file of its own somewhere else - so when no
     * directory has been picked yet, that is the first thing asked. A phone too old to keep
     * a directory has nowhere to put it and shares the file instead, the way it always did.
     */
    private void backUpDb() {
        if (BackupHelper.canUseDirectory() && BackupHelper.getDirectory() == null) {
            backUpAfterPicking = true;
            pickBackupDirectory();
            return;
        }

        if (BackupHelper.getDirectory() == null) {
            exportDb(); // nowhere to keep it: hand it on and let the user decide where
            return;
        }

        Toast.makeText(requireContext(), R.string.db_management_exporting,
                Toast.LENGTH_SHORT).show();

        Context context = requireContext().getApplicationContext();

        @SuppressLint("StaticFieldLeak") // the application context outlives the task
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                // by hand means now, whatever version the directory already holds
                return BackupHelper.backupDatabase(context, true);
            }

            @Override
            protected void onPostExecute(Boolean done) {
                Toast.makeText(context, Boolean.TRUE.equals(done)
                                ? R.string.db_management_export_done
                                : R.string.db_management_export_failed,
                        Toast.LENGTH_LONG).show();
            }
        };

        task.execute();
    }

    /** Reads the database back: out of the backup directory, or out of a file that is picked. */
    private void restoreDb() {
        DocumentFile file = BackupHelper.findDatabaseBackup(
                requireContext(), BackupHelper.getDirectory());

        if (file == null) {
            if (BackupHelper.canUseDirectory() && BackupHelper.getDirectory() == null) {
                restoreAfterPicking = true;
                pickBackupDirectory();
                return;
            }

            pickDbToImport(); // there is a directory but no database in it
            return;
        }

        Uri uri = file.getUri();

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.db_management_import_db)
                .setMessage(R.string.db_management_import_confirmation)
                .setPositiveButton(R.string.db_management_import_confirm,
                        (dialog, which) -> startImport(uri))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickBackupDirectory() {
        try {
            startActivityForResult(BackupHelper.getPickDirectoryIntent(),
                    REQUEST_CODE_BACKUP_DIRECTORY);
        } catch (ActivityNotFoundException e) {
            LOG.warn("pickBackupDirectory()", e);

            backUpAfterPicking = restoreAfterPicking = false;

            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
        }
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

        if (requestCode == REQUEST_CODE_BACKUP_DIRECTORY) {
            boolean backUp = backUpAfterPicking;
            boolean restore = restoreAfterPicking;

            backUpAfterPicking = restoreAfterPicking = false;

            if (resultCode != android.app.Activity.RESULT_OK || data == null
                    || data.getData() == null
                    || !BackupHelper.keepDirectory(requireContext(), data.getData())) {
                return;
            }

            if (backUp) backUpDb();
            if (restore) restoreDb();

            return;
        }

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

    /** The same file the backup directory would get, for a phone that can't keep a directory. */
    private File writeExport() {
        File file = new File(requireContext().getCacheDir(), BackupHelper.DB_FILE_NAME);

        try (OutputStream outputStream = new FileOutputStream(file)) {
            if (new DatabaseBackup().write(requireContext(), outputStream)) return file;
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

    /**
     * Reads a database back, whichever of the two things it is.
     *
     * <p>What this app writes now is the built table. What it wrote before - and what another
     * phone running an older version would hand over - is the downloaded files. Both are
     * zips, so the one that arrived says which it is and is read accordingly; the stream is
     * opened twice rather than held, because a content URI cannot be wound back.
     */
    private boolean readImport(Uri uri) {
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream != null && new DatabaseBackup().read(requireContext(), inputStream)) {
                /*
                 * The table is the database now: what is looked up comes out of it, and what
                 * the app remembers about numbers it was asked before came out of the one
                 * this just replaced.
                 */
                if (YacbHolder.getNumbersLookup() != null) {
                    YacbHolder.getNumbersLookup().reload();
                }

                if (YacbHolder.getNumberInfoCache() != null) {
                    YacbHolder.getNumberInfoCache().clear();
                }

                YacbHolder.getFeaturedDatabase().reload();
                YacbHolder.getSiaMetadata().reload();

                return true;
            }
        } catch (Exception e) {
            LOG.warn("readImport() reading it as a database backup", e);
        }

        return readFilesImport(uri);
    }

    /** The older shape: the downloaded files, which take the place of the ones on the phone. */
    private boolean readFilesImport(Uri uri) {
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

    private static String getDbDirName(String pathPrefix) {
        return pathPrefix.endsWith("/")
                ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;
    }

}
