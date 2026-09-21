package dummydomain.yetanothercallblocker.utils;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.BuildConfig;

public class DebuggingUtils {

    private static final String TAG = DebuggingUtils.class.getSimpleName();

    public static void setUpCrashHandler() {
        Thread.UncaughtExceptionHandler defaultHandler
                = Thread.getDefaultUncaughtExceptionHandler();

        Thread.UncaughtExceptionHandler customHandler = (t, e) -> {
            try {
                handleCrash(e);
            } catch (Throwable ignored) {
                /*
                 * Writing down a crash can fail for the same reason as the crash itself -
                 * there is no memory left to write it with - and a handler that then throws
                 * is a crash with nothing said about either of them. The one the system
                 * installed still gets its turn below, which is what ends the process.
                 */
            } finally {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(t, e);
                }
            }
        };

        Thread.setDefaultUncaughtExceptionHandler(customHandler);
    }

    private static void handleCrash(Throwable e) {
        boolean logToExternal;
        try {
            logToExternal = App.getSettings().getSaveCrashesToExternalStorage();
        } catch (Exception ignored) {
            logToExternal = false;
        }

        try {
            saveCrashToFile(App.getInstance(), e, logToExternal);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        boolean saveLogcat;
        try {
            saveLogcat = App.getSettings().getSaveLogcatOnCrash();
        } catch (Exception ignored) {
            saveLogcat = false;
        }

        if (saveLogcat) {
            try {
                String path = saveLogcatToFile(App.getInstance(), logToExternal);
                appendDeviceInfo(path);
            } catch (IOException | InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * The crash reports and saved logs that are lying around, newest first.
     *
     * <p>They are written into the app's own directories, where nothing but adb can reach
     * them - so the app has to hand them over itself. A log exported after the fact holds
     * nothing of the crash: an app may only read its own process's log, and the process that
     * crashed is not the one doing the reading.
     */
    public static List<File> listReports(Context context) {
        List<File> reports = new ArrayList<>();

        collectReports(context.getCacheDir(), reports);
        collectReports(FileUtils.getExternalFilesDir(context), reports);

        Collections.sort(reports, (a, b) -> {
            long difference = b.lastModified() - a.lastModified();

            return difference < 0 ? -1 : difference > 0 ? 1 : 0;
        });

        return reports;
    }

    private static void collectReports(File dir, List<File> reports) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();

            if (file.isFile() && name.endsWith(".txt")
                    && (name.startsWith("crash_") || name.startsWith("logcat_"))) {
                reports.add(file);
            }
        }
    }

    public static void saveCrashToFile(Context context, Throwable th, boolean external)
            throws IOException {
        saveCrashToFile(context, th, external, getDateString());
    }

    public static String saveLogcatToFile(Context context, boolean external)
            throws IOException, InterruptedException {
        return saveLogcatToFile(getFilesDir(context, external).getAbsolutePath(), getDateString());
    }

    public static String saveLogcatInCache(Context context)
            throws IOException, InterruptedException {
        return saveLogcatToFile(context.getCacheDir().getAbsolutePath(), getDateString());
    }

    private static void saveCrashToFile(Context context, Throwable th,
                                        boolean external, String name)
            throws IOException {
        String fileName = getFilesDir(context, external).getAbsolutePath()
                + "/crash_" + name + ".txt";

        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            th.printStackTrace(writer);
        }
    }

    private static String saveLogcatToFile(String path, String name)
            throws IOException, InterruptedException {
        path += "/logcat_" + name + ".txt";

        Log.d(TAG, "Saving logcat to " + path);
        Process process = Runtime.getRuntime().exec("logcat -d -f " + path);
        process.waitFor();

        return path;
    }

    public static void appendDeviceInfo(String file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.newLine();
            writer.append("API level: ").append(String.valueOf(Build.VERSION.SDK_INT)).append('\n');
            writer.append("Brand: ").append(Build.BRAND).append('\n');
            writer.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
            writer.append("Model: ").append(Build.MODEL).append('\n');
            writer.append("Product: ").append(Build.PRODUCT).append('\n');
            writer.append("Device: ").append(Build.DEVICE).append('\n');
            writer.append("Board: ").append(Build.BOARD).append('\n');
            writer.append("Build display ID: ").append(Build.DISPLAY).append('\n');
            writer.append("App version: ").append(String.valueOf(BuildConfig.VERSION_CODE)).append('\n');
        }
    }

    private static String getDateString() {
        return new SimpleDateFormat("yyyyMMdd_HHmmssS", Locale.US).format(new Date());
    }

    private static File getFilesDir(Context context, boolean external) {
        if (external) {
            File f = FileUtils.getExternalFilesDir(context);
            if (f != null) return f;

            Log.d(TAG, "getFilesDir() no external dirs available");
        }

        return context.getCacheDir();
    }

}
