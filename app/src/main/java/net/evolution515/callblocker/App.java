package net.evolution515.callblocker;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.evolution515.callblocker.data.Config;
import net.evolution515.callblocker.utils.DebuggingUtils;
import net.evolution515.callblocker.utils.ExitReasons;
import net.evolution515.callblocker.work.UpdateScheduler;

public class App extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private static App instance;

    @SuppressLint("StaticFieldLeak")
    private static volatile Settings settings;

    public static App getInstance() {
        return instance;
    }

    public static Settings getSettings() {
        return settings;
    }

    public static void setUiMode(int uiMode) {
        AppCompatDelegate.setDefaultNightMode(uiMode);
    }

    /**
     * Initializes the settings and the services unless it's already done.
     *
     * <p>ContentProviders are created before {@link #onCreate()} is called and may be queried
     * while it's still running, so any component that can be reached that early
     * (see {@link CallerIdDirectoryProvider}) has to ensure the app is initialized.
     */
    public static synchronized void ensureInitialized(Context context) {
        if (settings != null) return;

        Context storageContext = getDeviceProtectedStorageContext(context);

        new DeviceProtectedStorageMigrator().migrate(context);

        Settings newSettings = new Settings(storageContext);
        newSettings.init();

        Config.init(storageContext, newSettings);

        settings = newSettings;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        DebuggingUtils.setUpCrashHandler();

        ensureInitialized(this);

        setUiMode(settings.getUiMode());

        noteInterruptedBuild();

        // what the service says in the drawer is said on screen as well, while there is one
        TaskNotices.install(this);

        if (!settings.getAutoUpdateSetUp()) {
            settings.setAutoUpdateSetUp(true);
            UpdateScheduler.get(this).scheduleAutoUpdates();
        }

        if (settings.getUseMonitoringService()) {
            CallMonitoringService.start(this);
        }
    }

    /**
     * Says that the last build never finished, when it didn't.
     *
     * <p>A build that is killed - for memory, or by the user - writes nothing on its way out:
     * there is no exception, no handler runs, and the next start looks like any other. The
     * marker it sets while it runs is still set here in that case, and the system can say why
     * the process before this one ended, which is the difference between "it ran out of
     * memory" and "something in it is broken".
     */
    private void noteInterruptedBuild() {
        if (!settings.getDbBuildRunning()) return;

        settings.setDbBuildRunning(false);

        String reason = ExitReasons.getLastAbnormal(this);

        String text = getString(R.string.db_build_interrupted)
                + (reason != null ? " \u00b7 " + reason : "");

        /*
         * A phone that doesn't let this app work in the background kills whatever is running
         * the moment the screen goes off, which is the likeliest thing to have happened to a
         * build that took minutes. Saying so here - and once in the drawer, where it can be
         * acted on - is the difference between a mystery and a setting.
         */
        boolean restricted = BackgroundWorkHelper.needsAttention(this);

        if (restricted) {
            text += " \u00b7 " + getString(R.string.background_work_interrupted_hint,
                    BackgroundWorkHelper.getStatus(this));
        }

        LOG.error("noteInterruptedBuild() {}", text);

        settings.setLastDbBuildError(text);
        settings.setLastDbBuildErrorTime(System.currentTimeMillis());

        if (restricted) {
            NotificationHelper.showBackgroundWorkWarning(this,
                    getString(R.string.background_work_warning_text,
                            BackgroundWorkHelper.getStatus(this)));
        }
    }

    private static Context getDeviceProtectedStorageContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createDeviceProtectedStorageContext();
        } else {
            return context;
        }
    }

}
