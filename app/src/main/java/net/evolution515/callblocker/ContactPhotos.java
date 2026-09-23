package net.evolution515.callblocker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.widget.ImageViewCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

import net.evolution515.callblocker.data.ContactItem;

/**
 * A contact's own photo, round, in place of the rating icon.
 *
 * <p>The thumbnails the contacts keep are small - a few kilobytes, 96 pixels or so - so they
 * are read where the row is bound, and kept: a call log is the same few people over and over,
 * and scrolling back up shouldn't read them again. A contact without a photo, or one that
 * can't be read, keeps the icon the row already has.
 */
public class ContactPhotos {

    private static final Logger LOG = LoggerFactory.getLogger(ContactPhotos.class);

    /** What a contact turned out not to have, so that it isn't asked again. */
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            2 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getRowBytes() * value.getHeight();
        }
    };

    private ContactPhotos() {
    }

    /** Puts the contact's photo on the view, when there is one; leaves the view alone otherwise. */
    public static void apply(AppCompatImageView view, ContactItem contact) {
        if (contact == null || TextUtils.isEmpty(contact.photoUri)) return;

        Bitmap bitmap = load(view.getContext(), contact.photoUri);
        if (bitmap == null) return;

        RoundedBitmapDrawable round = RoundedBitmapDrawableFactory.create(
                view.getResources(), bitmap);
        round.setCircular(true);

        // the rating icon is tinted; a photo isn't
        ImageViewCompat.setImageTintList(view, null);
        view.setImageDrawable(round);
    }

    private static Bitmap load(Context context, String uri) {
        Bitmap cached = CACHE.get(uri);
        if (cached != null) return cached != NONE ? cached : null;

        Bitmap bitmap = null;

        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in != null) bitmap = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            LOG.debug("load() couldn't read {}", uri, e);
        }

        CACHE.put(uri, bitmap != null ? bitmap : NONE);

        return bitmap;
    }

}
