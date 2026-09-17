package dummydomain.yetanothercallblocker.data;

import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A short-lived cache of resolved {@link NumberInfo}.
 *
 * <p>The point of it is to make the info of a call that is being handled available to
 * {@link dummydomain.yetanothercallblocker.CallerIdDirectoryProvider} without doing the lookup
 * again: the call screening service resolves the number before the phone starts ringing,
 * the phone app queries the directory provider right after that.
 */
public class NumberInfoCache {

    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int MAX_ENTRIES = 4; // a couple of concurrent calls at most

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void put(String number, NumberInfo numberInfo) {
        String key = getKey(number);
        if (key == null || numberInfo == null) return;

        removeStaleEntries();

        entries.remove(key); // to update the insertion order
        entries.put(key, new Entry(numberInfo, System.nanoTime()));

        while (entries.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized NumberInfo get(String number) {
        String key = getKey(number);
        if (key == null) return null;

        removeStaleEntries();

        Entry entry = entries.get(key);
        return entry != null ? entry.numberInfo : null;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void removeStaleEntries() {
        long cutoff = System.nanoTime() - TTL_NANOS;

        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            if (it.next().timestamp - cutoff < 0) it.remove();
        }
    }

    /**
     * The phone app may format the number differently than the call screening service saw it,
     * so the separators are stripped to make such numbers match.
     */
    private static String getKey(String number) {
        if (TextUtils.isEmpty(number)) return null;

        String key = PhoneNumberUtils.stripSeparators(number);
        return !TextUtils.isEmpty(key) ? key : null;
    }

    private static class Entry {
        final NumberInfo numberInfo;
        final long timestamp;

        Entry(NumberInfo numberInfo, long timestamp) {
            this.numberInfo = numberInfo;
            this.timestamp = timestamp;
        }
    }

}
