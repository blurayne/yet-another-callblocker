package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
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

    /** The entries, in the order the screens show them. */
    public List<WhitelistItem> getItems() {
        return Whitelist.parse(settings.getWhitelist());
    }

    public int getCount() {
        return getItems().size();
    }

    /** The entry with this pattern, or null if there is none. */
    public WhitelistItem findByPattern(String pattern) {
        pattern = Whitelist.normalize(pattern);

        for (WhitelistItem item : getItems()) {
            if (item.getPattern().equals(pattern)) return item;
        }

        return null;
    }

    public boolean contains(String pattern) {
        return findByPattern(pattern) != null;
    }

    /**
     * Adds an entry, and takes the same pattern off the blacklist.
     *
     * @return whether the entry was added (it isn't when it's unusable or already there)
     */
    public boolean add(WhitelistItem item) {
        if (!item.isValid()) return false;

        List<WhitelistItem> items = getItems();
        if (contains(items, item.getPattern())) return false;

        LOG.debug("add() adding an entry");

        items.add(item);
        save(items);

        takeOffBlacklist(item.getPattern());

        return true;
    }

    /**
     * Replaces an entry with an edited one; the new pattern is taken off the blacklist.
     *
     * @param oldPattern the pattern the entry had before it was edited
     * @return whether the entry was saved (it isn't when the new pattern is already used)
     */
    public boolean replace(String oldPattern, WhitelistItem item) {
        if (!item.isValid()) return false;

        oldPattern = Whitelist.normalize(oldPattern);

        List<WhitelistItem> items = getItems();

        if (!item.getPattern().equals(oldPattern) && contains(items, item.getPattern())) {
            LOG.info("replace() not saving because another entry has the same pattern");
            return false;
        }

        LOG.debug("replace() replacing an entry");

        remove(items, oldPattern);
        items.add(item);
        save(items);

        takeOffBlacklist(item.getPattern());

        return true;
    }

    /** Removes the entries with these patterns. */
    public void remove(Collection<String> patterns) {
        List<WhitelistItem> items = getItems();

        boolean changed = false;
        for (String pattern : patterns) {
            changed |= remove(items, Whitelist.normalize(pattern));
        }

        if (!changed) return;

        LOG.debug("remove() removing {} entries", patterns.size());

        save(items);
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

        List<String> patterns = new ArrayList<>(1);
        patterns.add(entry);
        remove(patterns);
    }

    private void save(List<WhitelistItem> items) {
        settings.setWhitelist(Whitelist.serialize(items));

        postEvent(new WhitelistChangedEvent());
    }

    private void takeOffBlacklist(String pattern) {
        if (blacklistService == null || pattern.isEmpty()) return;

        blacklistService.removeExactPattern(BlacklistUtils.patternFromHumanReadable(pattern));
    }

    private static boolean contains(List<WhitelistItem> items, String pattern) {
        for (WhitelistItem item : items) {
            if (item.getPattern().equals(pattern)) return true;
        }
        return false;
    }

    private static boolean remove(List<WhitelistItem> items, String pattern) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getPattern().equals(pattern)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

}
