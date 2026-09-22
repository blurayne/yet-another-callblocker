package dummydomain.yetanothercallblocker.data;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dummydomain.yetanothercallblocker.data.numbers.NumbersCompiler;
import dummydomain.yetanothercallblocker.data.numbers.NumbersDb;
import dummydomain.yetanothercallblocker.sia.utils.FileUtils;

/**
 * The database, as a file to put somewhere safe.
 *
 * <p>What goes in is the table the sources were built into, because that is what the app
 * looks a number up in. The downloaded files the table was built <em>from</em> do not: they
 * are the same numbers again, several times the size, and a phone restoring this wants to be
 * able to block a call - not to be able to rebuild. The cost is that the next build fetches
 * the sources again, which is the trade this is.
 *
 * <p>One small thing goes in beside the table, because it is read and is not in it: what
 * the library knows about the database and about countries, its {@code sia_*} files. They
 * live in the same directory as the slices and are a few kilobytes. The business names
 * used to be taken from there too; they are in the table now, like the numbers.
 *
 * <p>Deliberately <em>not</em> included is the index over the slices. While that file is
 * there the app takes the database to be present: a restored phone would neither fetch it
 * nor find anything in it. Without it, the next build starts by downloading, which is what
 * should happen.
 */
public class DatabaseBackup {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseBackup.class);

    /** What says this is one of these rather than an export of the downloaded files. */
    private static final String INFO_ENTRY = "yacb_db_backup.properties";

    private static final String PROP_FORMAT = "format";
    private static final String PROP_BUILT = "built";

    private static final String FORMAT = "table";

    /** The table itself, under the name it is read from. */
    private static final String DB_ENTRY = "numbers.db";

    /** And the small files that are read but are not in the table. */
    private static final String SUPPORT_PREFIX = "support/";

    private static final String[] SUPPORT_PREFIXES = {"sia_"};

    private static final int BUFFER_SIZE = 8192;

    /** How much of an archive is unpacked before it is taken to be lying about its size. */
    private static final long MAX_SIZE = 4L * 1024 * 1024 * 1024;

    private static final int MAX_ENTRIES = 10_000;

    /**
     * Writes the database to the stream as a zip.
     *
     * @return false when there is no table to write
     */
    public boolean write(Context context, OutputStream outputStream) throws IOException {
        File db = NumbersDb.getFile(context);

        if (!db.exists() || db.length() == 0) {
            LOG.warn("write() there is no table to write");
            return false;
        }

        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(outputStream));

        Properties properties = new Properties();
        properties.setProperty(PROP_FORMAT, FORMAT);
        properties.setProperty(PROP_BUILT,
                String.valueOf(new NumbersCompiler(context).getCompiledTime()));

        zip.putNextEntry(new ZipEntry(INFO_ENTRY));
        properties.store(zip, "Yet Another Call Blocker database backup");
        zip.closeEntry();

        addFile(zip, DB_ENTRY, db);

        File siaDir = new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_PATH_PREFIX);

        File[] files = siaDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile() && isSupportFile(file.getName())) {
                    addFile(zip, SUPPORT_PREFIX + file.getName(), file);
                }
            }
        }

        zip.finish();

        LOG.info("write() written");

        return true;
    }

    /**
     * Puts a database from such a zip in the place of the one in use.
     *
     * <p>Unpacked beside it and moved over only once all of it is there, so a zip that turns
     * out to be something else, or that stops halfway, leaves a working database alone.
     *
     * @return false when the stream isn't one of these
     */
    public boolean read(Context context, InputStream inputStream) throws IOException {
        File target = NumbersDb.getFile(context);
        File temp = new File(target.getPath() + ".restore");

        File siaDir = new File(YacbHolder.getStorage().getDataDirPath(),
                SiaConstants.SIA_PATH_PREFIX);

        File supportDir = new File(siaDir.getPath() + "-restore");

        boolean saidSo = false;
        boolean gotDb = false;

        try {
            FileUtils.delete(temp);
            FileUtils.delete(supportDir);

            if (!supportDir.mkdirs()) LOG.debug("read() couldn't make {}", supportDir);

            ZipInputStream zip = new ZipInputStream(inputStream);

            long total = 0;
            int entries = 0;

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many entries");

                String name = entry.getName();

                if (INFO_ENTRY.equals(name)) {
                    Properties properties = new Properties();
                    properties.load(zip);

                    saidSo = FORMAT.equals(properties.getProperty(PROP_FORMAT));
                } else if (DB_ENTRY.equals(name)) {
                    total += copy(zip, temp);
                    gotDb = true;
                } else if (name.startsWith(SUPPORT_PREFIX)) {
                    String plain = name.substring(SUPPORT_PREFIX.length());

                    // no directories, and nothing that climbs out of the one it is given
                    if (plain.isEmpty() || plain.contains("/") || plain.contains("\\")
                            || plain.contains("..")) {
                        continue;
                    }

                    total += copy(zip, new File(supportDir, plain));
                }

                if (total > MAX_SIZE) throw new IOException("the archive is too big");
            }

            if (!saidSo || !gotDb) {
                LOG.info("read() this isn't a database backup");
                return false;
            }

            /*
             * Nothing may be reading the table while it is replaced: what is open is a handle
             * on the file that is about to go, and it would keep answering out of it.
             */
            if (YacbHolder.getNumbersLookup() != null) YacbHolder.getNumbersLookup().close();

            if (!replace(temp, target)) throw new IOException("couldn't put the table in place");

            File[] support = supportDir.listFiles();

            if (support != null) {
                if (!siaDir.isDirectory() && !siaDir.mkdirs()) {
                    LOG.warn("read() couldn't make {}", siaDir);
                }

                for (File file : support) {
                    if (!replace(file, new File(siaDir, file.getName()))) {
                        LOG.warn("read() couldn't put {} in place", file.getName());
                    }
                }
            }

            LOG.info("read() the database was put in place");

            return true;
        } finally {
            FileUtils.delete(temp);
            FileUtils.delete(supportDir);
        }
    }

    /** Whether the file is one of the small ones that are read but aren't in the table. */
    private static boolean isSupportFile(String name) {
        for (String prefix : SUPPORT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }

        return false;
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));

        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];

            int read;
            while ((read = in.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
        }

        zip.closeEntry();
    }

    private static long copy(InputStream in, File file) throws IOException {
        long written = 0;

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];

            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
            }
        }

        return written;
    }

    /** Moves a file over another, and clears what SQLite may have left beside the old one. */
    private static boolean replace(File from, File to) {
        FileUtils.delete(to);

        for (String postfix : new String[]{"-journal", "-wal", "-shm"}) {
            FileUtils.delete(new File(to.getPath() + postfix));
        }

        if (from.renameTo(to)) return true;

        // across a boundary a rename can't cross, which shouldn't happen but can be worked
        try {
            copy(new FileInputStream(from), to);
            return true;
        } catch (IOException e) {
            LOG.warn("replace() couldn't copy {} to {}", from, to, e);
            return false;
        }
    }

}
