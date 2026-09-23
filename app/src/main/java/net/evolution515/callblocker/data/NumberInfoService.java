package net.evolution515.callblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Set;

import net.evolution515.callblocker.Settings;
import net.evolution515.callblocker.data.db.BlacklistItem;
import net.evolution515.callblocker.data.numbers.NumbersLookup;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

public class NumberInfoService {

    public interface HiddenNumberDetector {
        boolean isHiddenNumber(String number);
    }

    public interface NumberNormalizer {
        String normalizeNumber(String number, String countryCode);
    }

    private static final Logger LOG = LoggerFactory.getLogger(NumberInfoService.class);

    protected final Settings settings;

    protected final HiddenNumberDetector hiddenNumberDetector;
    protected final NumberNormalizer numberNormalizer;
    protected final CommunityDatabase communityDatabase;
    protected final FeaturedDatabase featuredDatabase;
    protected final ContactsProvider contactsProvider;
    protected final BlacklistService blacklistService;
    protected NumbersLookup numbersLookup;
    protected PhoneBlockList phoneBlockList;
    protected PhoneBlockPersonalLists phoneBlockPersonalLists;
    protected Whitelist whitelist;

    public NumberInfoService(Settings settings, HiddenNumberDetector hiddenNumberDetector,
                             NumberNormalizer numberNormalizer, CommunityDatabase communityDatabase,
                             FeaturedDatabase featuredDatabase, ContactsProvider contactsProvider,
                             BlacklistService blacklistService) {
        this.settings = settings;
        this.hiddenNumberDetector = hiddenNumberDetector;
        this.numberNormalizer = numberNormalizer;
        this.communityDatabase = communityDatabase;
        this.featuredDatabase = featuredDatabase;
        this.contactsProvider = contactsProvider;
        this.blacklistService = blacklistService;
    }

    /** Where the sources were built into, which is what a number is asked of first. */
    public void setNumbersLookup(NumbersLookup numbersLookup) {
        this.numbersLookup = numbersLookup;
    }

    /** The number as the databases key it, or 0 when it isn't one. */
    private static long parseNumber(String normalizedNumber) {
        if (normalizedNumber == null) return 0;

        String digits = normalizedNumber.startsWith("+")
                ? normalizedNumber.substring(1) : normalizedNumber;

        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setPhoneBlockList(PhoneBlockList phoneBlockList) {
        this.phoneBlockList = phoneBlockList;
    }

    public void setPhoneBlockPersonalLists(PhoneBlockPersonalLists phoneBlockPersonalLists) {
        this.phoneBlockPersonalLists = phoneBlockPersonalLists;
    }

    public void setWhitelist(Whitelist whitelist) {
        this.whitelist = whitelist;
    }

    public NumberInfo getNumberInfo(String number, String countryCode, boolean full) {
        LOG.debug("getNumberInfo({}, {}, {}) started", number, countryCode, full);

        NumberInfo numberInfo = new NumberInfo();
        numberInfo.number = number;

        if (hiddenNumberDetector != null) {
            numberInfo.isHiddenNumber = hiddenNumberDetector.isHiddenNumber(number);
        }
        LOG.trace("getNumberInfo() isHiddenNumber={}", numberInfo.isHiddenNumber);

        if (numberInfo.isHiddenNumber || TextUtils.isEmpty(number)
                || TextUtils.getTrimmedLength(number) == 0) {
            numberInfo.noNumber = true;
        }
        LOG.trace("getNumberInfo() noNumber={}", numberInfo.noNumber);

        if (numberInfo.noNumber) {
            numberInfo.blockingReason = getBlockingReason(numberInfo);
            LOG.trace("getNumberInfo() blockingReason={}", numberInfo.blockingReason);
            LOG.debug("getNumberInfo() finished early");
            return numberInfo;
        }

        String normalizedNumber = numberInfo.normalizedNumber
                = numberNormalizer.normalizeNumber(number, countryCode);
        LOG.trace("getNumberInfo() normalizedNumber={}", numberInfo.normalizedNumber);

        /*
         * The lists are matched against every form of the number, not just the one the call
         * came in as: a number saved as "+4922147258578" is the same number as the
         * "022147258578" in the call log, and both must find the entry.
         */
        List<String> numberVariants = NumberUtils.getVariants(number, normalizedNumber, countryCode);
        LOG.trace("getNumberInfo() numberVariants={}", numberVariants);

        if (contactsProvider != null) {
            numberInfo.contactItem = contactsProvider.get(number);
        }
        LOG.trace("getNumberInfo() contactItem={}", numberInfo.contactItem);

        if (whitelist != null) {
            numberInfo.whitelistItem = whitelist.getMatch(numberVariants);
            numberInfo.whitelisted = numberInfo.whitelistItem != null;
        }
        LOG.trace("getNumberInfo() whitelisted={}", numberInfo.whitelisted);

        if (numberInfo.contactItem != null || numberInfo.whitelisted) {
            if (numberInfo.contactItem != null) numberInfo.name = numberInfo.contactItem.displayName;

            if (!full) {
                /*
                 * A call from a contact is allowed whatever the databases say about the number,
                 * so when the answer is all that's wanted, there is nothing left to look up.
                 * The full info is still gathered for the screens that show it.
                 */
                LOG.debug("getNumberInfo() the number is allowed, finished early");
                return numberInfo;
            }
        }

        /*
         * The table every source was built into, when there is one: it holds what all of
         * them said, in the order the user put them in, so it is the whole answer - the
         * numbers a later source took out included. Asking the library afterwards would put
         * those back and would miss the sources whose numbers only ever reach the table.
         *
         * Until the first build there is no table, and then the library's own files are all
         * there is to ask.
         */
        if (numbersLookup != null && numbersLookup.isReady()) {
            numberInfo.communityDatabaseItem = numbersLookup.get(normalizedNumber);
        } else if (communityDatabase != null) {
            numberInfo.communityDatabaseItem = communityDatabase.getDbItemByNumber(normalizedNumber);
        }
        LOG.trace("getNumberInfo() communityItem={}", numberInfo.communityDatabaseItem);

        /*
         * The name, out of the same table and nowhere else: every source's business names
         * are read into it beside the numbers, the library's featured slices included, so
         * once it is built the library's files are not opened for a lookup at all. Until
         * then, the library's own files are all there is.
         */
        if (numbersLookup != null && numbersLookup.isReady()) {
            String name = numbersLookup.getName(normalizedNumber);

            numberInfo.featuredDatabaseItem = !TextUtils.isEmpty(name)
                    ? new FeaturedDatabaseItem(parseNumber(normalizedNumber), name) : null;
        } else if (featuredDatabase != null) {
            numberInfo.featuredDatabaseItem = featuredDatabase.getDbItemByNumber(normalizedNumber);
        }
        LOG.trace("getNumberInfo() featuredItem={}", numberInfo.featuredDatabaseItem);

        ContactItem contactItem = numberInfo.contactItem;
        FeaturedDatabaseItem featuredItem = numberInfo.featuredDatabaseItem;
        if (contactItem != null && !TextUtils.isEmpty(contactItem.displayName)) {
            numberInfo.name = contactItem.displayName;
        } else if (featuredItem != null && !TextUtils.isEmpty(featuredItem.getName())) {
            numberInfo.name = featuredItem.getName();
        }
        LOG.trace("getNumberInfo() name={}", numberInfo.name);

        CommunityDatabaseItem communityItem = numberInfo.communityDatabaseItem;
        if (communityItem != null && communityItem.hasRatings()) {
            if (communityItem.getNegativeRatingsCount() > communityItem.getPositiveRatingsCount()
                    + communityItem.getNeutralRatingsCount()) {
                numberInfo.rating = NumberInfo.Rating.NEGATIVE;
            } else if (communityItem.getPositiveRatingsCount() > communityItem.getNeutralRatingsCount()
                    + communityItem.getNegativeRatingsCount()) {
                numberInfo.rating = NumberInfo.Rating.POSITIVE;
            } else {
                numberInfo.rating = NumberInfo.Rating.NEUTRAL;
            }
        }
        LOG.trace("getNumberInfo() rating={}", numberInfo.rating);

        if (settings.getUsePhoneBlock()) {
            long phoneBlockNumber = PhoneBlockService.parseNumber(normalizedNumber);

            if (phoneBlockList != null) {
                numberInfo.phoneBlockRating = phoneBlockList.getRating(phoneBlockNumber);
            }

            // the user's own lists win over what the community says, as PhoneBlock intends
            if (phoneBlockPersonalLists != null && phoneBlockNumber > 0) {
                numberInfo.phoneBlockPersonalAllowed
                        = phoneBlockPersonalLists.isAllowed(phoneBlockNumber);
                numberInfo.phoneBlockPersonalBlocked = !numberInfo.phoneBlockPersonalAllowed
                        && phoneBlockPersonalLists.isBlocked(phoneBlockNumber);
            }
        }
        LOG.trace("getNumberInfo() phoneBlockRating={}, personalAllowed={}, personalBlocked={}",
                numberInfo.phoneBlockRating, numberInfo.phoneBlockPersonalAllowed,
                numberInfo.phoneBlockPersonalBlocked);

        // the flag only spares the screening service the cost of opening the blacklist;
        // the screens show what the lists say about a number, so they always look it up
        if (blacklistService != null && (full || settings.getBlacklistIsNotEmpty())) {
            // avoid loading blacklist if blocking for other reason
            if (full || getBlockingReason(numberInfo) == null) {
                /*
                 * The full lookup matches the list in the app, where every wildcard works and
                 * the entry that is the number itself wins over one that merely covers it;
                 * the answer-only lookup leaves the matching to the database, which is cheaper
                 * during a call.
                 */
                numberInfo.blacklistItem = full
                        ? blacklistService.getFullMatch(numberVariants)
                        : blacklistService.getBlacklistItemForNumber(numberVariants);
            }
        }
        LOG.trace("getNumberInfo() blacklistItem={}", numberInfo.blacklistItem);

        numberInfo.blockingReason = getBlockingReason(numberInfo);
        LOG.trace("getNumberInfo() blockingReason={}", numberInfo.blockingReason);

        LOG.debug("getNumberInfo() finished");
        return numberInfo;
    }

    /**
     * The blacklist entry the number falls under without being it, so that the screens can
     * offer to edit the rule itself rather than only the number.
     *
     * @return null when the number is on the list as itself only, or not at all
     */
    public BlacklistItem getBlacklistRule(NumberInfo numberInfo) {
        if (blacklistService == null || !hasNumber(numberInfo)) return null;

        return blacklistService.getFullRuleMatch(getNumberVariants(numberInfo));
    }

    /** The whitelist entry the number falls under without being it. */
    public WhitelistItem getWhitelistRule(NumberInfo numberInfo) {
        if (whitelist == null || !hasNumber(numberInfo)) return null;

        return whitelist.getRuleMatch(getNumberVariants(numberInfo));
    }

    private static boolean hasNumber(NumberInfo numberInfo) {
        return numberInfo != null && !numberInfo.noNumber && !TextUtils.isEmpty(numberInfo.number);
    }

    private List<String> getNumberVariants(NumberInfo numberInfo) {
        String countryCode = settings.getCachedAutoDetectedCountryCode();

        String normalizedNumber = numberInfo.normalizedNumber != null
                ? numberInfo.normalizedNumber
                : numberNormalizer.normalizeNumber(numberInfo.number, countryCode);

        return NumberUtils.getVariants(numberInfo.number, normalizedNumber, countryCode);
    }

    protected NumberInfo.BlockingReason getBlockingReason(NumberInfo numberInfo) {
        if (isAllowed(numberInfo)) return null;

        if (numberInfo.isHiddenNumber && settings.getBlockHiddenNumbers()) {
            return NumberInfo.BlockingReason.HIDDEN_NUMBER;
        }

        if (numberInfo.rating == NumberInfo.Rating.NEGATIVE
                && settings.getBlockNegativeSiaNumbers()
                && canBlock(NumberInfo.BlockingReason.SIA_RATING)) {
            return NumberInfo.BlockingReason.SIA_RATING;
        }

        if (numberInfo.blacklistItem != null && settings.getBlockBlacklisted()
                && canBlock(NumberInfo.BlockingReason.BLACKLISTED)) {
            return NumberInfo.BlockingReason.BLACKLISTED;
        }

        boolean phoneBlockSpam = numberInfo.phoneBlockPersonalBlocked
                || numberInfo.phoneBlockRating != null && numberInfo.phoneBlockRating.isSpam();

        if (phoneBlockSpam && settings.getBlockPhoneBlock()
                && canBlock(NumberInfo.BlockingReason.PHONE_BLOCK)) {
            return NumberInfo.BlockingReason.PHONE_BLOCK;
        }

        return null;
    }

    /** Whether the user said the number is welcome, whatever any list says about it. */
    public boolean isAllowed(NumberInfo numberInfo) {
        return numberInfo.contactItem != null || numberInfo.whitelisted
                || numberInfo.phoneBlockPersonalAllowed;
    }

    protected boolean canBlock(NumberInfo.BlockingReason reason) {
        if (contactsProvider == null || !contactsProvider.isInLimitedMode()) return true;

        if (reason == NumberInfo.BlockingReason.SIA_RATING
                && settings.isBlockingByRatingInLimitedModeAllowed()) {
            LOG.trace("canBlock() allowed: " + reason);
            return true;
        }

        if (reason == NumberInfo.BlockingReason.BLACKLISTED
                && settings.isBlockingBlacklistedInLimitedModeAllowed()) {
            LOG.trace("canBlock() allowed: " + reason);
            return true;
        }

        LOG.trace("canBlock() not allowed: " + reason);
        return false;
    }

    public boolean shouldBlock(NumberInfo numberInfo) {
        return numberInfo.blockingReason != null;
    }

    /**
     * Whether the ringer should be silenced for the call.
     *
     * <p>The call itself isn't affected: it rings silently, is shown by the phone app
     * and ends up in the call log as usual. Contacts are never silenced,
     * just like they are never blocked.
     */
    public boolean shouldSilence(NumberInfo numberInfo) {
        if (isAllowed(numberInfo)) return false;

        Set<String> ratings = settings.getSilenceCalls();
        if (ratings.isEmpty()) return false;

        // the contacts can't be checked before the device is unlocked:
        // silencing an unrecognized contact would be worse than not silencing a stranger
        if (settings.getUseContacts() && contactsProvider != null
                && contactsProvider.isInLimitedMode()) {
            LOG.debug("shouldSilence() not silencing in limited mode");
            return false;
        }

        switch (numberInfo.rating) {
            case NEGATIVE:
                return ratings.contains(Settings.PREF_SILENCE_CALLS_NEGATIVE);

            case NEUTRAL:
                return ratings.contains(Settings.PREF_SILENCE_CALLS_NEUTRAL);

            case POSITIVE:
                return false;

            default:
                return ratings.contains(Settings.PREF_SILENCE_CALLS_UNKNOWN);
        }
    }

    public void blockedCall(NumberInfo numberInfo) {
        if (blacklistService != null && numberInfo.blacklistItem != null
                && numberInfo.blockingReason == NumberInfo.BlockingReason.BLACKLISTED) {
            blacklistService.addCall(numberInfo.blacklistItem, new Date());
        }
    }

}
