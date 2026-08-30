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
    final boolean noInfo;

    private IconAndColor(int iconResId, int colorResId) {
        this(iconResId, colorResId, false);
    }

    private IconAndColor(int icon, int color, boolean noInfo) {
        this.iconResId = icon;
        this.colorResId = color;
        this.noInfo = noInfo;
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
        return new IconAndColor(R.drawable.ic_thumbs_up_down_24dp, R.color.notFound, true);
    }

    /**
     * The icon for a number: a blacklisted one is marked as such, in red when the database
     * has a bad opinion of it too. Contacts are never treated as blacklisted,
     * the same way they are never blocked.
     */
    static IconAndColor forNumberInfo(NumberInfo numberInfo) {
        // a forged number says more about the call than anything known about the number itself
        if (numberInfo.failedVerification) {
            return of(R.drawable.ic_shield_s_24dp, R.color.rateNegative);
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null) {
            return of(R.drawable.ic_incognito_24dp,
                    numberInfo.rating == NumberInfo.Rating.NEGATIVE
                            ? R.color.rateNegative : R.color.blacklisted);
        }

        return forNumberRating(numberInfo.rating, numberInfo.contactItem != null);
    }

    static IconAndColor forNumberRating(NumberInfo.Rating rating, boolean contact) {
        boolean noInfo = false;
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
                noInfo = true;
                icon = R.drawable.ic_question_mark_24dp;
                color = R.color.notFound;
                break;
        }

        if (contact) {
            noInfo = false;
            icon = R.drawable.ic_person_24dp;
        }

        return new IconAndColor(icon, color, noInfo);
    }
}
