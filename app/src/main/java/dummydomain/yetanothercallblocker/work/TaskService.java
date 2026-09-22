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

import java.text.NumberFormat;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.NotificationHelper;
import dummydomain.yetanothercallblocker.PhoneBlockHelper;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.event.DbCompileProgressEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.PhoneBlockUpdateFinishedEvent;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;
import static dummydomain.yetanothercallblocker.EventUtils.postStickyEvent;
import static dummydomain.yetanothercallblocker.EventUtils.removeStickyEvent;

public class TaskService extends IntentService {

    public static final String TASK_DOWNLOAD_MAIN_DB = "download_main_db";
    public static final String TASK_UPDATE_SECONDARY_DB = "update_secondary_db";
    public static final String TASK_UPDATE_PHONE_BLOCK = "update_phone_block";

    /** What started the build, which decides which sources are fetched before it. */
    private static final String EXTRA_TRIGGER = "trigger";

    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);

    /** How often the notification is allowed to say how far along something is. */
    private static final long PROGRESS_INTERVAL_MS = 500;

    /** When it last did. */
    private long lastProgressTime;

    /** What the build is doing, so that a count can say what it is counting. */
    private int phaseTitleResId = R.string.sources_compiling;

    public static void start(Context context, String task) {
        start(context, task, DbCompileService.Trigger.BUILD);
    }

    /**
     * @param trigger what this is: the schedule coming round, someone asking for a build, or
     *                someone asking for one source now
     */
    public static void start(Context context, String task,
                             DbCompileService.Trigger trigger) {
        Intent intent = new Intent(context, TaskService.class);
        intent.setAction(task);
        intent.putExtra(EXTRA_TRIGGER, trigger.name());

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
                        downloadMainDb(triggerOf(intent));
                        break;

                    case TASK_UPDATE_SECONDARY_DB:
                        updateNotification(getString(R.string.secondary_db_updating));
                        updateSecondaryDb();
                        break;

                    case TASK_UPDATE_PHONE_BLOCK:
                        updateNotification(getString(R.string.phone_block_updating));
                        updatePhoneBlock();
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
        updateNotification(title, -1, -1);
    }

    /**
     * Whether it is worth saying again.
     *
     * <p>A database can be a few hundred thousand files, and one notification per file is
     * hundreds of thousands of trips to the system - which takes longer than reading the
     * files does, and gets the app throttled for the trouble. Twice a second is as much as
     * anyone can read anyway; the first step and the last are always shown.
     */
    private boolean progressIsDue(int current, int total) {
        long now = System.currentTimeMillis();

        if (current > 1 && current < total && now - lastProgressTime < PROGRESS_INTERVAL_MS) {
            return false;
        }

        lastProgressTime = now;

        return true;
    }

    /** The same notification, with a bar when there is something to count. */
    private void updateNotification(String title, int current, int total) {
        NotificationHelper.notify(getApplicationContext(),
                NotificationHelper.NOTIFICATION_ID_TASKS,
                NotificationHelper.createServiceNotification(
                        getApplicationContext(), title, current, total));
    }

    /** What the intent says started this, or a plain build when it says nothing. */
    private static DbCompileService.Trigger triggerOf(Intent intent) {
        String name = intent != null ? intent.getStringExtra(EXTRA_TRIGGER) : null;

        try {
            return name != null
                    ? DbCompileService.Trigger.valueOf(name) : DbCompileService.Trigger.BUILD;
        } catch (IllegalArgumentException e) {
            return DbCompileService.Trigger.BUILD;
        }
    }

    /** Builds the database from the sources, in the order the user put them in. */
    private void downloadMainDb(DbCompileService.Trigger trigger) {
        MainDbDownloadingEvent sticky = new MainDbDownloadingEvent();

        DbCompileService.Result result = null;
        String error = null;
        boolean cancelled = false;

        // so that a build that is killed rather than finished can be told about afterwards
        App.getSettings().setDbBuildRunning(true);

        // a cancel asked for before this build began was about the build before it
        DbCompileService.clearCancel();

        postStickyEvent(sticky);
        try {
            result = new DbCompileService(this, App.getSettings())
                    .compile(trigger, new DbCompileService.ProgressListener() {
                        @Override
                        public void onPhase(int titleResId) {
                            // there are four of them in a build; each one is worth saying
                            lastProgressTime = 0;
                            phaseTitleResId = titleResId;

                            updateNotification(getString(titleResId));

                            postEvent(new DbCompileProgressEvent(titleResId, -1, -1));
                        }

                        @Override
                        public void onProgress(int current, int total) {
                            if (!progressIsDue(current, total)) return;

                            updateNotification(getString(R.string.compiling_db, current, total),
                                    current, total);

                            // the same thing, for the screens that are about the database
                            postEvent(new DbCompileProgressEvent(
                                    phaseTitleResId, current, total));
                        }
                    });
        } catch (DbCompileService.Cancelled e) {
            LOG.info("downloadMainDb() the build was cancelled");

            cancelled = true;
        } catch (Throwable e) {
            /*
             * Everything, an OutOfMemoryError included: a build that ends in a message is
             * worth more than an app that disappears, and running out of memory is what a
             * database of a few hundred thousand files does on a small phone.
             *
             * The whole trace goes to the log - that is what a report is read from - and the
             * one line that says what happened goes where the user can see it, in the
             * notification and on the database screen afterwards.
             */
            LOG.error("downloadMainDb() the build failed", e);

            error = describe(e);
        } finally {
            App.getSettings().setDbBuildRunning(false);

            removeStickyEvent(sticky);
        }

        if (cancelled) {
            showBuildCancelled();
        } else {
            showBuildFinished(result, error);
        }

        postEvent(new MainDbDownloadFinishedEvent(
                result != null && result.status == DbCompileService.Status.NO_SOURCES,
                cancelled));
    }

    /**
     * Says that the build stopped because someone said so - which is not an error, and is
     * not "built" either. The database in use is whatever was there before it started.
     */
    private void showBuildCancelled() {
        App.getSettings().setLastDbBuildError("");
        App.getSettings().setLastDbBuildErrorTime(0);

        NotificationHelper.showDbBuildFinished(getApplicationContext(),
                getString(R.string.db_build_cancelled),
                getString(R.string.db_build_cancelled_text), true);
    }

    /** The first of these that says something. */
    private static String first(String... texts) {
        for (String text : texts) {
            if (!TextUtils.isEmpty(text)) return text;
        }

        return null;
    }

    /** The short of it: what went wrong, in one line, for someone who is not reading a log. */
    private static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String message = cause.getLocalizedMessage();

        return TextUtils.isEmpty(message)
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Says how the build went, and leaves it said.
     *
     * <p>The notification the service runs under goes away with the service, so the end of
     * something that takes minutes would otherwise be a notification quietly disappearing.
     */
    private void showBuildFinished(DbCompileService.Result result, String error) {
        String title;
        String text;
        boolean ok = false;

        if (result == null || result.status == DbCompileService.Status.FAILED) {
            title = getString(R.string.db_build_failed);
            text = first(error, result != null ? result.reason : null,
                    getString(R.string.db_build_failed_text));
        } else if (result.status == DbCompileService.Status.NO_SOURCES) {
            title = getString(R.string.db_build_failed);
            text = getString(R.string.sources_none_enabled);
        } else if (result.status == DbCompileService.Status.NO_BASE) {
            title = getString(R.string.db_build_failed);
            text = first(result.reason, getString(R.string.db_build_no_base_text));
        } else {
            long numbers = new NumbersCompiler(this).getCount();

            title = getString(R.string.db_build_done);
            text = numbers >= 0
                    ? getString(R.string.db_build_done_text,
                            NumberFormat.getInstance().format(numbers), result.sources)
                    : getString(R.string.db_build_done_text_plain, result.sources);
            ok = true;
        }

        // the reason outlives the notification: the database screen says it until it is fixed
        App.getSettings().setLastDbBuildError(ok ? "" : text);
        App.getSettings().setLastDbBuildErrorTime(ok ? 0 : System.currentTimeMillis());

        if (!ok) LOG.error("showBuildFinished() the database was not built: {}", text);

        NotificationHelper.showDbBuildFinished(getApplicationContext(), title, text, ok);

        /*
         * Built, but not from everyone: that is a success with part of the story missing,
         * and the part that is missing is exactly what someone has to hear about. So it is
         * said separately, as the error it is, with each source's own reason - which the
         * build log and the source's row now carry in full.
         */
        if (ok && result.failed > 0) {
            NotificationHelper.showError(getApplicationContext(),
                    getString(R.string.db_build_sources_failed_title),
                    getString(R.string.db_build_sources_failed, result.failed,
                            result.sources + result.failed,
                            TextUtils.join("\n", result.failures)));
        }
    }

    private void updateSecondaryDb() {
        // whatever went wrong on the way is said, here as for the run nobody started
        NotificationHelper.showErrors(getApplicationContext(),
                getString(R.string.update_failed_title), new DbUpdater().update());
    }

    private void updatePhoneBlock() {
        PhoneBlockService service = new PhoneBlockService(App.getSettings(),
                YacbHolder.getPhoneBlockList(), YacbHolder.getPhoneBlockPersonalLists());

        PhoneBlockService.Result result = service.update(true);
        service.updatePersonalLists(true);

        // the update may have run into a token that isn't accepted any more
        PhoneBlockHelper.checkTokenIfDue(getApplicationContext(), App.getSettings());

        noteSources(result);

        // asked for by hand, so the answer is owed even if the screen was left meanwhile
        if (result.status == PhoneBlockService.Status.FAILED) {
            NotificationHelper.showError(getApplicationContext(),
                    getString(R.string.phone_block_update_failed_title),
                    getString(R.string.phone_block_update_failed));
        }

        postEvent(new PhoneBlockUpdateFinishedEvent(result));
    }

    /**
     * Writes down on the source what the fetch did.
     *
     * <p>The list keeps its own account of itself, which is what the account screen shows -
     * but the source in the list is a source like any other, and every other one says when it
     * was last fetched and what came of it. Without this it said "never fetched" for as long
     * as it existed, however often it had been.
     */
    private void noteSources(PhoneBlockService.Result result) {
        SourceService sourceService = YacbHolder.getSourceService();
        if (sourceService == null) return;

        boolean ok = result.status != PhoneBlockService.Status.FAILED
                && result.status != PhoneBlockService.Status.NOT_CONFIGURED;

        long now = System.currentTimeMillis();

        for (NumberSource source : sourceService.getSources()) {
            if (source.getType() != NumberSource.Type.PHONE_BLOCK) continue;

            source.setLastCheck(now);

            if (ok) {
                source.setEntries(result.size);
                source.setLastUpdate(now);
                source.setLastResult(getString(R.string.source_result_numbers, result.size));
            } else {
                source.setLastResult(getString(R.string.source_result_failed));
            }

            sourceService.save(source);
        }
    }

}
