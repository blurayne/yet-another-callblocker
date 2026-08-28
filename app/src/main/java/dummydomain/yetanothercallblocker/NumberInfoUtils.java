package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import dummydomain.yetanothercallblocker.data.NumberInfo;
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

        return null;
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

        return null;
    }

}
