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
import dummydomain.yetanothercallblocker.data.DatabaseBackup;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;

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

    /** And the name the downloaded database goes under, beside it. */
    public static final String DB_FILE_NAME = "yacb-database.zip";

    private static final String MIME_TYPE = "application/json";

    private static final String DB_MIME_TYPE = "application/zip";

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

        // and the database beside it, when it is wanted and isn't already the one in there
        if (App.getSettings().getBackupDatabase()) backupDatabase(context, false);

        return true;
    }

    /**
     * Writes the downloaded database into the backup directory, beside the backup itself.
     *
     * <p>So that a phone set up again from the backup can block a call straight away rather
     * than downloading tens of megabytes over whatever connection it has first.
     *
     * <p>Only when it is not the one already in there: the backup runs daily and the database
     * changes when it is built, which is not daily. Copying it every night would be tens of
     * megabytes written for nothing, and would look like a change to whatever syncs the
     * directory. A version that is the same is a file that is the same.
     *
     * @param force write it even when the directory already holds this version
     * @return whether the directory holds this version of the database afterwards
     */
    public static boolean backupDatabase(Context context, boolean force) {
        Uri treeUri = getDirectory();
        if (treeUri == null) return false;

        /*
         * What is written is the table, so it is the table that decides whether the one in
         * the directory is still the current one. When it was built is the whole answer: a
         * build is the only thing that changes it.
         */
        long built = new NumbersCompiler(context).getCompiledTime();

        if (built <= 0) {
            LOG.info("backupDatabase() there is no database to write");
            return false;
        }

        DocumentFile directory;
        try {
            directory = DocumentFile.fromTreeUri(context, treeUri);
        } catch (Exception e) {
            LOG.warn("backupDatabase() couldn't open the directory", e);
            return false;
        }

        if (directory == null || !directory.canWrite()) {
            LOG.warn("backupDatabase() the directory can't be written to");
            return false;
        }

        DocumentFile file = directory.findFile(DB_FILE_NAME);

        if (!force && file != null && App.getSettings().getLastBackupDbBuild() == built) {
            LOG.debug("backupDatabase() the database in the directory is the current one");
            return true;
        }

        if (file == null) {
            file = directory.createFile(DB_MIME_TYPE, DB_FILE_NAME);

            if (file == null) {
                LOG.warn("backupDatabase() couldn't create the file");
                return false;
            }
        }

        /*
         * Straight into the directory rather than through a file of our own first: a phone
         * with room for the database twice is not one to count on.
         */
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (out == null) return false;

            if (!new DatabaseBackup().write(context, out)) {
                LOG.warn("backupDatabase() the database couldn't be written");
                return false;
            }
        } catch (Exception e) {
            LOG.warn("backupDatabase() failed", e);
            return false;
        }

        App.getSettings().setLastBackupDbBuild(built);

        LOG.info("backupDatabase() the database built at {} was written", built);

        return true;
    }

    /** The database in the backup directory, or null when there is none there. */
    public static DocumentFile findDatabaseBackup(Context context, Uri treeUri) {
        if (treeUri == null) return null;

        try {
            DocumentFile directory = DocumentFile.fromTreeUri(context, treeUri);

            return directory != null ? directory.findFile(DB_FILE_NAME) : null;
        } catch (Exception e) {
            LOG.warn("findDatabaseBackup()", e);
            return null;
        }
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
