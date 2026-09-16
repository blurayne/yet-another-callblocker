package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.widget.ImageViewCompat;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.sia.model.CommunityReview;

class IconAndColor {

    @DrawableRes
    final int iconResId;
    @ColorRes
    final int colorResId;

    private IconAndColor(int icon, int color) {
        this.iconResId = icon;
        this.colorResId = color;
    }

    @ColorInt
    int getColorInt(@NonNull Context context) {
        return UiUtils.getColorInt(context, colorResId);
    }

    void applyToImageView(AppCompatImageView imageView) {
        imageView.setImageResource(iconResId);
        ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(
                getColorInt(imageView.getContext())));
    }

    static IconAndColor of(@DrawableRes int icon, @ColorRes int color) {
        return new IconAndColor(icon, color);
    }

    static IconAndColor forReviewRating(CommunityReview.Rating rating) {
        switch (rating) {
            case NEUTRAL:
                return of(R.drawable.ic_thumbs_up_down_24dp, R.color.rateNeutral);
            case POSITIVE:
                return of(R.drawable.ic_thumb_up_24dp, R.color.ratePositive);
            case NEGATIVE:
                return of(R.drawable.ic_thumb_down_24dp, R.color.rateNegative);
        }
        return of(R.drawable.ic_thumbs_up_down_24dp, R.color.notFound);
    }

    /**
     * The icon for a number: what the user said about it, then what the app would do about a
     * call from it, then what is known about it otherwise.
     *
     * <p>The lists win over everything else, in the call log as much as during a call: a number
     * that has been put on one of them since is shown as being on it, whether or not the call
     * it was heard on was treated that way at the time.
     */
    static IconAndColor forNumberInfo(NumberInfo numberInfo) {
        // a forged number says more about the call than anything known about the number itself
        if (numberInfo.failedVerification) {
            return of(R.drawable.ic_shield_s_24dp, R.color.rateNegative);
        }

        if (numberInfo.whitelisted) {
            return of(R.drawable.ic_check_24dp, R.color.ratePositive);
        }

        if (numberInfo.blacklistItem != null) {
            return of(R.drawable.ic_middle_finger_24dp, R.color.rateNegative);
        }

        if (numberInfo.contactItem == null && isBlockedAsSpam(numberInfo)) {
            return of(R.drawable.ic_spam_24dp, R.color.rateNegative);
        }

        return forNumberRating(numberInfo.rating, numberInfo.contactItem != null);
    }

    /** Whether the number is one a list of unwanted callers had something to say about. */
    private static boolean isBlockedAsSpam(NumberInfo numberInfo) {
        return numberInfo.blockingReason == NumberInfo.BlockingReason.SIA_RATING
                || numberInfo.blockingReason == NumberInfo.BlockingReason.PHONE_BLOCK;
    }

    static IconAndColor forNumberRating(NumberInfo.Rating rating, boolean contact) {
        @DrawableRes int icon;
        @ColorInt int color;

        switch (rating) {
            case NEUTRAL:
                icon = R.drawable.ic_thumbs_up_down_24dp;
                color = R.color.rateNeutral;
                break;

            case POSITIVE:
                icon = R.drawable.ic_thumb_up_24dp;
                color = R.color.ratePositive;
                break;

            case NEGATIVE:
                icon = R.drawable.ic_thumb_down_24dp;
                color = R.color.rateNegative;
                break;

            default:
                icon = R.drawable.ic_question_mark_24dp;
                color = R.color.notFound;
                break;
        }

        if (contact) {
            icon = R.drawable.ic_person_24dp;
        }

        return of(icon, color);
    }
}
