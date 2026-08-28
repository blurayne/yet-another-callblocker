package dummydomain.yetanothercallblocker.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static dummydomain.yetanothercallblocker.utils.StringUtils.quote;

/**
 * Exports and imports the offline number database (the {@code sia} and {@code sia-secondary}
 * directories) as a zip archive, so that it can be moved to another device or kept as a backup
 * instead of being downloaded again.
 *
 * <p>The database versions are stored in the settings rather than in the database files,
 * so they are carried in the archive as well: without them the secondary (updates) database
 * would be dropped as soon as the imported database is loaded.
 */
public class DbImporterExporter {

    /** The database versions that belong to an archive. */
    public static class Versions {

        public final int baseDbVersion;
        public final int secondaryDbVersion;

        public Versions(int baseDbVersion, int secondaryDbVersion) {
            this.baseDbVersion = baseDbVersion;
            this.secondaryDbVersion = secondaryDbVersion;
        }

    }

    /** Every database directory contains this file. */
    private static final String MAIN_DB_MARKER = "data_slice_info.dat";

    private static final String INFO_ENTRY = "yacb_db_export.properties";
    private static final String SECONDARY_ENTRY_PREFIX = "secondary/";

    private static final String PROP_BASE_DB_VERSION = "baseDbVersion";
    private static final String PROP_SECONDARY_DB_VERSION = "secondaryDbVersion";

    private static final String IMPORT_DIR_SUFFIX = "-import";
    private static final String REPLACED_DIR_SUFFIX = "-replaced";

    // the database files are named "<name>.dat" (main) and "<slice id>.sia" (secondary)
    private static final String[] ALLOWED_EXTENSIONS = {".dat", ".sia"};

    // the archive comes from the user, so the extraction is bounded
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_TOTAL_SIZE = 1024L * 1024 * 1024;

    private static final int BUFFER_SIZE = 8192;

    private static final Logger LOG = LoggerFactory.getLogger(DbImporterExporter.class);

    public boolean isExportable(File mainDir) {
        return new File(mainDir, MAIN_DB_MARKER).exists();
    }

    /**
     * Writes the database to the stream as a zip archive.
     *
     * @return false if there's no database to export
     */
    public boolean export(File mainDir, File secondaryDir, Versions versions,
                          OutputStream outputStream) throws IOException {
        LOG.debug("export() started");

        if (!isExportable(mainDir)) {
            LOG.warn("export() no database in {}", mainDir);
            return false;
        }

        ZipOutputStream zipOutputStream
                = new ZipOutputStream(new BufferedOutputStream(outputStream));

        Properties properties = new Properties();
        properties.setProperty(PROP_BASE_DB_VERSION, String.valueOf(versions.baseDbVersion));
        properties.setProperty(PROP_SECONDARY_DB_VERSION,
                String.valueOf(versions.secondaryDbVersion));

        zipOutputStream.putNextEntry(new ZipEntry(INFO_ENTRY));
        properties.store(zipOutputStream, "Yet Another Call Blocker database export");
        zipOutputStream.closeEntry();

        addFiles(zipOutputStream, mainDir, "");
        addFiles(zipOutputStream, secondaryDir, SECONDARY_ENTRY_PREFIX);

        zipOutputStream.finish();

        LOG.info("export() exported the database");
        return true;
    }

    /**
     * Replaces the database with the one from the archive.
     *
     * <p>The archive is extracted into temporary directories and checked before anything is
     * replaced, so a broken archive leaves the current database alone.
     *
     * @return the versions of the imported database, or null if it isn't a database archive
     */
    public Versions importDb(InputStream inputStream, File dataDir,
                             String mainDirName, String secondaryDirName) throws IOException {
        LOG.debug("importDb() started");

        File mainImportDir = new File(dataDir, mainDirName + IMPORT_DIR_SUFFIX);
        File secondaryImportDir = new File(dataDir, secondaryDirName + IMPORT_DIR_SUFFIX);

        try {
            recreateDir(mainImportDir);
            recreateDir(secondaryImportDir);

            Versions versions = extract(inputStream, mainImportDir, secondaryImportDir);

            if (versions == null || !isExportable(mainImportDir)) {
                LOG.warn("importDb() the archive doesn't contain a database");
                return null;
            }

            if (!replaceDir(new File(dataDir, mainDirName), mainImportDir,
                    new File(dataDir, mainDirName + REPLACED_DIR_SUFFIX))) {
                throw new IOException("Couldn't replace the database directory");
            }

            if (!replaceDir(new File(dataDir, secondaryDirName), secondaryImportDir,
                    new File(dataDir, secondaryDirName + REPLACED_DIR_SUFFIX))) {
                // the secondary database is expendable: an update rebuilds it
                LOG.warn("importDb() couldn't replace the secondary database directory");
                versions = new Versions(versions.baseDbVersion, 0);
            }

            LOG.info("importDb() imported the database");
            return versions;
        } finally {
            delete(mainImportDir);
            delete(secondaryImportDir);
        }
    }

    private void addFiles(ZipOutputStream zipOutputStream, File dir, String entryPrefix)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        byte[] buffer = new byte[BUFFER_SIZE];

        for (File file : files) {
            if (!file.isFile() || !hasAllowedExtension(file.getName())) continue;

            zipOutputStream.putNextEntry(new ZipEntry(entryPrefix + file.getName()));
            try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                copy(inputStream, zipOutputStream, buffer, Long.MAX_VALUE);
            }
            zipOutputStream.closeEntry();
        }
    }

    private Versions extract(InputStream inputStream, File mainDir, File secondaryDir)
            throws IOException {
        Versions versions = null;
        boolean hasFiles = false;

        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = MAX_TOTAL_SIZE;
        int entries = 0;

        ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(inputStream));

        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            if (++entries > MAX_ENTRIES) throw new IOException("Too many entries in the archive");

            if (entry.isDirectory()) continue;

            String name = entry.getName();

            if (INFO_ENTRY.equals(name)) {
                versions = readVersions(zipInputStream);
                continue;
            }

            File dir = mainDir;
            if (name.startsWith(SECONDARY_ENTRY_PREFIX)) {
                dir = secondaryDir;
                name = name.substring(SECONDARY_ENTRY_PREFIX.length());
            }

            // the name is never used as a path: only plain database file names are accepted
            if (!isAllowedFileName(name)) {
                LOG.warn("extract() skipping an unexpected entry: {}", quote(entry.getName()));
                continue;
            }

            try (OutputStream outputStream = new BufferedOutputStream(
                    new FileOutputStream(new File(dir, name)))) {
                remaining -= copy(zipInputStream, outputStream, buffer, remaining);
            }

            hasFiles = true;
        }

        if (!hasFiles) return null;

        // an archive from an older version has no info entry: let the versions be re-detected
        return versions != null ? versions : new Versions(0, 0);
    }

    private Versions readVersions(InputStream inputStream) {
        try {
            Properties properties = new Properties();
            properties.load(inputStream);

            return new Versions(getInt(properties, PROP_BASE_DB_VERSION),
                    getInt(properties, PROP_SECONDARY_DB_VERSION));
        } catch (Exception e) {
            LOG.warn("readVersions() couldn't read the versions", e);
            return new Versions(0, 0);
        }
    }

    private static int getInt(Properties properties, String key) {
        try {
            return Integer.parseInt(properties.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            LOG.warn("getInt() bad value for {}", key);
            return 0;
        }
    }

    private static long copy(InputStream inputStream, OutputStream outputStream,
                             byte[] buffer, long limit) throws IOException {
        long total = 0;

        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("The archive is too large");

            outputStream.write(buffer, 0, read);
        }

        return total;
    }

    private static boolean isAllowedFileName(String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.startsWith(".")) {
            return false;
        }
        return hasAllowedExtension(name);
    }

    private static boolean hasAllowedExtension(String name) {
        for (String extension : ALLOWED_EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    private static void recreateDir(File dir) throws IOException {
        delete(dir);
        if (!dir.mkdirs()) throw new IOException("Couldn't create " + dir);
    }

    private static boolean replaceDir(File target, File source, File backup) {
        delete(backup);

        if (target.exists() && !target.renameTo(backup)) return false;

        if (!source.renameTo(target)) {
            if (backup.exists() && !backup.renameTo(target)) {
                LOG.error("replaceDir() couldn't restore {}", target);
            }
            return false;
        }

        delete(backup);
        return true;
    }

    private static void delete(File file) {
        if (!file.exists()) return;

        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                delete(child);
            }
        }

        if (!file.delete()) LOG.warn("delete() couldn't delete {}", file);
    }

}
