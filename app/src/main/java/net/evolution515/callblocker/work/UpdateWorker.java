package net.evolution515.callblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.evolution515.callblocker.App;
import net.evolution515.callblocker.NotificationHelper;
import net.evolution515.callblocker.R;
import net.evolution515.callblocker.Settings;

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

        /*
         * Quiet about running, never about failing. The switch above is about whether a
         * job that nobody started may announce itself; a job that went wrong is announced
         * whatever the switch says, because a database that stopped updating three weeks
         * ago and never mentioned it is the worst outcome there is.
         */
        try {
            NotificationHelper.showErrors(getApplicationContext(),
                    getApplicationContext().getString(R.string.auto_update_failed_title),
                    new DbUpdater().update());
        } catch (Exception e) {
            LOG.error("doWork() error", e);

            String message = e.getLocalizedMessage();

            NotificationHelper.showError(getApplicationContext(),
                    getApplicationContext().getString(R.string.auto_update_failed_title),
                    message != null && !message.isEmpty()
                            ? e.getClass().getSimpleName() + ": " + message
                            : e.getClass().getSimpleName());
        } finally {
            if (notify) NotificationHelper.hideAutoUpdateNotification(getApplicationContext());
        }

        LOG.info("doWork() finished");
        return Result.success();
    }

}
