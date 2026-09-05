package dummydomain.yetanothercallblocker.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * The numbers of the PhoneBlock community list, kept on the device.
 *
 * <p>The list is downloaded as a whole and looked up locally, so a call costs no network and
 * nothing about it leaves the device - the same way the number database works. It is held as a
 * sorted array of numbers with a rating each, which is a few megabytes for a list of this size
 * and can be searched without loading anything else.
 */
public class PhoneBlockList {

    /** What the community says a number is used for. */
    public enum Rating {
        LEGITIMATE("A_LEGITIMATE"), MISSED("B_MISSED"), PING("C_PING"), POLL("D_POLL"),
        ADVERTISING("E_ADVERTISING"), GAMBLE("F_GAMBLE"), FRAUD("G_FRAUD"), UNKNOWN(null);

        private final String apiName;

        Rating(String apiName) {
            this.apiName = apiName;
        }

        /** The name the API uses, or null for a rating that can't be reported. */
        public String getApiName() {
            return apiName;
        }

        /** Parses the names the API uses ({@code A_LEGITIMATE} and so on). */
        public static Rating parse(String value) {
            if (value == null || value.length() < 3) return UNKNOWN;

            switch (value.charAt(0)) {
                case 'A': return LEGITIMATE;
                case 'B': return MISSED;
                case 'C': return PING;
                case 'D': return POLL;
                case 'E': return ADVERTISING;
                case 'F': return GAMBLE;
                case 'G': return FRAUD;
                default: return UNKNOWN;
            }
        }

        /** Whether the number is one the community warns about. */
        public boolean isSpam() {
            return this != LEGITIMATE;
        }
    }

    /** One entry of the list as it arrives from the API. */
    public static class Entry {

        public final long number;
        public final Rating rating;

        public Entry(long number, Rating rating) {
            this.number = number;
            this.rating = rating;
        }

    }

    private static final String DIR_NAME = "phoneblock";
    private static final String FILE_NAME = "numbers.dat";

    private static final int MAGIC = 0x50424c4b; // "PBLK"
    private static final int FORMAT_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockList.class);

    private final Storage storage;

    private boolean loaded;
    private long[] numbers = new long[0];
    private byte[] ratings = new byte[0];
    private int listVersion;

    /** Where the list is kept; the same place the number database lives. */
    public interface Storage {
        String getDataDirPath();
    }

    public PhoneBlockList(Storage storage) {
        this.storage = storage;
    }

    /** What the list says about a number, or null if it doesn't know it. */
    public synchronized Rating getRating(long number) {
        checkLoaded();

        int index = Arrays.binarySearch(numbers, number);
        return index >= 0 ? Rating.values()[ratings[index]] : null;
    }

    /** The version of the list, which the next update continues from. */
    public synchronized int getListVersion() {
        checkLoaded();
        return listVersion;
    }

    public synchronized int getSize() {
        checkLoaded();
        return numbers.length;
    }

    public synchronized boolean isEmpty() {
        return getSize() == 0;
    }

    /**
     * Applies what an update brought: entries the community warns about are added or updated,
     * entries it has cleared are dropped.
     *
     * @param replaceAll whether the entries are the whole list rather than the changes to it
     */
    public synchronized void apply(List<Entry> entries, int listVersion, boolean replaceAll)
            throws IOException {
        LOG.debug("apply({} entries, version {}, replaceAll={})",
                entries.size(), listVersion, replaceAll);

        if (!replaceAll) checkLoaded();

        long[] oldNumbers = replaceAll ? new long[0] : numbers;
        byte[] oldRatings = replaceAll ? new byte[0] : ratings;

        Entry[] changes = entries.toArray(new Entry[0]);
        Arrays.sort(changes, (a, b) -> Long.compare(a.number, b.number));
        changes = withoutDuplicates(changes);

        long[] newNumbers = new long[oldNumbers.length + changes.length];
        byte[] newRatings = new byte[newNumbers.length];

        int oldIndex = 0;
        int changeIndex = 0;
        int newIndex = 0;

        // both sides are sorted, so one pass over each is enough
        while (oldIndex < oldNumbers.length || changeIndex < changes.length) {
            long oldNumber = oldIndex < oldNumbers.length ? oldNumbers[oldIndex] : Long.MAX_VALUE;
            long changedNumber = changeIndex < changes.length
                    ? changes[changeIndex].number : Long.MAX_VALUE;

            if (changedNumber < oldNumber) {
                newIndex = add(newNumbers, newRatings, newIndex, changes[changeIndex]);
                changeIndex++;
            } else if (oldNumber < changedNumber) {
                newNumbers[newIndex] = oldNumber;
                newRatings[newIndex] = oldRatings[oldIndex];
                newIndex++;
                oldIndex++;
            } else { // the entry is an update of one that is already there
                newIndex = add(newNumbers, newRatings, newIndex, changes[changeIndex]);
                changeIndex++;
                oldIndex++;
            }
        }

        numbers = Arrays.copyOf(newNumbers, newIndex);
        ratings = Arrays.copyOf(newRatings, newIndex);
        this.listVersion = listVersion;
        loaded = true;

        save();

        LOG.info("apply() the list holds {} numbers at version {}", numbers.length, listVersion);
    }

    /** Forgets the list, so that the next update fetches it as a whole. */
    public synchronized void clear() {
        numbers = new long[0];
        ratings = new byte[0];
        listVersion = 0;
        loaded = true;

        File file = getFile();
        if (file.exists() && !file.delete()) LOG.warn("clear() couldn't delete {}", file);
    }

    /** Keeps the last entry of each number, so that one number is only carried once. */
    private static Entry[] withoutDuplicates(Entry[] changes) {
        int count = 0;

        for (int i = 0; i < changes.length; i++) {
            if (i + 1 < changes.length && changes[i + 1].number == changes[i].number) continue;
            changes[count++] = changes[i];
        }

        return count == changes.length ? changes : Arrays.copyOf(changes, count);
    }

    /** @return the index after the entry, which isn't written when the number was cleared */
    private static int add(long[] numbers, byte[] ratings, int index, Entry entry) {
        if (!entry.rating.isSpam()) return index; // no longer a number to warn about

        numbers[index] = entry.number;
        ratings[index] = (byte) entry.rating.ordinal();
        return index + 1;
    }

    private void checkLoaded() {
        if (loaded) return;
        loaded = true;

        File file = getFile();
        if (!file.exists()) {
            LOG.debug("checkLoaded() there's no list yet");
            return;
        }

        try (DataInputStream stream = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (stream.readInt() != MAGIC || stream.readInt() != FORMAT_VERSION) {
                LOG.warn("checkLoaded() {} isn't a list of this version", file);
                return;
            }

            listVersion = stream.readInt();

            int count = stream.readInt();
            long[] numbers = new long[count];
            byte[] ratings = new byte[count];

            for (int i = 0; i < count; i++) {
                numbers[i] = stream.readLong();
                ratings[i] = stream.readByte();
            }

            this.numbers = numbers;
            this.ratings = ratings;

            LOG.info("checkLoaded() loaded {} numbers at version {}", count, listVersion);
        } catch (Exception e) {
            LOG.error("checkLoaded() couldn't read {}", file, e);

            numbers = new long[0];
            ratings = new byte[0];
            listVersion = 0;
        }
    }

    private void save() throws IOException {
        File file = getFile();

        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Couldn't create " + dir);
        }

        try (DataOutputStream stream = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            stream.writeInt(MAGIC);
            stream.writeInt(FORMAT_VERSION);
            stream.writeInt(listVersion);
            stream.writeInt(numbers.length);

            for (int i = 0; i < numbers.length; i++) {
                stream.writeLong(numbers[i]);
                stream.writeByte(ratings[i]);
            }
        }
    }

    private File getFile() {
        return new File(new File(storage.getDataDirPath(), DIR_NAME), FILE_NAME);
    }

}
