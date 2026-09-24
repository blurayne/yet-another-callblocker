package net.evolution515.callblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.evolution515.callblocker.App;
import net.evolution515.callblocker.BackupHelper;

/** Writes the backup into the chosen directory, without anyone asking. */
public class BackupWorker extends Worker {

    private static final Logger LOG = LoggerFactory.getLogger(BackupWorker.class);

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.debug("doWork() started");

        if (!App.getSettings().getAutoBackup()) {
            LOG.info("doWork() the automatic backup is off");
            return Result.success();
        }

        boolean done = BackupHelper.backup(getApplicationContext());

        LOG.info("doWork() finished, done={}", done);

        // a directory that is not there right now may well be there at the next run
        return done ? Result.success() : Result.retry();
    }

}
