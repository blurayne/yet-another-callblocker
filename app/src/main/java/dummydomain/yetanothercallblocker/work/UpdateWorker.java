package dummydomain.yetanothercallblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.NotificationHelper;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.Settings;

public class UpdateWorker extends Worker {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateWorker.class);

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.info("doWork() started");

        Settings settings = App.getSettings();

        // what runs on its own is quiet unless the user asked to be told about it
        boolean notify = settings != null && settings.getNotifyAutoUpdates();

        if (notify) {
            NotificationHelper.showAutoUpdateNotification(getApplicationContext(),
                    getApplicationContext().getString(R.string.secondary_db_updating));
        }

        try {
            new DbUpdater().update();
        } catch (Exception e) {
            LOG.error("doWork() error", e);
        } finally {
            if (notify) NotificationHelper.hideAutoUpdateNotification(getApplicationContext());
        }

        LOG.info("doWork() finished");
        return Result.success();
    }

}
