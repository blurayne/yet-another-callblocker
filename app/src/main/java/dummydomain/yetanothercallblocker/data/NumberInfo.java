package dummydomain.yetanothercallblocker.data;

import dummydomain.yetanothercallblocker.data.db.BlacklistItem;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

public class NumberInfo {

    public enum BlockingReason {
        HIDDEN_NUMBER, SIA_RATING, BLACKLISTED, FAILED_VERIFICATION, PHONE_BLOCK
    }

    public enum Rating {
        POSITIVE, NEUTRAL, NEGATIVE, UNKNOWN
    }

    // id
    public String number;
    public String normalizedNumber;

    // info from various sources
    public boolean isHiddenNumber;
    /**
     * Whether the network said the number is not the one the caller is calling from
     * (STIR/SHAKEN). Known for a screened call only, and only where it is deployed.
     */
    public boolean failedVerification;
    /** Whether the user put the number on the whitelist, which allows it whatever else says. */
    public boolean whitelisted;
    public ContactItem contactItem;
    public CommunityDatabaseItem communityDatabaseItem;
    public FeaturedDatabaseItem featuredDatabaseItem;
    public BlacklistItem blacklistItem;
    /** What the PhoneBlock community list says about the number, null if it doesn't know it. */
    public PhoneBlockList.Rating phoneBlockRating;
    /** Whether the number is on the blacklist of the user's own PhoneBlock account. */
    public boolean phoneBlockPersonalBlocked;
    /** Whether the number is on the whitelist of the user's own PhoneBlock account. */
    public boolean phoneBlockPersonalAllowed;

    // computed rating
    public Rating rating = Rating.UNKNOWN;

    // precomputed for convenience
    public boolean noNumber;
    public String name;
    public BlockingReason blockingReason;

}
