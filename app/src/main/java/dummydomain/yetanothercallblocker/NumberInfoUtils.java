package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.PhoneBlockList;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

public class NumberInfoUtils {

    public static String getShortDescription(Context context, NumberInfo numberInfo) {
        if (numberInfo.communityDatabaseItem != null) {
            NumberCategory category = NumberCategory.getById(
                    numberInfo.communityDatabaseItem.getCategory());

            if (category != null && category != NumberCategory.NONE) {
                return SiaNumberCategoryUtils.getName(context, category);
            }
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null) {
            return context.getString(R.string.info_in_blacklist);
        }

        if (numberInfo.phoneBlockRating != null && numberInfo.phoneBlockRating.isSpam()) {
            return getPhoneBlockDescription(context, numberInfo.phoneBlockRating);
        }

        return null;
    }

    /**
     * What PhoneBlock says about the number, the user's own lists first, or null if nothing.
     */
    public static String getPhoneBlockStatus(Context context, NumberInfo numberInfo) {
        if (numberInfo.phoneBlockPersonalAllowed) {
            return context.getString(R.string.info_phone_block_personal_allowed);
        }

        if (numberInfo.phoneBlockPersonalBlocked) {
            return context.getString(R.string.info_phone_block_personal_blocked);
        }

        if (numberInfo.phoneBlockRating != null && numberInfo.phoneBlockRating.isSpam()) {
            return getPhoneBlockDescription(context, numberInfo.phoneBlockRating);
        }

        return null;
    }

    /** What the PhoneBlock community says the number is used for. */
    public static String getPhoneBlockDescription(Context context, PhoneBlockList.Rating rating) {
        return context.getString(R.string.phone_block_description,
                getPhoneBlockRatingName(context, rating));
    }

    /** The name of a PhoneBlock rating on its own. */
    public static String getPhoneBlockRatingName(Context context, PhoneBlockList.Rating rating) {
        int resId;
        switch (rating) {
            case LEGITIMATE: resId = R.string.phone_block_rating_legitimate; break;
            case PING: resId = R.string.phone_block_rating_ping; break;
            case POLL: resId = R.string.phone_block_rating_poll; break;
            case ADVERTISING: resId = R.string.phone_block_rating_advertising; break;
            case GAMBLE: resId = R.string.phone_block_rating_gamble; break;
            case FRAUD: resId = R.string.phone_block_rating_fraud; break;
            case MISSED: resId = R.string.phone_block_rating_missed; break;
            default: resId = R.string.phone_block_rating_unknown; break;
        }

        return context.getString(resId);
    }

    /**
     * Returns a name to show instead of the number (the "caller ID"),
     * or {@code null} if nothing is known about the number.
     *
     * @see CallerIdDirectoryProvider
     */
    public static String getCallerIdName(Context context, NumberInfo numberInfo) {
        if (numberInfo == null || numberInfo.noNumber) return null;

        FeaturedDatabaseItem featuredItem = numberInfo.featuredDatabaseItem;
        if (featuredItem != null && !TextUtils.isEmpty(featuredItem.getName())) {
            return featuredItem.getName();
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null
                && !TextUtils.isEmpty(numberInfo.blacklistItem.getName())) {
            return numberInfo.blacklistItem.getName();
        }

        String shortDescription = getShortDescription(context, numberInfo);
        if (!TextUtils.isEmpty(shortDescription)) return shortDescription;

        switch (numberInfo.rating) {
            case NEGATIVE:
                return context.getString(R.string.notification_incoming_call_negative);

            case POSITIVE:
                return context.getString(R.string.notification_incoming_call_positive);

            case NEUTRAL:
                return context.getString(R.string.notification_incoming_call_neutral);

            default:
                return null;
        }
    }

    /**
     * Returns additional info to show next to the {@link #getCallerIdName(Context, NumberInfo) name}
     * (the ratings summary), or {@code null} if there's nothing to add.
     */
    public static String getCallerIdLabel(Context context, NumberInfo numberInfo) {
        if (numberInfo == null || numberInfo.noNumber) return null;

        CommunityDatabaseItem communityItem = numberInfo.communityDatabaseItem;
        if (communityItem != null && communityItem.hasRatings()) {
            return context.getString(R.string.notification_incoming_call_text_description,
                    communityItem.getNegativeRatingsCount(),
                    communityItem.getPositiveRatingsCount(),
                    communityItem.getNeutralRatingsCount());
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null) {
            return context.getString(R.string.info_in_blacklist);
        }

        if (numberInfo.phoneBlockRating != null && numberInfo.phoneBlockRating.isSpam()) {
            return getPhoneBlockDescription(context, numberInfo.phoneBlockRating);
        }

        return null;
    }

}
