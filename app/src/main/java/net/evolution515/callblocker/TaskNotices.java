package net.evolution515.callblocker;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import net.evolution515.callblocker.event.MainDbDownloadFinishedEvent;
import net.evolution515.callblocker.event.MainDbDownloadingEvent;
import net.evolution515.callblocker.event.SecondaryDbUpdateFinished;
import net.evolution515.callblocker.event.SecondaryDbUpdatingEvent;

/**
 * Says, while the app is open, that the database started or finished being built.
 *
 * <p>The work runs in a service with a notification of its own, which is the right place for
 * it when the app is in the background. Someone who is looking at the app at that moment
 * shouldn't have to pull down the drawer to find out that the button they pressed did
 * something, so the same two moments are said here as well.
 *
 * <p>Nothing is said while no screen of the app is in front: a toast that nobody is there for
 * would land on whatever app is, and the notification has that covered anyway.
 */
public class TaskNotices implements Application.ActivityLifecycleCallbacks {

    /** Starts listening; it lives as long as the app does. */
    public static void install(Application application) {
        TaskNotices notices = new TaskNotices(application);

        application.registerActivityLifecycleCallbacks(notices);
        EventUtils.register(notices);
    }

    private final Context context;

    /** How many screens of the app are between onStart and onStop right now. */
    private int visible;

    private TaskNotices(Context context) {
        this.context = context.getApplicationContext();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloading(MainDbDownloadingEvent event) {
        toast(context.getString(R.string.sources_compiling));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        if (event.cancelled) {
            toast(context.getString(R.string.db_build_cancelled));
            return;
        }

        String error = App.getSettings() != null ? App.getSettings().getLastDbBuildError() : null;

        toast(!TextUtils.isEmpty(error)
                ? context.getString(R.string.db_build_failed) + ": " + error
                : context.getString(R.string.db_build_done));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdating(SecondaryDbUpdatingEvent event) {
        toast(context.getString(R.string.secondary_db_updating));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        toast(context.getString(event.updated
                ? R.string.db_update_done : R.string.db_update_nothing_new));
    }

    private void toast(String text) {
        if (visible <= 0 || TextUtils.isEmpty(text)) return;

        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        visible++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (visible > 0) visible--;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {}

}
