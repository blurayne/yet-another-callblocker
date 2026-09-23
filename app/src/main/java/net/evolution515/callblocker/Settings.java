package net.evolution515.callblocker;

import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.evolution515.callblocker.data.CountryHelper;
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
    public static final String PREF_CALL_DECISIONS = "callDecisions";
    public static final String PREF_BLOCKING_PAUSED_UNTIL = "blockingPausedUntil";
    public static final String PREF_NUMBER_SOURCES = "numberSources";
    public static final String PREF_SOURCE_SECRETS = "sourceSecrets";
    public static final String PREF_SOURCES_MIGRATED = "sourcesMigrated";
    public static final String PREF_PROVIDERS = "providers";
    public static final String PREF_PROVIDER_SECRETS = "providerSecrets";
    public static final String PREF_PROVIDER_PASSWORDS = "providerPasswords";
    public static final String PREF_PROVIDERS_SEEDED = "providersSeeded";
    public static final String PREF_PROVIDERS_SEEDED_VERSION = "providersSeededVersion";
    public static final String PREF_BACKUP_DIRECTORY = "backupDirectory";
    public static final String PREF_BACKUP_SECRETS = "backupSecrets";
    public static final String PREF_AUTO_BACKUP = "autoBackup";
    public static final String PREF_LAST_BACKUP_TIME = "lastBackupTime";
    public static final String PREF_BACKUP_DATABASE = "backupDatabase";
    public static final String PREF_LAST_BACKUP_DB_BUILD = "lastBackupDbBuild";
    public static final String PREF_UI_MODE = "uiMode";
    public static final String PREF_CALL_LOG_GROUPING = "callLogGrouping";
    public static final String PREF_USE_MONITORING_SERVICE = "useMonitoringService";
    public static final String PREF_NOTIFICATIONS_KNOWN = "showNotificationsForKnownCallers";
    public static final String PREF_NOTIFICATIONS_UNKNOWN = "showNotificationsForUnknownCallers";
    public static final String PREF_NOTIFICATIONS_BLOCKED = "showNotificationsForBlockedCalls";
    public static final String PREF_BLOCK_IN_LIMITED_MODE = "blockInLimitedMode";
    public static final String PREF_AUTO_UPDATE_SET_UP = "autoUpdateSetUp";
    public static final String PREF_LAST_UPDATE_TIME = "lastUpdateTime";
    public static final String PREF_LAST_UPDATE_CHECK_TIME = "lastUpdateCheckTime";
    public static final String PREF_LAST_DB_BUILD_ERROR = "lastDbBuildError";
    public static final String PREF_DB_BUILD_RUNNING = "dbBuildRunning";
    public static final String PREF_LAST_DB_BUILD_ERROR_TIME = "lastDbBuildErrorTime";
    public static final String PREF_NOTIFY_AUTO_UPDATES = "notifyAutoUpdates";
    public static final String PREF_CALLER_ID_TEMPLATE = "callerIdTemplate";
    public static final String PREF_DB_FILTERING_ENABLED = "dbFilteringEnabled";
    public static final String PREF_DB_FILTERING_PREFIXES_PREFILLED = "dbFilteringPrefixesPrefilled";
    public static final String PREF_DB_FILTERING_PREFIXES_TO_KEEP = "dbFilteringPrefixesToKeep";
    public static final String PREF_DB_FILTERING_PATTERN = "dbFilteringPattern";
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
    public static final String PREF_PHONE_BLOCK_PERSONAL_NEXT_UPDATE_TIME
            = "phoneBlockPersonalNextUpdateTime";
    public static final String PREF_PHONE_BLOCK_TOKEN_VALID = "phoneBlockTokenValid";
    public static final String PREF_PHONE_BLOCK_LAST_TOKEN_CHECK_TIME = "phoneBlockLastTokenCheckTime";
    public static final String PREF_PHONE_BLOCK_TOKEN_PROBLEM_NOTIFIED
            = "phoneBlockTokenProblemNotified";
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
        return getBoolean(PREF_CALLER_ID_OVERLAY, true);
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
        return getBoolean(PREF_USE_PHONE_BLOCK, true);
    }

    public void setUsePhoneBlock(boolean use) {
        setBoolean(PREF_USE_PHONE_BLOCK, use);
    }

    /** Whether the numbers of that list are blocked rather than only shown. */
    public boolean getBlockPhoneBlock() {
        return getBoolean(PREF_BLOCK_PHONE_BLOCK, true);
    }

    public void setBlockPhoneBlock(boolean block) {
        setBoolean(PREF_BLOCK_PHONE_BLOCK, block);
    }

    public String getPhoneBlockToken() {
        return getString(PREF_PHONE_BLOCK_TOKEN);
    }

    public void setPhoneBlockUrl(String url) {
        setString(PREF_PHONE_BLOCK_URL, url);
    }

    public void setPhoneBlockToken(String token) {
        setString(PREF_PHONE_BLOCK_TOKEN, token);
    }

    public String getPhoneBlockUrl() {
        return getNonEmptyString(PREF_PHONE_BLOCK_URL,
                net.evolution515.callblocker.data.PhoneBlockService.DEFAULT_URL);
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

    /** When the lists of the user's own PhoneBlock account are fetched again. */
    public long getPhoneBlockPersonalNextUpdateTime() {
        return getLong(PREF_PHONE_BLOCK_PERSONAL_NEXT_UPDATE_TIME, 0);
    }

    public void setPhoneBlockPersonalNextUpdateTime(long time) {
        setLong(PREF_PHONE_BLOCK_PERSONAL_NEXT_UPDATE_TIME, time);
    }

    /** Whether the API token was accepted the last time it was used. */
    public boolean getPhoneBlockTokenValid() {
        return getBoolean(PREF_PHONE_BLOCK_TOKEN_VALID, true);
    }

    public void setPhoneBlockTokenValid(boolean valid) {
        setBoolean(PREF_PHONE_BLOCK_TOKEN_VALID, valid);
    }

    public long getPhoneBlockLastTokenCheckTime() {
        return getLong(PREF_PHONE_BLOCK_LAST_TOKEN_CHECK_TIME, 0);
    }

    public void setPhoneBlockLastTokenCheckTime(long time) {
        setLong(PREF_PHONE_BLOCK_LAST_TOKEN_CHECK_TIME, time);
    }

    /** Whether the user was told that the token stopped working (so they're told once). */
    public boolean getPhoneBlockTokenProblemNotified() {
        return getBoolean(PREF_PHONE_BLOCK_TOKEN_PROBLEM_NOTIFIED, false);
    }

    public void setPhoneBlockTokenProblemNotified(boolean notified) {
        setBoolean(PREF_PHONE_BLOCK_TOKEN_PROBLEM_NOTIFIED, notified);
    }

    /** Forgets what is known about the token, so that the new one is checked again. */
    public void resetPhoneBlockTokenState() {
        setPhoneBlockTokenValid(true);
        setPhoneBlockLastTokenCheckTime(0);
        setPhoneBlockTokenProblemNotified(false);
        setPhoneBlockPersonalNextUpdateTime(0); // the lists belong to whoever the token belongs to
    }

    /**
     * When the pause on blocking runs out, {@code Long.MAX_VALUE} while it is paused until the
     * user says otherwise, or 0 when nothing is paused.
     */
    public long getBlockingPausedUntil() {
        return getLong(PREF_BLOCKING_PAUSED_UNTIL, 0);
    }

    public void setBlockingPausedUntil(long time) {
        setLong(PREF_BLOCKING_PAUSED_UNTIL, time);
    }

    /**
     * Whether blocking and silencing are paused at the moment.
     *
     * <p>It is the pause itself that runs out, not a timer that ends it: nothing has to run
     * while the phone sleeps, and a pause set before a restart is still over when it is over.
     */
    public boolean isBlockingPaused() {
        return getBlockingPausedUntil() > System.currentTimeMillis();
    }

    public boolean getCallBlockingEnabled() {
        return getBlockNegativeSiaNumbers() || getBlockHiddenNumbers() || getBlacklistEnabled()
                || getBlockFailedVerification()
                || getUsePhoneBlock() && getBlockPhoneBlock();
    }

    /**
     * Whether calls the network says carry a forged number are blocked.
     *
     * @see net.evolution515.callblocker.CallScreeningServiceImpl
     */
    public boolean getBlockFailedVerification() {
        return getBoolean(PREF_BLOCK_FAILED_VERIFICATION, true);
    }

    public void setBlockFailedVerification(boolean block) {
        setBoolean(PREF_BLOCK_FAILED_VERIFICATION, block);
    }

    public boolean getBlockNegativeSiaNumbers() {
        return getBoolean(PREF_BLOCK_NEGATIVE_SIA_NUMBERS, true);
    }

    public void setBlockNegativeSiaNumbers(boolean block) {
        setBoolean(PREF_BLOCK_NEGATIVE_SIA_NUMBERS, block);
    }

    public boolean getBlockHiddenNumbers() {
        return getBoolean(PREF_BLOCK_HIDDEN_NUMBERS, true);
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

    /** The places the app gets numbers from, as {@code SourceService} writes them. */
    public String getNumberSources() {
        return getString(PREF_NUMBER_SOURCES, "");
    }

    public void setNumberSources(String sources) {
        setString(PREF_NUMBER_SOURCES, sources);
    }

    /**
     * The passwords and tokens of those sources, apart from the list itself so that a backup
     * can hold the one without the other.
     */
    public String getSourceSecrets() {
        return getString(PREF_SOURCE_SECRETS, "");
    }

    public void setSourceSecrets(String secrets) {
        setString(PREF_SOURCE_SECRETS, secrets);
    }

    /** Whether what older versions kept has been turned into the list of sources. */
    /** The providers the dialog about a call offers, as JSON. */
    public String getProviders() {
        return getString(PREF_PROVIDERS, "");
    }

    public void setProviders(String providers) {
        setString(PREF_PROVIDERS, providers);
    }

    /** The tokens of the providers, kept out of a backup unless the user asks. */
    public String getProviderSecrets() {
        return getString(PREF_PROVIDER_SECRETS, "");
    }

    public void setProviderSecrets(String secrets) {
        setString(PREF_PROVIDER_SECRETS, secrets);
    }

    /**
     * The passwords of the providers, kept apart from the tokens above.
     *
     * <p>A token and a password are not the same thing: the token is a key the provider
     * handed out for its API and can hand out again, the password belongs to a login and to
     * whoever typed it. They are written down separately so that changing one never touches
     * the other.
     */
    public String getProviderPasswords() {
        return getString(PREF_PROVIDER_PASSWORDS, "");
    }

    public void setProviderPasswords(String passwords) {
        setString(PREF_PROVIDER_PASSWORDS, passwords);
    }

    /** Whether the ones the app knows have been put into the list; see the version below. */
    public boolean getProvidersSeeded() {
        return getBoolean(PREF_PROVIDERS_SEEDED, false);
    }

    public void setProvidersSeeded(boolean seeded) {
        setBoolean(PREF_PROVIDERS_SEEDED, seeded);
    }

    /**
     * How far the list of known providers had got when they were last put in.
     *
     * <p>A version the app has already offered is never offered again, so that one the user
     * deleted stays deleted while a newly known one still arrives.
     */
    public int getProvidersSeededVersion() {
        return getInt(PREF_PROVIDERS_SEEDED_VERSION, 0);
    }

    public void setProvidersSeededVersion(int version) {
        setInt(PREF_PROVIDERS_SEEDED_VERSION, version);
    }

    public boolean getSourcesMigrated() {
        return getBoolean(PREF_SOURCES_MIGRATED, false);
    }

    public void setSourcesMigrated(boolean migrated) {
        setBoolean(PREF_SOURCES_MIGRATED, migrated);
    }

    /** Where the backup is kept: a directory the user picked, as a document tree. */
    public String getBackupDirectory() {
        return getString(PREF_BACKUP_DIRECTORY);
    }

    public void setBackupDirectory(String uri) {
        setString(PREF_BACKUP_DIRECTORY, uri);
    }

    /**
     * Whether passwords and tokens go into the backup.
     *
     * <p>Off unless the user says so: a backup is a file that gets copied around, and what is
     * in it is readable by whoever ends up with it.
     */
    public boolean getBackupSecrets() {
        return getBoolean(PREF_BACKUP_SECRETS, false);
    }

    public void setBackupSecrets(boolean backup) {
        setBoolean(PREF_BACKUP_SECRETS, backup);
    }

    /**
     * Whether the downloaded database goes into the backup as well.
     *
     * <p>On, because a phone that is set up again from the backup should be able to block a
     * call without first downloading tens of megabytes over whatever connection it has. It
     * is written only when it has changed, so the daily backup doesn't copy it every day.
     */
    public boolean getBackupDatabase() {
        return getBoolean(PREF_BACKUP_DATABASE, true);
    }

    public void setBackupDatabase(boolean backup) {
        setBoolean(PREF_BACKUP_DATABASE, backup);
    }

    /** When the database in the backup directory was built, which says whether it is current. */
    public long getLastBackupDbBuild() {
        return getLong(PREF_LAST_BACKUP_DB_BUILD, 0);
    }

    public void setLastBackupDbBuild(long built) {
        setLong(PREF_LAST_BACKUP_DB_BUILD, built);
    }

    /** Whether the app writes the backup by itself. Off until the user says otherwise. */
    public boolean getAutoBackup() {
        return getBoolean(PREF_AUTO_BACKUP, false);
    }

    public void setAutoBackup(boolean enabled) {
        setBoolean(PREF_AUTO_BACKUP, enabled);
    }

    public long getLastBackupTime() {
        return getLong(PREF_LAST_BACKUP_TIME, 0);
    }

    public void setLastBackupTime(long time) {
        setLong(PREF_LAST_BACKUP_TIME, time);
    }

    /** The numbers that are never blocked, one pattern per line. */
    public String getWhitelist() {
        return getString(PREF_WHITELIST, "");
    }

    public void setWhitelist(String whitelist) {
        setString(PREF_WHITELIST, whitelist);
    }

    /** What the app did about the last calls; written by {@code CallDecisionLog}. */
    public String getCallDecisions() {
        return getString(PREF_CALL_DECISIONS, "");
    }

    public void setCallDecisions(String value) {
        setString(PREF_CALL_DECISIONS, value);
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
        return getString(PREF_CALL_LOG_GROUPING, PREF_CALL_LOG_GROUPING_NONE);
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

    /**
     * Whether the automatic updates were ever set up. They are on to begin with, but turning
     * them off has to stick, so this says the difference between "not set up yet" and "not wanted".
     */
    public boolean getAutoUpdateSetUp() {
        return getBoolean(PREF_AUTO_UPDATE_SET_UP);
    }

    public void setAutoUpdateSetUp(boolean setUp) {
        setBoolean(PREF_AUTO_UPDATE_SET_UP, setUp);
    }

    public long getLastUpdateTime() {
        return getLong(PREF_LAST_UPDATE_TIME, 0);
    }

    /**
     * How the caller is written for the phone app, or an empty string for the app's own way.
     *
     * @see CallerIdTemplate
     */
    public String getCallerIdTemplate() {
        return getString(PREF_CALLER_ID_TEMPLATE, "");
    }

    public void setCallerIdTemplate(String template) {
        setString(PREF_CALLER_ID_TEMPLATE, template != null ? template : "");
    }

    /**
     * Whether a build was running when the app was last heard from.
     *
     * <p>Written down because a build that is killed - by the system, for memory, or by the
     * user - says nothing on its way out. Finding this still set at the next start is how the
     * app knows that the last build never finished, and the only way to say so afterwards.
     */
    public boolean getDbBuildRunning() {
        return getBoolean(PREF_DB_BUILD_RUNNING, false);
    }

    public void setDbBuildRunning(boolean running) {
        setBoolean(PREF_DB_BUILD_RUNNING, running);
    }

    /**
     * What went wrong the last time the database was built, or an empty string.
     *
     * <p>A build runs in a service and can end while nobody is looking at the app, so the
     * reason is written down rather than only said once: the notification says it when it
     * happens, and the database screen still says it afterwards.
     */
    public String getLastDbBuildError() {
        return getString(PREF_LAST_DB_BUILD_ERROR, "");
    }

    public void setLastDbBuildError(String error) {
        setString(PREF_LAST_DB_BUILD_ERROR, error != null ? error : "");
    }

    public long getLastDbBuildErrorTime() {
        return getLong(PREF_LAST_DB_BUILD_ERROR_TIME, 0);
    }

    public void setLastDbBuildErrorTime(long timestamp) {
        setLong(PREF_LAST_DB_BUILD_ERROR_TIME, timestamp);
    }

    /**
     * Whether the updates that run on their own say so in the notification drawer.
     *
     * <p>Off by default: they run daily and in the background, and a notification for
     * something nobody asked for at that moment is noise. What the user started by hand
     * always shows, whatever this says.
     */
    public boolean getNotifyAutoUpdates() {
        return getBoolean(PREF_NOTIFY_AUTO_UPDATES, false);
    }

    public void setNotifyAutoUpdates(boolean notify) {
        setBoolean(PREF_NOTIFY_AUTO_UPDATES, notify);
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

    /** What is kept when the database is built, as a pattern; everything else is dropped. */
    public static final String DEFAULT_DB_FILTERING_PATTERN = "+{42,49,41}*";

    /**
     * Which numbers are worth keeping, written as a pattern.
     *
     * <p>It used to be a list of country codes, which is one shape of the same question and
     * not the only useful one: a pattern says the same thing ({@code +{49,43}*}) and can also
     * say "German mobile numbers only" ({@code +4915*}). A list that was set before is read as
     * the pattern it amounts to, so nobody has to rewrite theirs.
     */
    public String getDbFilteringPattern() {
        String pattern = getString(PREF_DB_FILTERING_PATTERN, null);
        if (!TextUtils.isEmpty(pattern)) return pattern;

        String prefixes = getDbFilteringPrefixesToKeep();
        if (TextUtils.isEmpty(prefixes)) return DEFAULT_DB_FILTERING_PATTERN;

        List<String> parts = new ArrayList<>();
        for (String prefix : prefixes.split("[,;]")) {
            prefix = prefix.replaceAll("[^0-9]", "");
            if (!prefix.isEmpty() && !parts.contains(prefix)) parts.add(prefix);
        }

        if (parts.isEmpty()) return DEFAULT_DB_FILTERING_PATTERN;

        return parts.size() == 1
                ? "+" + parts.get(0) + "*"
                : "+{" + TextUtils.join(",", parts) + "}*";
    }

    public void setDbFilteringPattern(String pattern) {
        setString(PREF_DB_FILTERING_PATTERN, pattern);
    }

    public String getDbFilteringPrefixesToKeep() {
        return getString(PREF_DB_FILTERING_PREFIXES_TO_KEEP);
    }

    public void setDbFilteringPrefixesToKeep(String prefixes) {
        setString(PREF_DB_FILTERING_PREFIXES_TO_KEEP, prefixes);
    }

    public boolean isDbFilteringThorough() {
        return getBoolean(PREF_DB_FILTERING_THOROUGH, true);
    }

    public void setDbFilteringThorough(boolean thorough) {
        setBoolean(PREF_DB_FILTERING_THOROUGH, thorough);
    }

    public boolean getDbFilteringKeepShortNumbers() {
        return getBoolean(PREF_DB_FILTERING_KEEP_SHORT_NUMBERS, false);
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
