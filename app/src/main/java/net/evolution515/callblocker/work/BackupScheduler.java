package net.evolution515.callblocker.work;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * When the automatic backup runs: once a day, and only while the phone has something to spare.
 *
 * <p>A backup that says what the last one said is not written, so a daily run costs nothing
 * on the days nothing changed.
 */
public class BackupScheduler {

    private static final String BACKUP_WORK_NAME = "automaticBackupWork";

    private static final Logger LOG = LoggerFactory.getLogger(BackupScheduler.class);

    private final Context context;

    public static BackupScheduler get(Context context) {
        return new BackupScheduler(context);
    }

    private BackupScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    public void schedule() {
        LOG.debug("schedule()");

        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(BackupWorker.class, 1, TimeUnit.DAYS)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public void cancel() {
        LOG.debug("cancel()");

        WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME);
    }

}
