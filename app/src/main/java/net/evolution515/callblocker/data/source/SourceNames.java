package net.evolution515.callblocker.data.source;

import android.content.Context;
import android.text.TextUtils;

import net.evolution515.callblocker.R;

/**
 * What a source is called, wherever it is mentioned.
 *
 * <p>Its own name when it has one, and what kind of source it is when it doesn't - so that a
 * row in a list, a line in the build log and a message about a failure all say the same
 * thing about the same source.
 */
public class SourceNames {

    private SourceNames() {
    }

    public static int getTypeName(NumberSource.Type type) {
        switch (type) {
            case PHONE_BLOCK: return R.string.source_type_phone_block;
            case CARDDAV: return R.string.source_type_carddav;
            default: return R.string.source_type_database;
        }
    }

    public static String getName(Context context, NumberSource source) {
        if (source == null) return "";

        return !TextUtils.isEmpty(source.getName())
                ? source.getName() : context.getString(getTypeName(source.getType()));
    }

}
