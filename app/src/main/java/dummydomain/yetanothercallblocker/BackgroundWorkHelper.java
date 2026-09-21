package dummydomain.yetanothercallblocker;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the phone lets the app work while nobody is looking at it.
 *
 * <p>Building the database takes minutes and updating it happens once a day, both without the
 * app being open - and a phone that has decided this app may not run in the background stops
 * exactly that, usually by killing whatever is running the moment the screen goes off. From
 * the inside that is indistinguishable from a crash, which is why it is worth asking about
 * rather than guessing.
 *
 * <p>Two different things are asked. "Background restricted" is the user (or the vendor's
 * battery manager) saying no outright. Battery optimisation is the milder one: the app may
 * run, but the system decides when - which is enough for a daily update to be skipped for
 * days at a time.
 */
public class BackgroundWorkHelper {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundWorkHelper.class);

    private BackgroundWorkHelper() {
    }

    /** The system is stopping the app from doing anything in the background. */
    public static boolean isRestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;

        ActivityManager manager = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);

        return manager != null && manager.isBackgroundRestricted();
    }

    /** The app is left to the battery saver's judgement about when it may run. */
    public static boolean isBatteryOptimized(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;

        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        return manager != null
                && !manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Whether there is anything to fix at all. */
    public static boolean needsAttention(Context context) {
        return isRestricted(context) || isBatteryOptimized(context);
    }

    /** What the state is, in one line, for a settings row. */
    public static String getStatus(Context context) {
        if (isRestricted(context)) return context.getString(R.string.background_work_restricted);

        if (isBatteryOptimized(context)) {
            return context.getString(R.string.background_work_optimized);
        }

        return context.getString(R.string.background_work_allowed);
    }

    /**
     * Takes the user where it can be changed.
     *
     * <p>Two places, because they are two settings: the restriction lives on the app's own
     * page, and battery optimisation has a dialog of its own that can be answered with one
     * tap. A phone that has neither - or a vendor that has moved them somewhere of its own -
     * gets the app's page, which is where everything about an app is.
     */
    public static void openSettings(Context context) {
        if (!isRestricted(context) && isBatteryOptimized(context)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName()));

            if (IntentHelper.startActivity(context, intent)) return;

            LOG.debug("openSettings() the battery dialog isn't available");

            // the list of apps, which every phone that has the setting also has
            if (IntentHelper.startActivity(context,
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                return;
            }
        }

        IntentHelper.startActivity(context, new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())));
    }

}
