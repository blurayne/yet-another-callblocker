package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.data.db.BlacklistDao;
import dummydomain.yetanothercallblocker.data.db.BlacklistItem;
import dummydomain.yetanothercallblocker.event.BlacklistChangedEvent;
import dummydomain.yetanothercallblocker.event.BlacklistItemChangedEvent;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;

public class BlacklistService {

    public interface Callback {
        void changed(boolean notEmpty);
    }

    private final Callback callback;
    private final BlacklistDao blacklistDao;

    private WhitelistService whitelistService;

    /**
     * How many entries the app is willing to match itself. A list longer than this stays with
     * the database, which matches the same patterns apart from the alternatives (those are
     * looked up separately either way).
     */
    private static final int FULL_MATCHING_LIMIT = 20000;

    /** Whether any pattern has alternatives in it; null until it has been looked up. */
    private volatile Boolean hasAlternatives;

    /** The list as the app matches it itself, with the patterns compiled; null until needed. */
    private volatile List<BlacklistItem> validItems;
    private final Map<String, Pattern> compiledPatterns = new HashMap<>();

    public BlacklistService(Callback callback, BlacklistDao blacklistDao) {
        this.callback = callback;
        this.blacklistDao = blacklistDao;
    }

    /** The whitelist, so that a number put on the blacklist is taken off it. */
    public void setWhitelistService(WhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    public BlacklistItem getBlacklistItemForNumber(String number) {
        if (TextUtils.isEmpty(number)) return null;

        return getMatch(BlacklistUtils.cleanNumber(number));
    }

    /**
     * The entry for a number given in every form it can be written in.
     *
     * <p>The entry may have been saved as "+4922147258578" while the call was logged as
     * "022147258578"; it is the same number either way, so every form is looked up.
     *
     * @param numberVariants the forms of the number, cleaned, the number itself first
     */
    public BlacklistItem getBlacklistItemForNumber(List<String> numberVariants) {
        if (numberVariants == null || numberVariants.isEmpty()) return null;

        for (String number : numberVariants) {
            BlacklistItem item = getMatch(number);
            if (item != null) return item;
        }

        return null;
    }

    /**
     * The entry for a number, matched by the app itself: every wildcard a pattern may contain
     * works here, in either notation, and an entry that is the number itself is preferred over
     * a pattern that merely covers it, so the screens can say which entry applies.
     *
     * @param numberVariants the forms of the number, cleaned, the number itself first
     */
    public BlacklistItem getFullMatch(List<String> numberVariants) {
        if (numberVariants == null || numberVariants.isEmpty()) return null;

        List<BlacklistItem> items = getValidItems();
        if (items == null) return getBlacklistItemForNumber(numberVariants); // too long to match here

        BlacklistItem match = null;

        for (String number : numberVariants) {
            for (BlacklistItem item : items) {
                String pattern = item.getPattern();
                if (TextUtils.isEmpty(pattern)) continue;

                if (pattern.equals(number)) return item; // the number itself

                if (match == null) {
                    Pattern compiled = getCompiledPattern(pattern);
                    if (compiled != null && compiled.matcher(number).matches()) match = item;
                }
            }
        }

        return match;
    }

    /** The valid entries, kept until the list changes, or null if there are too many of them. */
    private List<BlacklistItem> getValidItems() {
        List<BlacklistItem> items = validItems;

        if (items == null) {
            if (blacklistDao.countValid() > FULL_MATCHING_LIMIT) return null;

            validItems = items = blacklistDao.findAllValid();
        }

        return items;
    }

    private Pattern getCompiledPattern(String pattern) {
        synchronized (compiledPatterns) {
            Pattern compiled = compiledPatterns.get(pattern);

            if (compiled == null && !compiledPatterns.containsKey(pattern)) {
                compiledPatterns.put(pattern, compiled = BlacklistUtils.compilePattern(pattern));
            }

            return compiled;
        }
    }

    private BlacklistItem getMatch(String cleanNumber) {
        if (TextUtils.isEmpty(cleanNumber)) return null;

        BlacklistItem item = blacklistDao.getFirstMatch(cleanNumber);
        if (item != null) return item;

        return getAlternativesMatch(cleanNumber);
    }

    /**
     * Matches the patterns the database can't: the ones with alternatives in them.
     *
     * <p>Most blacklists have none, so whether there are any is remembered - a call then costs
     * nothing beyond the query the database does anyway.
     */
    private BlacklistItem getAlternativesMatch(String cleanNumber) {
        Boolean hasAlternatives = this.hasAlternatives;
        if (hasAlternatives == null) {
            this.hasAlternatives = hasAlternatives = blacklistDao.countWithAlternatives() != 0;
        }

        if (!hasAlternatives) return null;

        for (BlacklistItem item : blacklistDao.findAllWithAlternatives()) {
            if (BlacklistUtils.matches(item.getPattern(), cleanNumber)) return item;
        }

        return null;
    }

    public void save(BlacklistItem blacklistItem) {
        boolean newItem = blacklistItem.getId() == null;

        sanitize(blacklistItem);
        blacklistDao.save(blacklistItem);

        takeOffWhitelist(blacklistItem);

        blacklistChanged(!newItem);
    }

    public void insert(BlacklistItem blacklistItem) {
        sanitize(blacklistItem);
        blacklistDao.insert(blacklistItem);

        takeOffWhitelist(blacklistItem);

        blacklistChanged(false);
    }

    /**
     * Deletes the items whose pattern is exactly this one, because it was put on the whitelist.
     *
     * @return whether there was anything to delete
     */
    public boolean removeExactPattern(String pattern) {
        if (TextUtils.isEmpty(pattern)) return false;

        List<BlacklistItem> items = blacklistDao.findAllByPattern(pattern);
        if (items.isEmpty()) return false;

        List<Long> ids = new ArrayList<>(items.size());
        for (BlacklistItem item : items) {
            ids.add(item.getId());
        }

        delete(ids);
        return true;
    }

    private void takeOffWhitelist(BlacklistItem blacklistItem) {
        if (whitelistService != null && !blacklistItem.getInvalid()) {
            whitelistService.removeExactPattern(blacklistItem.getPattern());
        }
    }

    public void addCall(BlacklistItem blacklistItem, Date date) {
        sanitize(blacklistItem);

        blacklistItem.setLastCallDate(Objects.requireNonNull(date));
        blacklistItem.setNumberOfCalls(blacklistItem.getNumberOfCalls() + 1);

        blacklistDao.save(blacklistItem);

        postEvent(new BlacklistItemChangedEvent());
    }

    public void delete(Iterable<Long> keys) {
        blacklistDao.delete(keys);

        blacklistChanged(false);
    }

    private void sanitize(BlacklistItem blacklistItem) {
        blacklistItem.setInvalid(!BlacklistUtils.isValidPattern(blacklistItem.getPattern()));
        if (blacklistItem.getCreationDate() == null) blacklistItem.setCreationDate(new Date());
        if (blacklistItem.getNumberOfCalls() < 0) blacklistItem.setNumberOfCalls(0);
    }

    private void blacklistChanged(boolean itemUpdate) {
        // a pattern may have been added or changed, so nothing kept about them is valid now
        hasAlternatives = null;
        validItems = null;
        synchronized (compiledPatterns) {
            compiledPatterns.clear();
        }

        callback.changed(blacklistDao.countValid() != 0);

        postEvent(itemUpdate ? new BlacklistItemChangedEvent() : new BlacklistChangedEvent());
    }

}
