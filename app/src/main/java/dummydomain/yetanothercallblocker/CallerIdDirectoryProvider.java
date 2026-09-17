package dummydomain.yetanothercallblocker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.PhoneLookup;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.NumberInfoCache;
import dummydomain.yetanothercallblocker.data.NumberInfoService;
import dummydomain.yetanothercallblocker.data.YacbHolder;

import static dummydomain.yetanothercallblocker.utils.StringUtils.quote;

/**
 * Exposes the number info as a contacts directory, so that the phone app can display it
 * on the incoming call screen (and in the call log, and in the contacts search).
 *
 * <p>The Contacts Provider discovers the directory providers by the {@code ContactDirectory}
 * meta-data in the manifest and asks them for the list of directories they provide.
 * The phone app then queries every remote directory for the numbers it can't resolve locally
 * (see {@code CallerInfoAsyncQuery.startOtherDirectoriesQuery()} in AOSP Dialer),
 * which is what makes the info appear in its UI without any dedicated API.
 *
 * <p>Contacts are never reported here: the phone app resolves those on its own,
 * and it only asks the directories if the local lookup found nothing.
 */
public class CallerIdDirectoryProvider extends ContentProvider {

    private static final int DIRECTORIES = 1;
    private static final int PHONE_LOOKUP = 2;

    private static final Logger LOG = LoggerFactory.getLogger(CallerIdDirectoryProvider.class);

    private static final String[] DEFAULT_DIRECTORY_PROJECTION = new String[]{
            Directory.ACCOUNT_NAME,
            Directory.ACCOUNT_TYPE,
            Directory.DISPLAY_NAME,
            Directory.TYPE_RESOURCE_ID,
            Directory.EXPORT_SUPPORT,
            Directory.SHORTCUT_SUPPORT,
            Directory.PHOTO_SUPPORT
    };

    // the projection that the AOSP Dialer uses for the incoming call screen
    private static final String[] DEFAULT_PHONE_LOOKUP_PROJECTION = new String[]{
            PhoneLookup._ID,
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.LOOKUP_KEY,
            PhoneLookup.NUMBER,
            PhoneLookup.NORMALIZED_NUMBER,
            PhoneLookup.LABEL,
            PhoneLookup.TYPE
    };

    /** Same as {@code ContactsContract.PhoneLookup.CONTACT_ID}, which requires API 24. */
    private static final String COLUMN_CONTACT_ID = "contact_id";

    private UriMatcher uriMatcher;

    public static String getAuthority() {
        return BuildConfig.APPLICATION_ID + ".directory";
    }

    /** Asks the Contacts Provider to re-read the list of directories we provide. */
    public static void notifyDirectoryChanged(Context context) {
        LOG.debug("notifyDirectoryChanged()");

        try {
            Directory.notifyDirectoryChange(context.getContentResolver());
        } catch (Exception e) {
            LOG.warn("notifyDirectoryChanged() failed", e);
        }
    }

    @Override
    public boolean onCreate() {
        String authority = getAuthority();

        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(authority, "directories", DIRECTORIES);
        uriMatcher.addURI(authority, "phone_lookup/*", PHONE_LOOKUP);

        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        LOG.debug("query({})", uri);

        try {
            switch (uriMatcher.match(uri)) {
                case DIRECTORIES:
                    return getDirectoriesCursor(projection);

                case PHONE_LOOKUP:
                    return getPhoneLookupCursor(uri, projection);

                default:
                    // the other parts of the contacts API (the search by name and such)
                    // aren't supported: an empty cursor means "nothing found"
                    LOG.debug("query() unsupported uri");
                    return emptyCursor(projection);
            }
        } catch (Exception e) {
            // the caller is the phone app - it must not be affected by our errors
            LOG.error("query() failed", e);
            return emptyCursor(projection);
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private static MatrixCursor emptyCursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[0]);
    }

    private Cursor getDirectoriesCursor(String[] projection) {
        if (projection == null) projection = DEFAULT_DIRECTORY_PROJECTION;

        MatrixCursor cursor = new MatrixCursor(projection);

        // an empty cursor means "no directories", which is how the feature is turned off
        if (!isEnabled()) {
            LOG.debug("getDirectoriesCursor() the feature is disabled");
            return cursor;
        }

        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            row[i] = getDirectoryValue(projection[i]);
        }
        cursor.addRow(row);

        return cursor;
    }

    private Object getDirectoryValue(String column) {
        Context context = getContext();

        if (Directory.ACCOUNT_NAME.equals(column) || Directory.DISPLAY_NAME.equals(column)) {
            return context != null ? context.getString(R.string.app_name) : null;
        }
        if (Directory.ACCOUNT_TYPE.equals(column)) {
            return BuildConfig.APPLICATION_ID;
        }
        if (Directory.TYPE_RESOURCE_ID.equals(column)) {
            return R.string.app_name;
        }
        if (Directory.EXPORT_SUPPORT.equals(column)) {
            return Directory.EXPORT_SUPPORT_NONE;
        }
        if (Directory.SHORTCUT_SUPPORT.equals(column)) {
            return Directory.SHORTCUT_SUPPORT_NONE;
        }
        if (Directory.PHOTO_SUPPORT.equals(column)) {
            return Directory.PHOTO_SUPPORT_NONE;
        }

        return null;
    }

    private Cursor getPhoneLookupCursor(Uri uri, String[] projection) {
        if (projection == null) projection = DEFAULT_PHONE_LOOKUP_PROJECTION;

        MatrixCursor cursor = new MatrixCursor(projection);

        if (!isEnabled()) return cursor;

        String number = uri.getLastPathSegment();
        if (TextUtils.isEmpty(number)) return cursor;

        if (Boolean.parseBoolean(uri.getQueryParameter(
                PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS))) {
            LOG.debug("getPhoneLookupCursor() ignoring a SIP address");
            return cursor;
        }

        NumberInfo numberInfo = getNumberInfo(number);
        if (numberInfo == null) return cursor;

        if (numberInfo.contactItem != null) {
            LOG.debug("getPhoneLookupCursor() the number is a contact");
            return cursor;
        }

        Context context = getContext();
        if (context == null) return cursor;

        String name = NumberInfoUtils.getCallerIdName(context, numberInfo);
        if (TextUtils.isEmpty(name)) {
            // nothing is known about the number - let the phone app display it as is
            LOG.debug("getPhoneLookupCursor() no info for {}", quote(number));
            return cursor;
        }

        String label = NumberInfoUtils.getCallerIdLabel(context, numberInfo);

        LOG.info("getPhoneLookupCursor() reporting {} for {}", quote(name), quote(number));

        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            row[i] = getPhoneLookupValue(projection[i], numberInfo, number, name, label);
        }
        cursor.addRow(row);

        return cursor;
    }

    private Object getPhoneLookupValue(String column, NumberInfo numberInfo,
                                       String number, String name, String label) {
        if (PhoneLookup._ID.equals(column) || COLUMN_CONTACT_ID.equals(column)) {
            return getSyntheticId(number);
        }
        if (PhoneLookup.DISPLAY_NAME.equals(column)) {
            return name;
        }
        if (PhoneLookup.NUMBER.equals(column)) {
            // the number must be returned exactly as it was queried:
            // the phone app drops the result if it doesn't match the requested number
            return number;
        }
        if (PhoneLookup.NORMALIZED_NUMBER.equals(column)) {
            return numberInfo.normalizedNumber;
        }
        if (PhoneLookup.LABEL.equals(column)) {
            return label;
        }
        if (PhoneLookup.TYPE.equals(column)) {
            return label != null ? (Integer) Phone.TYPE_CUSTOM : null;
        }
        if (PhoneLookup.LOOKUP_KEY.equals(column)) {
            return BuildConfig.APPLICATION_ID + ":" + number;
        }

        // photos, ringtones, etc. are not provided
        return null;
    }

    private NumberInfo getNumberInfo(String number) {
        NumberInfoCache cache = YacbHolder.getNumberInfoCache();

        NumberInfo numberInfo = cache != null ? cache.get(number) : null;
        if (numberInfo != null) {
            LOG.debug("getNumberInfo() using the cached info");
            return numberInfo;
        }

        NumberInfoService numberInfoService = YacbHolder.getNumberInfoService();
        Settings settings = App.getSettings();
        if (numberInfoService == null || settings == null) return null;

        numberInfo = numberInfoService.getNumberInfo(number,
                settings.getCachedAutoDetectedCountryCode(), true);

        if (cache != null) cache.put(number, numberInfo);

        return numberInfo;
    }

    private boolean isEnabled() {
        Context context = getContext();
        if (context == null) return false;

        // a query may arrive before App.onCreate() has finished
        App.ensureInitialized(context.getApplicationContext());

        Settings settings = App.getSettings();
        return settings != null && settings.getCallerIdDirectory();
    }

    /**
     * The phone app uses the id to load a contact photo and such,
     * so it has to be non-zero and it must not look like
     * an {@link ContactsContract.Contacts#isEnterpriseContactId(long) enterprise} id.
     */
    private static long getSyntheticId(String number) {
        return Math.abs((long) number.hashCode()) % 100000000L + 1L;
    }

}
