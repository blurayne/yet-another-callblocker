package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dummydomain.yetanothercallblocker.data.BackupService;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * The backup file in the directory the user picked.
 *
 * <p>One file, always the same name, always overwritten: there are no generations to pick
 * from and nothing to clean up. Whoever wants copies of yesterday points a sync at the
 * directory - a phone is a poor place to keep the only copy of anything anyway.
 */
public class BackupHelper {

    /** The name the backup is written under, and looked for when restoring. */
    public static final String FILE_NAME = "yacb-backup.json";

    private static final String MIME_TYPE = "application/json";

    /** What an older version of the app called its export, so that those are found too. */
    private static final String LEGACY_PREFIX = "YetAnotherCallBlocker_backup";

    private static final Logger LOG = LoggerFactory.getLogger(BackupHelper.class);

    /** Whether the phone can keep a directory; picking one needs Android 5. */
    public static boolean canUseDirectory() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static Intent getPickDirectoryIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    }

    /** The directory the backup goes to, or null when the user hasn't picked one. */
    public static Uri getDirectory() {
        String uri = App.getSettings().getBackupDirectory();
        return TextUtils.isEmpty(uri) ? null : Uri.parse(uri);
    }

    /**
     * Remembers the directory the user picked, and asks to keep reading and writing it after
     * a restart - without that, the permission is gone as soon as the app is.
     *
     * @return whether the directory can be used
     */
    public static boolean keepDirectory(Context context, Uri treeUri) {
        if (treeUri == null) return false;

        try {
            context.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            LOG.warn("keepDirectory() couldn't keep the permission", e);
            return false;
        }

        App.getSettings().setBackupDirectory(treeUri.toString());

        return true;
    }

    /** What to call the directory on screen; the document tree knows its own name. */
    public static String getDirectoryName(Context context) {
        Uri uri = getDirectory();
        if (uri == null) return null;

        try {
            DocumentFile directory = DocumentFile.fromTreeUri(context, uri);
            if (directory != null && directory.getName() != null) return directory.getName();
        } catch (Exception e) {
            LOG.debug("getDirectoryName()", e);
        }

        return uri.getLastPathSegment(); // better than nothing, and it is what was picked
    }

    /**
     * Writes the backup into the directory, over whatever was there.
     *
     * <p>A backup that says the same thing as the one already there is not written at all:
     * the automatic one runs on a schedule, and touching the file would look like a change
     * to whatever syncs the directory.
     *
     * @return whether there is an up to date backup in the directory afterwards
     */
    public static boolean backup(Context context) {
        Uri treeUri = getDirectory();
        if (treeUri == null) return false;

        BackupService backupService = new BackupService();

        String backup;
        try {
            backup = backupService.write(App.getSettings(), YacbHolder.getBlacklistDao(),
                    App.getSettings().getBackupSecrets());
        } catch (Exception e) {
            LOG.warn("backup() couldn't put the backup together", e);
            return false;
        }

        DocumentFile directory;
        try {
            directory = DocumentFile.fromTreeUri(context, treeUri);
        } catch (Exception e) {
            LOG.warn("backup() couldn't open the directory", e);
            return false;
        }

        if (directory == null || !directory.canWrite()) {
            LOG.warn("backup() the directory can't be written to");
            return false;
        }

        DocumentFile file = directory.findFile(FILE_NAME);

        if (file != null && backupService.sameContent(read(context, file.getUri()), backup)) {
            LOG.debug("backup() the backup in the directory is up to date");

            App.getSettings().setLastBackupTime(System.currentTimeMillis());
            return true;
        }

        if (file == null) {
            file = directory.createFile(MIME_TYPE, FILE_NAME);

            if (file == null) {
                LOG.warn("backup() couldn't create the file");
                return false;
            }
        }

        if (!write(context, file.getUri(), backup)) return false;

        App.getSettings().setLastBackupTime(System.currentTimeMillis());

        LOG.info("backup() written");

        return true;
    }

    /**
     * Reads the backup out of a directory and puts it back.
     *
     * @param withSettings whether the settings are restored as well, or only the two lists
     * @return what was read, or null when the directory holds no backup
     */
    public static BackupService.Result restore(Context context, Uri treeUri,
                                               boolean withSettings) {
        DocumentFile file = findBackup(context, treeUri);
        if (file == null) return null;

        try (InputStream inputStream = context.getContentResolver().openInputStream(file.getUri())) {
            if (inputStream == null) return null;

            return new BackupService().read(inputStream, App.getSettings(),
                    YacbHolder.getBlacklistDao(), YacbHolder.getBlacklistService(),
                    YacbHolder.getWhitelistService(), withSettings);
        } catch (Exception e) {
            LOG.warn("restore()", e);
            return null;
        }
    }

    /** The backup in the directory: the one this app writes, or one an older version left. */
    private static DocumentFile findBackup(Context context, Uri treeUri) {
        DocumentFile directory;
        try {
            directory = DocumentFile.fromTreeUri(context, treeUri);
        } catch (Exception e) {
            LOG.warn("findBackup() couldn't open the directory", e);
            return null;
        }

        if (directory == null) return null;

        DocumentFile file = directory.findFile(FILE_NAME);
        if (file != null) return file;

        DocumentFile newest = null;
        for (DocumentFile candidate : directory.listFiles()) {
            String name = candidate.getName();
            if (name == null || !candidate.isFile()) continue;

            if (!name.startsWith(LEGACY_PREFIX) && !name.endsWith(".json")) continue;

            if (newest == null || candidate.lastModified() > newest.lastModified()) {
                newest = candidate;
            }
        }

        return newest;
    }

    private static boolean write(Context context, Uri uri, String content) {
        // "wt" truncates: without it the tail of a longer backup would stay behind
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) return false;

            out.write(content.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            LOG.warn("write()", e);
            return false;
        }
    }

    private static String read(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            return out.toString("UTF-8");
        } catch (IOException | RuntimeException e) {
            LOG.debug("read()", e);
            return null;
        }
    }

}
