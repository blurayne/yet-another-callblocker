package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.event.WhitelistChangedEvent;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;

/**
 * Changes to the whitelist, and what they mean for the blacklist.
 *
 * <p>A number can't sensibly be on both lists as itself: putting it on one takes it off the
 * other. Only the entry that is exactly the same is taken off - a pattern that happens to cover
 * the number stays, because it was put there for a whole range, and the whitelist wins over it
 * anyway.
 */
public class WhitelistService {

    private static final Logger LOG = LoggerFactory.getLogger(WhitelistService.class);

    private final Settings settings;
    private final BlacklistService blacklistService;

    public WhitelistService(Settings settings, BlacklistService blacklistService) {
        this.settings = settings;
        this.blacklistService = blacklistService;
    }

    /** The entries, sorted. */
    public List<String> getEntries() {
        return Whitelist.parseSorted(settings.getWhitelist());
    }

    /** Whether the entry is on the list as it is. */
    public boolean contains(String entry) {
        return Whitelist.contains(settings.getWhitelist(), entry);
    }

    /**
     * Adds an entry, and takes the same entry off the blacklist.
     *
     * @return whether the entry was added (it isn't when it's empty or already there)
     */
    public boolean add(String entry) {
        entry = Whitelist.normalize(entry);
        if (entry.isEmpty()) return false;

        String value = settings.getWhitelist();
        String newValue = Whitelist.add(value, entry);
        if (TextUtils.equals(value, newValue)) return false;

        LOG.debug("add() adding an entry");

        settings.setWhitelist(newValue);
        takeOffBlacklist(entry);

        postEvent(new WhitelistChangedEvent());
        return true;
    }

    /** Replaces an entry with an edited one; the new one is taken off the blacklist. */
    public void replace(String oldEntry, String newEntry) {
        String value = settings.getWhitelist();
        String newValue = Whitelist.replace(value, oldEntry, newEntry);
        if (TextUtils.equals(value, newValue)) return;

        LOG.debug("replace() replacing an entry");

        settings.setWhitelist(newValue);
        takeOffBlacklist(Whitelist.normalize(newEntry));

        postEvent(new WhitelistChangedEvent());
    }

    public void remove(String entry) {
        String value = settings.getWhitelist();
        String newValue = Whitelist.remove(value, entry);
        if (TextUtils.equals(value, newValue)) return;

        LOG.debug("remove() removing an entry");

        settings.setWhitelist(newValue);

        postEvent(new WhitelistChangedEvent());
    }

    /**
     * Takes an entry off the whitelist because it was put on the blacklist.
     *
     * @param pattern the blacklist pattern, as the blacklist keeps it
     */
    public void removeExactPattern(String pattern) {
        if (TextUtils.isEmpty(pattern)) return;

        String entry = BlacklistUtils.patternToHumanReadable(pattern);
        if (!contains(entry)) return;

        LOG.info("removeExactPattern() the entry was put on the blacklist, taking it off");
        remove(entry);
    }

    private void takeOffBlacklist(String entry) {
        if (blacklistService == null || entry.isEmpty()) return;

        blacklistService.removeExactPattern(BlacklistUtils.patternFromHumanReadable(entry));
    }

}
