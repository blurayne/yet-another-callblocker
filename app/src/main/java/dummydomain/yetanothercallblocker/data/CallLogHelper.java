package dummydomain.yetanothercallblocker.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dummydomain.yetanothercallblocker.PermissionHelper;

public class CallLogHelper {

    private static final String[] QUERY_PROJECTION = new String[]{
            CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.NUMBER,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
    };

    /** Says whether a number was withheld, which is more reliable than guessing from the number. */
    private static final String[] QUERY_PROJECTION_WITH_PRESENTATION = new String[]{
            CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.NUMBER,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.NUMBER_PRESENTATION
    };

    public static List<CallLogItem> loadCalls(Context context, Long anchorId, boolean before,
                                              int limit) {
        if (!PermissionHelper.hasCallLogPermission(context)) {
            return new ArrayList<>();
        }

        boolean reverseOrder = false;

        String selection;
        String[] selectionArgs;
        if (anchorId != null) {
            if (before) {
                selection = CallLog.Calls._ID + " > ?";
                reverseOrder = true;
            } else {
                selection = CallLog.Calls._ID + " < ?";
            }
            selectionArgs = new String[]{String.valueOf(anchorId)};
        } else {
            selection = null;
            selectionArgs = null;
        }

        Uri uri = CallLog.Calls.CONTENT_URI;

        String sortOrder = CallLog.Calls.DATE + " " + (reverseOrder ? "ASC" : "DESC");

        // should probably work since JELLY_BEAN_MR1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            uri = uri.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, String.valueOf(limit))
                    .build();
        } else {
            sortOrder += " limit " + limit;
        }

        List<CallLogItem> items = new ArrayList<>(limit);

        String[] projection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                ? QUERY_PROJECTION_WITH_PRESENTATION : QUERY_PROJECTION;

        try (Cursor cursor = context.getContentResolver()
                .query(uri, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID);
                int typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
                int dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
                int durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);
                int presentationIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER_PRESENTATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idIndex);
                    int callType = cursor.getInt(typeIndex);
                    String number = cursor.getString(numberIndex);
                    long callDate = cursor.getLong(dateIndex);
                    long callDuration = cursor.getLong(durationIndex);

                    CallLogItem.Presentation presentation = presentationIndex != -1
                            ? CallLogItem.Presentation.fromProviderValue(
                                    cursor.getInt(presentationIndex))
                            : CallLogItem.Presentation.ALLOWED;

                    // a number the app can't do anything with is the same as none at all,
                    // and it makes the calls of one kind group together
                    if (!presentation.hasNumber() || NumberUtils.isHiddenNumber(number)) {
                        if (presentation.hasNumber()) presentation = CallLogItem.Presentation.UNKNOWN;
                        number = null;
                    }

                    items.add(new CallLogItem(id, CallLogItem.Type.fromProviderType(callType),
                            number, presentation, callDate, callDuration));
                }
            }
        }

        if (reverseOrder) {
            Collections.reverse(items);
        }

        return items;
    }

}
