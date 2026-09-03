package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import dummydomain.yetanothercallblocker.data.CountryHelper;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;

public class Settings extends GenericSettings {

    public static final String PREF_INCOMING_CALL_NOTIFICATIONS = "incomingCallNotifications";
    public static final String PREF_CALLER_ID_DIRECTORY = "callerIdDirectory";
    public static final String PREF_CALLER_ID_OVERLAY = "callerIdOverlay";
    public static final String PREF_SILENCE_CALLS = "silenceCalls";
    public static final String PREF_BLOCK_NEGATIVE_SIA_NUMBERS = "blockNegativeSiaNumbers";
    public static final String PREF_BLOCK_HIDDEN_NUMBERS = "blockHiddenNumbers";
    public static final String PREF_BLOCK_FAILED_VERIFICATION = "blockFailedVerification";
    public static final String PREF_BLOCK_BLACKLISTED = "blockBlacklisted";
    public static final String PREF_BLACKLIST_IS_NOT_EMPTY = "blacklistIsNotEmpty";
    public static final String PREF_USE_CONTACTS = "useContacts";
    public static final String PREF_WHITELIST = "whitelist";
    public static final String PREF_UI_MODE = "uiMode";
    public static final String PREF_CALL_LOG_GROUPING = "callLogGrouping";
    public static final String PREF_USE_MONITORING_SERVICE = "useMonitoringService";
    public static final String PREF_NOTIFICATIONS_KNOWN = "showNotificationsForKnownCallers";
    public static final String PREF_NOTIFICATIONS_UNKNOWN = "showNotificationsForUnknownCallers";
    public static final String PREF_NOTIFICATIONS_BLOCKED = "showNotificationsForBlockedCalls";
    public static final String PREF_BLOCK_IN_LIMITED_MODE = "blockInLimitedMode";
    public static final String PREF_LAST_UPDATE_TIME = "lastUpdateTime";
    public static final String PREF_LAST_UPDATE_CHECK_TIME = "lastUpdateCheckTime";
    public static final String PREF_DB_FILTERING_ENABLED = "dbFilteringEnabled";
    public static final String PREF_DB_FILTERING_PREFIXES_PREFILLED = "dbFilteringPrefixesPrefilled";
    public static final String PREF_DB_FILTERING_PREFIXES_TO_KEEP = "dbFilteringPrefixesToKeep";
    public static final String PREF_DB_FILTERING_KEEP_MASTER = "dbFilteringKeepMaster";
    public static final String PREF_DB_FILTERED = "dbFiltered";
    public static final String PREF_DB_FILTERING_THOROUGH = "dbFilteringThorough";
    public static final String PREF_DB_FILTERING_KEEP_SHORT_NUMBERS = "dbFilteringKeepShortNumbers";
    public static final String PREF_DB_FILTERING_KEEP_SHORT_NUMBERS_MAX_LENGTH = "dbFilteringKeepShortNumbersMaxLength";
    public static final String PREF_COUNTRY_CODE_OVERRIDE = "countryCodeOverride";
    public static final String PREF_COUNTRY_CODE_FOR_REVIEWS_OVERRIDE = "countryCodeForReviewsOverride";
    public static final String PREF_DATABASE_DOWNLOAD_URL = "databaseDownloadUrl";
    public static final String PREF_USE_PHONE_BLOCK = "usePhoneBlock";
    public static final String PREF_BLOCK_PHONE_BLOCK = "blockPhoneBlock";
    public static final String PREF_PHONE_BLOCK_TOKEN = "phoneBlockToken";
    public static final String PREF_PHONE_BLOCK_URL = "phoneBlockUrl";
    public static final String PREF_PHONE_BLOCK_LAST_UPDATE_TIME = "phoneBlockLastUpdateTime";
    public static final String PREF_PHONE_BLOCK_LAST_FULL_UPDATE_TIME = "phoneBlockLastFullUpdateTime";
    public static final String PREF_PHONE_BLOCK_NEXT_UPDATE_TIME = "phoneBlockNextUpdateTime";
    public static final String PREF_SAVE_CRASHES_TO_EXTERNAL_STORAGE = "saveCrashesToExternalStorage";
    public static final String PREF_SAVE_LOGCAT_ON_CRASH = "saveLogcatOnCrash";

    public static final String PREF_CALL_LOG_GROUPING_NONE = "none";
    public static final String PREF_CALL_LOG_GROUPING_CONSECUTIVE = "consecutive";
    public static final String PREF_CALL_LOG_GROUPING_DAY = "day";

    public static final String PREF_SILENCE_CALLS_NEGATIVE = "negative";
    public static final String PREF_SILENCE_CALLS_NEUTRAL = "neutral";
    public static final String PREF_SILENCE_CALLS_UNKNOWN = "unknown";
    public static final String PREF_SILENCE_CALLS_UNVERIFIED = "unverified";

    public static final String PREF_BLOCK_IN_LIMITED_MODE_RATING = "rating";
    public static final String PREF_BLOCK_IN_LIMITED_MODE_BLACKLIST = "blacklist";

    static final String SYS_PREFERENCES_VERSION = "__preferencesVersion";

    private static final Logger LOG = LoggerFactory.getLogger(Settings.class);

    private static final int PREFERENCES_VERSION = 2;

    private volatile String cachedAutoDetectedCountryCode;

    Settings(Context context) {
        super(context, PreferenceManager.getDefaultSharedPreferences(context));
    }

    private Settings(Context context, String name) {
        super(context, name);
    }

    public void init() {
        int preferencesVersion = getInt(SYS_PREFERENCES_VERSION, -1);

        if (preferencesVersion == PREFERENCES_VERSION) return;

        LOG.info("init() preferencesVersion={}", preferencesVersion);

        String prefBlockCalls = "blockCalls";

        if (preferencesVersion < 1) {
            LOG.debug("init() upgrading to 1");

            PreferenceManager.setDefaultValues(context, R.xml.root_preferences, false);

            Settings oldSettings = new Settings(context, "yacb_preferences");

            if (oldSettings.isSet(PREF_INCOMING_CALL_NOTIFICATIONS)) {
                setIncomingCallNotifications(oldSettings.getIncomingCallNotifications());
            }
            if (oldSettings.isSet(prefBlockCalls)) {
                setBoolean(prefBlockCalls, oldSettings.getBoolean(prefBlockCalls));
            }
            if (oldSettings.isSet(PREF_USE_CONTACTS)) {
                setUseContacts(oldSettings.getUseContacts());
            }
            setLastUpdateTime(oldSettings.getLastUpdateTime());
            setLastUpdateCheckTime(oldSettings.getLastUpdateCheckTime());
        }
        if (preferencesVersion < 2) {
            LOG.debug("init() upgrading to 2");

            if (isSet(prefBlockCalls)) {
                setBlockNegativeSiaNumbers(getBoolean(prefBlockCalls));
                unset(prefBlockCalls);
            }
        }

        setInt(SYS_PREFERENCES_VERSION, PREFERENCES_VERSION);
        LOG.debug("init() finished upgrade");
    }

    public boolean getIncomingCallNotifications() {
        return getBoolean(PREF_INCOMING_CALL_NOTIFICATIONS, true);
    }

    public void setIncomingCallNotifications(boolean show) {
        setBoolean(PREF_INCOMING_CALL_NOTIFICATIONS, show);
    }

    /** Whether the caller info is provided to the phone app as a contacts directory. */
    public boolean getCallerIdDirectory() {
        return getBoolean(PREF_CALLER_ID_DIRECTORY, true);
    }

    public void setCallerIdDirectory(boolean enabled) {
        setBoolean(PREF_CALLER_ID_DIRECTORY, enabled);
    }

    /** Whether the caller info is drawn over the incoming call screen. */
    public boolean getCallerIdOverlay() {
        return getBoolean(PREF_CALLER_ID_OVERLAY);
    }

    public void setCallerIdOverlay(boolean enabled) {
        setBoolean(PREF_CALLER_ID_OVERLAY, enabled);
    }

    /** Whether any of the features that display the caller info during a call is enabled. */
    public boolean getCallerIdEnabled() {
        return getCallerIdDirectory() || getCallerIdOverlay();
    }

    /** The ratings the ringer is silenced for. */
    public Set<String> getSilenceCalls() {
        return getStringSet(PREF_SILENCE_CALLS, Collections::emptySet);
    }

    public void setSilenceCalls(Set<String> values) {
        setStringSet(PREF_SILENCE_CALLS, values);
    }

    public boolean getSilenceCallsEnabled() {
        return !getSilenceCalls().isEmpty();
    }

    /** Whether the PhoneBlock community list is kept on the device and used. */
    public boolean getUsePhoneBlock() {
        return getBoolean(PREF_USE_PHONE_BLOCK);
    }

    public void setUsePhoneBlock(boolean use) {
        setBoolean(PREF_USE_PHONE_BLOCK, use);
    }

    /** Whether the numbers of that list are blocked rather than only shown. */
    public boolean getBlockPhoneBlock() {
        return getBoolean(PREF_BLOCK_PHONE_BLOCK);
    }

    public void setBlockPhoneBlock(boolean block) {
        setBoolean(PREF_BLOCK_PHONE_BLOCK, block);
    }

    public String getPhoneBlockToken() {
        return getString(PREF_PHONE_BLOCK_TOKEN);
    }

    public String getPhoneBlockUrl() {
        return getNonEmptyString(PREF_PHONE_BLOCK_URL,
                dummydomain.yetanothercallblocker.data.PhoneBlockService.DEFAULT_URL);
    }

    public long getPhoneBlockLastUpdateTime() {
        return getLong(PREF_PHONE_BLOCK_LAST_UPDATE_TIME, 0);
    }

    public void setPhoneBlockLastUpdateTime(long time) {
        setLong(PREF_PHONE_BLOCK_LAST_UPDATE_TIME, time);
    }

    public long getPhoneBlockLastFullUpdateTime() {
        return getLong(PREF_PHONE_BLOCK_LAST_FULL_UPDATE_TIME, 0);
    }

    public void setPhoneBlockLastFullUpdateTime(long time) {
        setLong(PREF_PHONE_BLOCK_LAST_FULL_UPDATE_TIME, time);
    }

    public long getPhoneBlockNextUpdateTime() {
        return getLong(PREF_PHONE_BLOCK_NEXT_UPDATE_TIME, 0);
    }

    public void setPhoneBlockNextUpdateTime(long time) {
        setLong(PREF_PHONE_BLOCK_NEXT_UPDATE_TIME, time);
    }

    public boolean getCallBlockingEnabled() {
        return getBlockNegativeSiaNumbers() || getBlockHiddenNumbers() || getBlacklistEnabled()
                || getBlockFailedVerification()
                || getUsePhoneBlock() && getBlockPhoneBlock();
    }

    /**
     * Whether calls the network says carry a forged number are blocked.
     *
     * @see dummydomain.yetanothercallblocker.CallScreeningServiceImpl
     */
    public boolean getBlockFailedVerification() {
        return getBoolean(PREF_BLOCK_FAILED_VERIFICATION);
    }

    public void setBlockFailedVerification(boolean block) {
        setBoolean(PREF_BLOCK_FAILED_VERIFICATION, block);
    }

    public boolean getBlockNegativeSiaNumbers() {
        return getBoolean(PREF_BLOCK_NEGATIVE_SIA_NUMBERS);
    }

    public void setBlockNegativeSiaNumbers(boolean block) {
        setBoolean(PREF_BLOCK_NEGATIVE_SIA_NUMBERS, block);
    }

    public boolean getBlockHiddenNumbers() {
        return getBoolean(PREF_BLOCK_HIDDEN_NUMBERS);
    }

    public void setBlockHiddenNumbers(boolean block) {
        setBoolean(PREF_BLOCK_HIDDEN_NUMBERS, block);
    }

    public boolean getBlacklistEnabled() {
        return getBlockBlacklisted() && getBlacklistIsNotEmpty();
    }

    public boolean getBlockBlacklisted() {
        return getBoolean(PREF_BLOCK_BLACKLISTED, true);
    }

    public void setBlockBlacklisted(boolean block) {
        setBoolean(PREF_BLOCK_BLACKLISTED, block);
    }

    public boolean getBlacklistIsNotEmpty() {
        return getBoolean(PREF_BLACKLIST_IS_NOT_EMPTY);
    }

    public void setBlacklistIsNotEmpty(boolean flag) {
        setBoolean(PREF_BLACKLIST_IS_NOT_EMPTY, flag);
    }

    /** The numbers that are never blocked, one pattern per line. */
    public String getWhitelist() {
        return getString(PREF_WHITELIST, "");
    }

    public void setWhitelist(String whitelist) {
        setString(PREF_WHITELIST, whitelist);
    }

    public boolean getUseContacts() {
        return getBoolean(PREF_USE_CONTACTS, true);
    }

    public void setUseContacts(boolean use) {
        setBoolean(PREF_USE_CONTACTS, use);
    }

    public int getUiMode() {
        return getInt(PREF_UI_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setUiMode(int mode) {
        setInt(PREF_UI_MODE, mode);
    }

    public String getCallLogGrouping() {
        return getString(PREF_CALL_LOG_GROUPING, PREF_CALL_LOG_GROUPING_CONSECUTIVE);
    }

    public void setCallLogGrouping(String value) {
        setString(PREF_CALL_LOG_GROUPING, value);
    }

    public boolean getUseMonitoringService() {
        return getBoolean(PREF_USE_MONITORING_SERVICE);
    }

    public void setUseMonitoringService(boolean use) {
        setBoolean(PREF_USE_MONITORING_SERVICE, use);
    }

    public boolean getNotificationsForKnownCallers() {
        return getBoolean(PREF_NOTIFICATIONS_KNOWN);
    }

    public void setNotificationsForKnownCallers(boolean show) {
        setBoolean(PREF_NOTIFICATIONS_KNOWN, show);
    }

    public boolean getNotificationsForUnknownCallers() {
        return getBoolean(PREF_NOTIFICATIONS_UNKNOWN);
    }

    public void setNotificationsForUnknownCallers(boolean show) {
        setBoolean(PREF_NOTIFICATIONS_UNKNOWN, show);
    }

    public boolean getNotificationsForBlockedCalls() {
        return getBoolean(PREF_NOTIFICATIONS_BLOCKED, true);
    }

    public void setNotificationsForBlockedCalls(boolean show) {
        setBoolean(PREF_NOTIFICATIONS_BLOCKED, show);
    }

    public boolean isBlockingByRatingInLimitedModeAllowed() {
        return getBlockInLimitedMode().contains(PREF_BLOCK_IN_LIMITED_MODE_RATING);
    }

    public boolean isBlockingBlacklistedInLimitedModeAllowed() {
        return getBlockInLimitedMode().contains(PREF_BLOCK_IN_LIMITED_MODE_BLACKLIST);
    }

    public Set<String> getBlockInLimitedMode() {
        return getStringSet(PREF_BLOCK_IN_LIMITED_MODE, () ->
                new HashSet<>(Arrays.asList(context.getResources()
                        .getStringArray(R.array.block_in_limited_mode_default_values))));
    }

    public void setBlockInLimitedMode(Set<String> value) {
        setStringSet(PREF_BLOCK_IN_LIMITED_MODE, value);
    }

    public long getLastUpdateTime() {
        return getLong(PREF_LAST_UPDATE_TIME, 0);
    }

    public void setLastUpdateTime(long timestamp) {
        setLong(PREF_LAST_UPDATE_TIME, timestamp);
    }

    public long getLastUpdateCheckTime() {
        return getLong(PREF_LAST_UPDATE_CHECK_TIME, 0);
    }

    public void setLastUpdateCheckTime(long timestamp) {
        setLong(PREF_LAST_UPDATE_CHECK_TIME, timestamp);
    }

    public boolean isDbFilteringEnabled() {
        return getBoolean(PREF_DB_FILTERING_ENABLED);
    }

    public void setDbFilteringEnabled(boolean enabled) {
        setBoolean(PREF_DB_FILTERING_ENABLED, enabled);
    }

    public boolean isDbFilteringPrefixesPrefilled() {
        return getBoolean(PREF_DB_FILTERING_PREFIXES_PREFILLED);
    }

    public void setDbFilteringPrefixesPrefilled(boolean prefilled) {
        setBoolean(PREF_DB_FILTERING_PREFIXES_PREFILLED, prefilled);
    }

    public String getDbFilteringPrefixesToKeep() {
        return getString(PREF_DB_FILTERING_PREFIXES_TO_KEEP);
    }

    public void setDbFilteringPrefixesToKeep(String prefixes) {
        setString(PREF_DB_FILTERING_PREFIXES_TO_KEEP, prefixes);
    }

    /** Whether a copy of the unfiltered database is kept when the database is filtered. */
    public boolean getDbFilteringKeepMaster() {
        return getBoolean(PREF_DB_FILTERING_KEEP_MASTER, true);
    }

    public void setDbFilteringKeepMaster(boolean keep) {
        setBoolean(PREF_DB_FILTERING_KEEP_MASTER, keep);
    }

    /** Whether the database in use has been filtered. */
    public boolean isDbFiltered() {
        return getBoolean(PREF_DB_FILTERED);
    }

    public void setDbFiltered(boolean filtered) {
        setBoolean(PREF_DB_FILTERED, filtered);
    }

    public boolean isDbFilteringThorough() {
        return getBoolean(PREF_DB_FILTERING_THOROUGH, true);
    }

    public void setDbFilteringThorough(boolean thorough) {
        setBoolean(PREF_DB_FILTERING_THOROUGH, thorough);
    }

    public boolean getDbFilteringKeepShortNumbers() {
        return getBoolean(PREF_DB_FILTERING_KEEP_SHORT_NUMBERS, true);
    }

    public void setDbFilteringKeepShortNumbers(boolean keep) {
        setBoolean(PREF_DB_FILTERING_KEEP_SHORT_NUMBERS, keep);
    }

    public int getDbFilteringKeepShortNumbersMaxLength() {
        return getInt(PREF_DB_FILTERING_KEEP_SHORT_NUMBERS_MAX_LENGTH, 5);
    }

    public void setDbFilteringKeepShortNumbersMaxLength(int length) {
        setInt(PREF_DB_FILTERING_KEEP_SHORT_NUMBERS_MAX_LENGTH, length);
    }

    public String getCountryCodeOverride() {
        return getString(PREF_COUNTRY_CODE_OVERRIDE);
    }

    public void setCountryCodeOverride(String code) {
        setString(PREF_COUNTRY_CODE_OVERRIDE, code);
    }

    public String getCountryCodeForReviewsOverride() {
        return getString(PREF_COUNTRY_CODE_FOR_REVIEWS_OVERRIDE);
    }

    public void setCountryCodeForReviewsOverride(String code) {
        setString(PREF_COUNTRY_CODE_FOR_REVIEWS_OVERRIDE, code);
    }

    public String getDatabaseDownloadUrl() {
        return getNonEmptyString(PREF_DATABASE_DOWNLOAD_URL, DbManager.DEFAULT_URL);
    }

    public void setDatabaseDownloadUrl(String url) {
        setString(PREF_DATABASE_DOWNLOAD_URL, url);
    }

    public String getCountryCode() {
        String override = getCountryCodeOverride();
        if (!TextUtils.isEmpty(override)) return override.toUpperCase(Locale.ROOT);

        return getCachedAutoDetectedCountryCode();
    }

    public String getCountryCodeForReviews() {
        String override = getCountryCodeForReviewsOverride();
        if (!TextUtils.isEmpty(override)) return override.toUpperCase(Locale.ROOT);

        String code = getCachedAutoDetectedCountryCode();
        return !TextUtils.isEmpty(code) ? code : "US";
    }

    public String getCachedAutoDetectedCountryCode() {
        String code = cachedAutoDetectedCountryCode;
        if (code == null) {
            code = CountryHelper.detectCountry(context);
            if (TextUtils.isEmpty(code)) code = "";

            cachedAutoDetectedCountryCode = code;
        }
        return code;
    }

    public boolean getSaveCrashesToExternalStorage() {
        return getBoolean(PREF_SAVE_CRASHES_TO_EXTERNAL_STORAGE);
    }

    public void setSaveCrashesToExternalStorage(boolean flag) {
        setBoolean(PREF_SAVE_CRASHES_TO_EXTERNAL_STORAGE, flag);
    }

    public boolean getSaveLogcatOnCrash() {
        return getBoolean(PREF_SAVE_LOGCAT_ON_CRASH);
    }

    public void setSaveLogcatOnCrash(boolean flag) {
        setBoolean(PREF_SAVE_LOGCAT_ON_CRASH, flag);
    }

}
