package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import dummydomain.yetanothercallblocker.data.DbImporterExporter;
import dummydomain.yetanothercallblocker.data.SiaConstants;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.utils.FileUtils;
import dummydomain.yetanothercallblocker.work.TaskService;

public class DbManagementActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_IMPORT_DB = 1;

    private static final Logger LOG = LoggerFactory.getLogger(DbManagementActivity.class);

    private AsyncTask<Void, Void, String> dbInfoTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_db_management);

        onDbInfoButtonClick(null);
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelDbInfoTask();

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_IMPORT_DB && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            confirmImport(data.getData());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        setResult(getString(R.string.db_management_update_result,
                YacbHolder.getCommunityDatabase().getEffectiveDbVersion()));
    }

    public void onResetDbClick(View view) {
        clearMessage();

        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();

        YacbHolder.getDbManager().removeMainDb();
        YacbHolder.getCommunityDatabase().reload();
        YacbHolder.getFeaturedDatabase().reload();
        YacbHolder.getSiaMetadata().reload();

        setResult("Database removed");
    }

    public void onResetSecondaryDbClick(View view) {
        clearMessage();

        YacbHolder.getCommunityDatabase().resetSecondaryDatabase();

        setResult("Secondary database removed");
    }

    public void onDbInfoButtonClick(View view) {
        clearMessage();

        startDbInfoTask(null);
    }

    public void onUpdateDbButtonClick(View view) {
        clearMessage();

        TaskService.start(this, TaskService.TASK_UPDATE_SECONDARY_DB);
    }

    public void onExportDbClick(View view) {
        setResult(getString(R.string.db_management_exporting));

        @SuppressLint("StaticFieldLeak") // the task doesn't outlive the export
        AsyncTask<Void, Void, File> task = new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... voids) {
                return exportDb();
            }

            @Override
            protected void onPostExecute(File file) {
                if (file != null) {
                    setResult(getString(R.string.db_management_export_result, file.getName()));
                    FileUtils.shareFile(DbManagementActivity.this, file);
                } else {
                    setResult(getString(R.string.db_management_export_failed));
                }
            }
        };
        task.execute();
    }

    public void onImportDbClick(View view) {
        clearMessage();

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_DB);
        } catch (ActivityNotFoundException e) {
            LOG.warn("onImportDbClick()", e);
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    /** The import replaces the current database, so it's only started if the user confirms it. */
    private void confirmImport(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.db_management_import_db)
                .setMessage(R.string.db_management_import_confirmation)
                .setPositiveButton(R.string.db_management_import_confirm,
                        (dialog, which) -> startImport(uri))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startImport(Uri uri) {
        setResult(getString(R.string.db_management_importing));

        @SuppressLint("StaticFieldLeak") // the import has to finish even if the screen is closed
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return importDb(uri);
            }

            @Override
            protected void onPostExecute(Boolean imported) {
                if (Boolean.TRUE.equals(imported)) {
                    startDbInfoTask(getString(R.string.db_management_import_result));
                } else {
                    setResult(getString(R.string.db_management_import_failed));
                }
            }
        };
        task.execute();
    }

    private File exportDb() {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

        DbImporterExporter.Versions versions = new DbImporterExporter.Versions(
                communityDatabase.getBaseDbVersion(),
                YacbHolder.getSiaSettings().getSecondaryDbVersion());

        File file = new File(getCacheDir(), "YetAnotherCallBlocker_db_"
                + communityDatabase.getEffectiveDbVersion() + ".zip");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            if (new DbImporterExporter().export(
                    getDbDir(SiaConstants.SIA_PATH_PREFIX),
                    getDbDir(SiaConstants.SIA_SECONDARY_PATH_PREFIX),
                    versions, outputStream)) {
                return file;
            }
        } catch (Exception e) {
            LOG.warn("exportDb()", e);
        }

        return null;
    }

    private boolean importDb(Uri uri) {
        DbImporterExporter.Versions versions;

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return false;

            versions = new DbImporterExporter().importDb(inputStream,
                    new File(YacbHolder.getStorage().getDataDirPath()),
                    getDbDirName(SiaConstants.SIA_PATH_PREFIX),
                    getDbDirName(SiaConstants.SIA_SECONDARY_PATH_PREFIX));
        } catch (Exception e) {
            LOG.warn("importDb()", e);
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

    private void startDbInfoTask(String header) {
        cancelDbInfoTask();

        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, String> task = this.dbInfoTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                StringBuilder sb = new StringBuilder();

                if (header != null) sb.append(header).append("\n\n");

                SiaMetadata siaMetadata = YacbHolder.getSiaMetadata();
                CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

                sb.append("DB info:\n");
                sb.append("Operational: ").append(communityDatabase.isOperational()).append('\n');
                sb.append("Base version: ").append(communityDatabase.getBaseDbVersion());
                sb.append(" (SIA: ").append(siaMetadata.getSiaAppVersion()).append(")\n");
                sb.append("Effective version: ").append(communityDatabase.getEffectiveDbVersion()).append('\n');
                sb.append("Last update time: ").append(dateOrNever(App.getSettings().getLastUpdateTime())).append('\n');
                sb.append("Last update check time: ").append(dateOrNever(App.getSettings().getLastUpdateCheckTime())).append('\n');

                FeaturedDatabase featuredDatabase = YacbHolder.getFeaturedDatabase();

                sb.append("\nFeatured DB info:\n");
                sb.append("Operational: ").append(featuredDatabase.isOperational()).append('\n');
                sb.append("Effective version: ").append(featuredDatabase.getBaseDbVersion()).append('\n');

                return sb.toString();
            }

            private String dateOrNever(long time) {
                if (time > 0) return new Date(time).toString();
                return "never";
            }

            @Override
            protected void onPostExecute(String info) {
                setResult(info);
            }
        };
        task.execute();
    }

    private void cancelDbInfoTask() {
        if (dbInfoTask != null) {
            dbInfoTask.cancel(true);
            dbInfoTask = null;
        }
    }

    private void clearMessage() {
        setResult("");
    }

    private void setResult(String result) {
        this.<TextView>findViewById(R.id.debugResultsTextView).setText(result);
    }

}
