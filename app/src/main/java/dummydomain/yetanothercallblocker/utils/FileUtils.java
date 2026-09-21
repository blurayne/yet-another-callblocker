package dummydomain.yetanothercallblocker.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.app.ShareCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import dummydomain.yetanothercallblocker.BuildConfig;

public class FileUtils {

    private static final String FILEPROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider";

    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    public static void shareFile(Activity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, FILEPROVIDER_AUTHORITY, file);

            ShareCompat.IntentBuilder shareBuilder = ShareCompat.IntentBuilder.from(activity)
                    .setStream(uri)
                    .setType(activity.getContentResolver().getType(uri));

            shareBuilder.getIntent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            shareBuilder.startChooser();
        } catch (Exception e) {
            LOG.warn("shareFile()", e);
        }
    }

    /** Shares several files at once, for the times when one of them says little by itself. */
    public static void shareFiles(Activity activity, List<File> files) {
        if (files == null || files.isEmpty()) return;

        if (files.size() == 1) {
            shareFile(activity, files.get(0));
            return;
        }

        try {
            ShareCompat.IntentBuilder shareBuilder = ShareCompat.IntentBuilder.from(activity)
                    .setType("text/plain");

            for (File file : files) {
                shareBuilder.addStream(FileProvider.getUriForFile(
                        activity, FILEPROVIDER_AUTHORITY, file));
            }

            shareBuilder.getIntent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            shareBuilder.startChooser();
        } catch (Exception e) {
            LOG.warn("shareFiles()", e);
        }
    }

    public static File getExternalFilesDir(Context context) {
        File[] dirs = ContextCompat.getExternalFilesDirs(context, null);
        for (File dir : dirs) {
            if (dir != null) return dir;
        }
        return null;
    }

}
