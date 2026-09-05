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
 * The numbers the user blocked or allowed in their own PhoneBlock account.
 *
 * <p>These are not part of the community list: PhoneBlock keeps them per account, and they take
 * precedence over what the community says. They are kept here so that a number blocked on the
 * website (or by reporting it from this app) is blocked on the phone as well, and looked up
 * without asking the server about the call.
 */
public class PhoneBlockPersonalLists {

    private static final String DIR_NAME = "phoneblock";
    private static final String FILE_NAME = "personal.dat";

    private static final int MAGIC = 0x50425053; // "PBPS"
    private static final int FORMAT_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockPersonalLists.class);

    private final PhoneBlockList.Storage storage;

    private boolean loaded;
    private long[] blocked = new long[0];
    private long[] allowed = new long[0];

    public PhoneBlockPersonalLists(PhoneBlockList.Storage storage) {
        this.storage = storage;
    }

    /** Whether the user blocked the number in their PhoneBlock account. */
    public synchronized boolean isBlocked(long number) {
        checkLoaded();
        return Arrays.binarySearch(blocked, number) >= 0;
    }

    /** Whether the user marked the number as legitimate in their PhoneBlock account. */
    public synchronized boolean isAllowed(long number) {
        checkLoaded();
        return Arrays.binarySearch(allowed, number) >= 0;
    }

    public synchronized int getBlockedCount() {
        checkLoaded();
        return blocked.length;
    }

    public synchronized int getAllowedCount() {
        checkLoaded();
        return allowed.length;
    }

    public synchronized boolean isEmpty() {
        return getBlockedCount() == 0 && getAllowedCount() == 0;
    }

    /** Replaces both lists with what the account holds now. */
    public synchronized void apply(List<Long> blocked, List<Long> allowed) throws IOException {
        LOG.debug("apply({} blocked, {} allowed)", blocked.size(), allowed.size());

        this.blocked = toSortedArray(blocked);
        this.allowed = toSortedArray(allowed);
        loaded = true;

        save();
    }

    /** Forgets the lists, for when there's no account to keep them for any more. */
    public synchronized void clear() {
        blocked = new long[0];
        allowed = new long[0];
        loaded = true;

        File file = getFile();
        if (file.exists() && !file.delete()) LOG.warn("clear() couldn't delete {}", file);
    }

    private static long[] toSortedArray(List<Long> numbers) {
        long[] result = new long[numbers.size()];

        int count = 0;
        for (Long number : numbers) {
            if (number != null && number > 0) result[count++] = number;
        }

        result = count == result.length ? result : Arrays.copyOf(result, count);
        Arrays.sort(result);

        return result;
    }

    private void checkLoaded() {
        if (loaded) return;
        loaded = true;

        File file = getFile();
        if (!file.exists()) {
            LOG.debug("checkLoaded() there are no lists yet");
            return;
        }

        try (DataInputStream stream = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (stream.readInt() != MAGIC || stream.readInt() != FORMAT_VERSION) {
                LOG.warn("checkLoaded() {} isn't a list of this version", file);
                return;
            }

            blocked = readNumbers(stream);
            allowed = readNumbers(stream);

            LOG.info("checkLoaded() loaded {} blocked and {} allowed numbers",
                    blocked.length, allowed.length);
        } catch (Exception e) {
            LOG.error("checkLoaded() couldn't read {}", file, e);

            blocked = new long[0];
            allowed = new long[0];
        }
    }

    private static long[] readNumbers(DataInputStream stream) throws IOException {
        long[] numbers = new long[stream.readInt()];

        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = stream.readLong();
        }

        return numbers;
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

            writeNumbers(stream, blocked);
            writeNumbers(stream, allowed);
        }
    }

    private static void writeNumbers(DataOutputStream stream, long[] numbers) throws IOException {
        stream.writeInt(numbers.length);

        for (long number : numbers) {
            stream.writeLong(number);
        }
    }

    private File getFile() {
        return new File(new File(storage.getDataDirPath(), DIR_NAME), FILE_NAME);
    }

}
