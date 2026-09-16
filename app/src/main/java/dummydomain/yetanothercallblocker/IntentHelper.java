package dummydomain.yetanothercallblocker;

import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IntentHelper {

    private static final Logger LOG = LoggerFactory.getLogger(IntentHelper.class);

    public static Uri getUriForPhoneNumber(String number) {
        return Uri.parse("tel:" + (!TextUtils.isEmpty(number) ? number : "private"));
    }

    public static PendingIntent pendingActivity(Context context, Intent intent) {
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    /** Opens the contact the number belongs to in the contacts app. */
    public static Intent getViewContactIntent(long contactId) {
        return new Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId));
    }

    /**
     * Looks the number up on the web, in whatever app handles web addresses.
     *
     * <p>It is an ordinary address rather than a search intent, so that it opens in the
     * browser instead of whichever app claims searches.
     */
    public static Intent getWebSearchIntent(String number) {
        Uri uri = Uri.parse("https://www.google.com/search").buildUpon()
                .appendQueryParameter("q", number)
                .build();

        return new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
    }

    /** Lets the user store the number, either as a new contact or in an existing one. */
    public static Intent getAddToContactsIntent(String number) {
        Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
        intent.putExtra(ContactsContract.Intents.Insert.PHONE, number);
        return intent;
    }

    public static Intent clearTop(Intent intent) {
        return intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    public static boolean startActivity(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            LOG.warn("startActivity() error starting activity", e);
        }
        return false;
    }

}
