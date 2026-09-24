package net.evolution515.callblocker;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import net.evolution515.callblocker.data.DbCompileService;
import net.evolution515.callblocker.event.MainDbDownloadingEvent;
import net.evolution515.callblocker.work.TaskService;

/**
 * Starts a build, an update or a fetch - and asks first when one is already running.
 *
 * <p>The service runs its tasks one after the other, so a second build pressed while the
 * first is still going would simply wait its turn and then do the whole thing again. That
 * is seldom what anyone meant: they meant "not that one, this one", or they pressed the
 * button twice. So when a build is running, the choice is put to them - stop it, or stop it
 * and run the new one in its place - rather than quietly queueing minutes of work.
 */
public class BuildStarter {

    private BuildStarter() {
    }

    /** Whether the database is being built right now. */
    public static boolean isBuilding() {
        return EventUtils.bus().getStickyEvent(MainDbDownloadingEvent.class) != null;
    }

    /**
     * Starts the task, or asks what to do about the one that is running.
     *
     * @param activity where the question is asked
     */
    public static void start(Activity activity, String task, DbCompileService.Trigger trigger) {
        start(activity, task, trigger, null);
    }

    /**
     * @param afterwards run once something was done - started, replaced or stopped - and not
     *                   when the question was answered with "back"; for a screen that closes
     *                   after starting, which must not close while the question is open
     */
    public static void start(Activity activity, String task, DbCompileService.Trigger trigger,
                             Runnable afterwards) {
        if (!isBuilding()) {
            TaskService.start(activity, task, trigger);
            done(activity, task, afterwards);
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.build_running_title)
                .setMessage(R.string.build_running_message)
                .setPositiveButton(R.string.build_running_replace, (d, w) -> {
                    // the new one is queued behind the old one, which stops within a moment
                    DbCompileService.requestCancel();
                    TaskService.start(activity, task, trigger);
                    done(activity, task, afterwards);
                })
                .setNeutralButton(R.string.build_running_stop, (d, w) -> {
                    DbCompileService.requestCancel();
                    done(activity, task, afterwards);
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    public static void start(Activity activity, String task) {
        start(activity, task, DbCompileService.Trigger.BUILD);
    }

    /**
     * Opens the build log on what was just set going, so that it can be watched as it
     * happens - the log follows its end by itself. Only for the database: the PhoneBlock
     * list is fetched on its own and says how it went in a toast.
     */
    private static void done(Activity activity, String task, Runnable afterwards) {
        if (TaskService.TASK_DOWNLOAD_MAIN_DB.equals(task)) {
            activity.startActivity(LogcatActivity.getBuildLogIntent(activity, null));
        }

        if (afterwards != null) afterwards.run();
    }

}
