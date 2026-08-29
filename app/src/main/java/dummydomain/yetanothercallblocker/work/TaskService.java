package dummydomain.yetanothercallblocker.work;

import android.app.IntentService;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.NotificationHelper;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.data.DbFilteringService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.DbFilterRevertedEvent;
import dummydomain.yetanothercallblocker.event.DbFilteringFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;
import static dummydomain.yetanothercallblocker.EventUtils.postStickyEvent;
import static dummydomain.yetanothercallblocker.EventUtils.removeStickyEvent;

public class TaskService extends IntentService {

    public static final String TASK_DOWNLOAD_MAIN_DB = "download_main_db";
    public static final String TASK_UPDATE_SECONDARY_DB = "update_secondary_db";
    public static final String TASK_FILTER_DB = "filter_db";
    public static final String TASK_REVERT_DB_FILTER = "revert_db_filter";

    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);

    public static void start(Context context, String task) {
        Intent intent = new Intent(context, TaskService.class);
        intent.setAction(task);

        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            // an app targeting Android 12+ can't start a foreground service from the background
            LOG.warn("start() couldn't start the task service", e);
        }
    }

    public TaskService() {
        super(TaskService.class.getSimpleName());
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        String action = intent != null ? intent.getAction() : null;

        startForeground(NotificationHelper.NOTIFICATION_ID_TASKS, createNotification(null));
        try {
            if (!TextUtils.isEmpty(action)) {
                switch (action) {
                    case TASK_DOWNLOAD_MAIN_DB:
                        updateNotification(getString(R.string.main_db_downloading));
                        downloadMainDb();
                        break;

                    case TASK_UPDATE_SECONDARY_DB:
                        updateNotification(getString(R.string.secondary_db_updating));
                        updateSecondaryDb();
                        break;

                    case TASK_FILTER_DB:
                        updateNotification(getString(R.string.filtering_db));
                        filterDb();
                        break;

                    case TASK_REVERT_DB_FILTER:
                        updateNotification(getString(R.string.db_filtering_reverting));
                        revertDbFilter();
                        break;

                    default:
                        LOG.warn("Unknown action: " + action);
                        break;
                }
            }
        } finally {
            stopForeground(true);
        }
    }

    private Notification createNotification(String title) {
        return NotificationHelper.createServiceNotification(getApplicationContext(), title);
    }

    private void updateNotification(String title) {
        NotificationHelper.notify(getApplicationContext(),
                NotificationHelper.NOTIFICATION_ID_TASKS, createNotification(title));
    }

    private void downloadMainDb() {
        MainDbDownloadingEvent sticky = new MainDbDownloadingEvent();

        postStickyEvent(sticky);
        try {
            YacbHolder.getDbManager().downloadMainDb(App.getSettings().getDatabaseDownloadUrl());
            YacbHolder.getCommunityDatabase().reload();
            YacbHolder.getFeaturedDatabase().reload();
            YacbHolder.getSiaMetadata().reload();

            // the downloaded database is unfiltered, so the filter has to be applied again
            updateNotification(getString(R.string.filtering_db));
            new DbFilteringService(App.getSettings()).updateFilter(true);
        } catch (Exception e) {
            LOG.warn("downloadMainDb()", e);
        } finally {
            removeStickyEvent(sticky);
        }

        postEvent(new MainDbDownloadFinishedEvent());
    }

    private void updateSecondaryDb() {
        new DbUpdater().update();
    }

    private void filterDb() {
        postEvent(new DbFilteringFinishedEvent(new DbFilteringService(App.getSettings()).filter()));
    }

    private void revertDbFilter() {
        postEvent(new DbFilterRevertedEvent(
                new DbFilteringService(App.getSettings()).revertToMaster()));
    }

}
