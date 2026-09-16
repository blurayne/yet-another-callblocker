package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

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

    /** Whether any pattern has alternatives in it; null until it has been looked up. */
    private volatile Boolean hasAlternatives;

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

        number = BlacklistUtils.cleanNumber(number);

        BlacklistItem item = blacklistDao.getFirstMatch(number);
        if (item != null) return item;

        return getAlternativesMatch(number);
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
        hasAlternatives = null; // a pattern may have been added or changed
        callback.changed(blacklistDao.countValid() != 0);

        postEvent(itemUpdate ? new BlacklistItemChangedEvent() : new BlacklistChangedEvent());
    }

}
