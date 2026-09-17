package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

import dummydomain.yetanothercallblocker.data.Config;
import dummydomain.yetanothercallblocker.utils.DebuggingUtils;
import dummydomain.yetanothercallblocker.work.UpdateScheduler;

public class App extends Application {

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

        if (!settings.getAutoUpdateSetUp()) {
            settings.setAutoUpdateSetUp(true);
            UpdateScheduler.get(this).scheduleAutoUpdates();
        }

        if (settings.getUseMonitoringService()) {
            CallMonitoringService.start(this);
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
