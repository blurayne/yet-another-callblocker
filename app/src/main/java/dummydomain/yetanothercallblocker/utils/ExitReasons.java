package dummydomain.yetanothercallblocker.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Why the app stopped running the last few times.
 *
 * <p>An app that is killed for using too much memory doesn't crash: there is no exception, no
 * handler runs, and nothing is written down - it simply isn't there any more. The log of the
 * process that died is out of reach too, because an app may only read its own process's log
 * and that process is gone.
 *
 * <p>What the system does keep is a short list of how each of an app's processes ended, and it
 * hands that list to the app itself. It is the only thing that can tell "killed for memory"
 * from "crashed" after the fact, which is exactly the question a build that disappears raises.
 *
 * <p>Android 11 and newer. Before that, there is nothing to ask.
 */
public class ExitReasons {

    private static final Logger LOG = LoggerFactory.getLogger(ExitReasons.class);

    /** How many of them are worth looking at. */
    private static final int LIMIT = 10;

    private ExitReasons() {
    }

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * The last few endings, newest first, one per line, or null when there are none to tell.
     */
    public static String get(Context context) {
        if (!isAvailable()) return null;

        try {
            return read(context);
        } catch (Throwable t) {
            LOG.warn("get()", t);
            return null;
        }
    }

    /** The last one that wasn't the app simply being closed, or null. */
    public static String getLastAbnormal(Context context) {
        if (!isAvailable()) return null;

        try {
            return readLastAbnormal(context);
        } catch (Throwable t) {
            LOG.warn("getLastAbnormal()", t);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String read(Context context) {
        List<ApplicationExitInfo> infos = getInfos(context);
        if (infos == null || infos.isEmpty()) return null;

        StringBuilder builder = new StringBuilder();

        for (ApplicationExitInfo info : infos) {
            if (builder.length() != 0) builder.append('\n');

            builder.append(describe(info));
        }

        return builder.toString();
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String readLastAbnormal(Context context) {
        List<ApplicationExitInfo> infos = getInfos(context);
        if (infos == null) return null;

        for (ApplicationExitInfo info : infos) {
            switch (info.getReason()) {
                // the ordinary ways for a process to end; nothing went wrong in them
                case ApplicationExitInfo.REASON_EXIT_SELF:
                case ApplicationExitInfo.REASON_USER_REQUESTED:
                case ApplicationExitInfo.REASON_USER_STOPPED:
                case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                    continue;

                default:
                    return describe(info);
            }
        }

        return null;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static List<ApplicationExitInfo> getInfos(Context context) {
        ActivityManager manager = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);

        return manager != null
                ? manager.getHistoricalProcessExitReasons(null, 0, LIMIT) : null;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String describe(ApplicationExitInfo info) {
        StringBuilder builder = new StringBuilder();

        builder.append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(info.getTimestamp())));

        builder.append(" · ").append(getReasonName(info.getReason()));

        /*
         * How much the process was holding when it ended is the whole story for a kill: a
         * build that walks a few hundred thousand files either stayed within what the phone
         * allows or it didn't, and this is the number that says which.
         */
        if (info.getRss() > 0) {
            builder.append(" · RSS ").append(mb(info.getRss()));
        }

        if (info.getPss() > 0) {
            builder.append(" · PSS ").append(mb(info.getPss()));
        }

        String description = info.getDescription();
        if (description != null && !description.isEmpty()) {
            builder.append(" · ").append(description);
        }

        return builder.toString();
    }

    /** The RSS and PSS of an exit come in kilobytes. */
    private static String mb(long kilobytes) {
        return String.format(Locale.US, "%.0f MB", kilobytes / 1024f);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String getReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "killed: the phone was low on memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed by a signal";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "killed: using too much";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "something it depended on died";
            case ApplicationExitInfo.REASON_FREEZER: return "frozen";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "couldn't start";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "ended by itself";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "closed by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "stopped by the user";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "updated";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "the app changed";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "a permission changed";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "unknown (" + reason + ")";
        }
    }

}
