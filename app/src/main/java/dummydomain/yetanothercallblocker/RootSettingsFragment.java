package dummydomain.yetanothercallblocker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dummydomain.yetanothercallblocker.data.BackupService;
import dummydomain.yetanothercallblocker.data.BlacklistService;
import dummydomain.yetanothercallblocker.data.CallDecisionLog;
import dummydomain.yetanothercallblocker.data.Whitelist;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.utils.FileUtils;
import dummydomain.yetanothercallblocker.utils.PackageManagerUtils;
import dummydomain.yetanothercallblocker.work.UpdateScheduler;

public class RootSettingsFragment extends BaseSettingsFragment {

    private static final Logger LOG = LoggerFactory.getLogger(RootSettingsFragment.class);

    private static final String PREF_SCREEN_ROOT = null;
    private static final String PREF_USE_CALL_SCREENING_SERVICE = "useCallScreeningService";
    private static final String PREF_AUTO_UPDATE_ENABLED = "autoUpdateEnabled";
    private static final String PREF_NOTIFICATION_CHANNEL_SETTINGS = "notificationChannelSettings";
    private static final String PREF_BLOCKING_STATUS = "blockingStatus";
    private static final String PREF_BLACKLIST_SCREEN = "blacklistScreen";
    private static final String PREF_WHITELIST_SCREEN = "whitelistScreen";
    private static final String PREF_DB_MANAGEMENT = "dbManagement";
    private static final String PREF_PHONE_BLOCK_SCREEN = "phoneBlockScreen";
    private static final String PREF_NOTIFICATIONS_BLOCKED_NON_PERSISTENT = "showNotificationsForBlockedCallsNonPersistent";
    private static final String PREF_BACKUP_EXPORT = "backupExport";
    private static final String PREF_BACKUP_IMPORT = "backupImport";

    private static final int REQUEST_CODE_IMPORT_BACKUP = 131; // 128-130 are taken by the permission helpers

    /** How far back the row about blocking looks when it says what blocking has done. */
    private static final long STATS_PERIOD = 30 * DateUtils.DAY_IN_MILLIS;

    private static final String STATE_REQUEST_TOKEN = "STATE_REQUEST_TOKEN";
    private static final String STATE_OVERLAY_REQUESTED = "STATE_OVERLAY_REQUESTED";

    private final UpdateScheduler updateScheduler = UpdateScheduler.get(App.getInstance());

    private PermissionHelper.RequestToken requestToken;
    private boolean overlayPermissionRequested;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Settings settings = App.getSettings();

        PermissionHelper.handlePermissionsResult(requireContext(),
                requestCode, permissions, grantResults,
                settings.getIncomingCallNotifications(), settings.getCallBlockingEnabled(),
                settings.getUseContacts());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (PermissionHelper.handleCallScreeningResult(
                requireActivity(), requestCode, resultCode, requestToken)) {
            updateCallScreeningPreference();
            return;
        }

        if (requestCode == REQUEST_CODE_IMPORT_BACKUP && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            readBackup(data.getData());
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        requestToken = PermissionHelper.RequestToken
                .fromSavedInstanceState(savedInstanceState, STATE_REQUEST_TOKEN);

        overlayPermissionRequested = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_OVERLAY_REQUESTED);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (requestToken != null) {
            requestToken.onSaveInstanceState(outState, STATE_REQUEST_TOKEN);
        }

        outState.putBoolean(STATE_OVERLAY_REQUESTED, overlayPermissionRequested);
    }

    @Override
    public void onStart() {
        super.onStart();

        // may be changed externally
        updateCallScreeningPreference();

        // needs to be updated after the confirmation dialog was closed
        // due to activity recreation (orientation change, etc.)
        updateBlockedCallNotificationsPreference();

        // the permission may be granted (or revoked) in the system settings
        updateCallerIdOverlayPreference();

        // all of these change on the screens this one leads to
        updateBlockingStatusPreference();
        updateListPreferences();
        updateSourcePreferences();
    }

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN_ROOT;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.root_preferences;
    }

    @Override
    protected void initScreen() {
        setPrefChangeListener(Settings.PREF_INCOMING_CALL_NOTIFICATIONS, (pref, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                PermissionHelper.checkPermissions(requireContext(), this,
                        true, false, false);
            }
            return true;
        });

        // the row says what tapping it does - start a pause, or end the one that is running
        requirePreference(PREF_BLOCKING_STATUS).setOnPreferenceClickListener(preference -> {
            BlockingPauseHelper.show(requireActivity(), this::updateBlockingStatusPreference);
            return true;
        });

        // the file holds the user's own numbers, and the token is not in it: both are said
        // before anything is written or read
        requirePreference(PREF_BACKUP_EXPORT).setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.backup_export)
                    .setMessage(R.string.backup_export_message)
                    .setPositiveButton(R.string.backup_export_confirmation, (d, w) -> writeBackup())
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        requirePreference(PREF_BACKUP_IMPORT).setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.backup_import)
                    .setMessage(R.string.backup_import_message)
                    .setPositiveButton(R.string.backup_import_confirmation, (d, w) -> pickBackup())
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        requirePreference(PREF_BLACKLIST_SCREEN).setOnPreferenceClickListener(preference -> {
            startActivity(BlacklistActivity.getIntent(requireContext()));
            return true;
        });

        requirePreference(PREF_WHITELIST_SCREEN).setOnPreferenceClickListener(preference -> {
            startActivity(WhitelistActivity.getIntent(requireContext()));
            return true;
        });

        setPrefChangeListener(Settings.PREF_CALLER_ID_DIRECTORY, (preference, newValue) -> {
            // the value has to be stored before the Contacts Provider re-reads the directories
            App.getSettings().setCallerIdDirectory(Boolean.TRUE.equals(newValue));

            CallerIdDirectoryProvider.notifyDirectoryChanged(requireContext());

            return true;
        });

        setPrefChangeListener(Settings.PREF_CALLER_ID_OVERLAY, (preference, newValue) -> {
            // below Android 6 the permission is granted on install, there's nothing to request
            if (Boolean.TRUE.equals(newValue)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !PermissionHelper.hasOverlayPermission(requireContext())) {
                overlayPermissionRequested = true;

                PermissionHelper.requestOverlayPermission(requireActivity());

                return false; // enabled in updateCallerIdOverlayPreference() if granted
            }
            return true;
        });

        Preference.OnPreferenceChangeListener callBlockingListener = (preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                PermissionHelper.checkPermissions(requireContext(), this,
                        false, true, false);
            }
            return true;
        };
        setPrefChangeListener(Settings.PREF_BLOCK_NEGATIVE_SIA_NUMBERS, callBlockingListener);
        setPrefChangeListener(Settings.PREF_BLOCK_HIDDEN_NUMBERS, callBlockingListener);
        setPrefChangeListener(Settings.PREF_BLOCK_BLACKLISTED, callBlockingListener);
        setPrefChangeListener(Settings.PREF_BLOCK_FAILED_VERIFICATION, callBlockingListener);

        // the network only reports a forged number since Android 11
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requirePreference(Settings.PREF_BLOCK_FAILED_VERIFICATION).setVisible(false);
        }

        SwitchPreferenceCompat callScreeningPref =
                requirePreference(PREF_USE_CALL_SCREENING_SERVICE);
        callScreeningPref.setChecked(PermissionHelper.isCallScreeningHeld(requireContext()));
        callScreeningPref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                requestToken = PermissionHelper.requestCallScreening(requireActivity(), this);
            } else {
                PermissionHelper.disableCallScreening(requireActivity());
                return false;
            }
            return true;
        });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callScreeningPref.setVisible(false);
        }

        // silencing is done through the call screening service, which can only do it on Android 10+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requirePreference(Settings.PREF_SILENCE_CALLS).setVisible(false);
        }

        setPrefChangeListener(Settings.PREF_USE_MONITORING_SERVICE, (pref, newValue) -> {
            boolean enabled = Boolean.TRUE.equals(newValue);
            Context context = requireContext();

            PackageManagerUtils.setComponentEnabledOrDefault(
                    context, StartupReceiver.class, enabled);
            if (enabled) {
                CallMonitoringService.start(context);
            } else {
                CallMonitoringService.stop(context);
            }

            return true;
        });

        SwitchPreferenceCompat nonPersistentAutoUpdatePref =
                requirePreference(PREF_AUTO_UPDATE_ENABLED);
        nonPersistentAutoUpdatePref.setChecked(updateScheduler.isAutoUpdateScheduled());
        nonPersistentAutoUpdatePref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                updateScheduler.scheduleAutoUpdates();
            } else {
                updateScheduler.cancelAutoUpdateWorker();
            }
            return true;
        });

        setPrefChangeListener(Settings.PREF_USE_CONTACTS, (preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                PermissionHelper.checkPermissions(requireContext(), this,
                        false, false, true);
            }
            return true;
        });

        setPrefChangeListener(Settings.PREF_UI_MODE, (preference, newValue) -> {
            App.setUiMode(Integer.parseInt((String) newValue));
            return true;
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requirePreference(PREF_NOTIFICATION_CHANNEL_SETTINGS)
                    .setOnPreferenceClickListener(preference -> {
                        NotificationHelper.initNotificationChannels(requireContext());

                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,
                                BuildConfig.APPLICATION_ID);
                        startActivity(intent);
                        return true;
                    });

            // the system settings hold these since Android 8
            requirePreference(Settings.PREF_NOTIFICATIONS_KNOWN).setVisible(false);
            requirePreference(Settings.PREF_NOTIFICATIONS_UNKNOWN).setVisible(false);
            requirePreference(PREF_NOTIFICATIONS_BLOCKED_NON_PERSISTENT).setVisible(false);
        } else {
            requirePreference(PREF_NOTIFICATION_CHANNEL_SETTINGS).setVisible(false);

            SwitchPreferenceCompat blockedCallNotificationsPref =
                    requirePreference(PREF_NOTIFICATIONS_BLOCKED_NON_PERSISTENT);
            blockedCallNotificationsPref.setChecked(
                    App.getSettings().getNotificationsForBlockedCalls());
            blockedCallNotificationsPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    App.getSettings().setNotificationsForBlockedCalls(true);
                } else {
                    new AlertDialog.Builder(requireActivity())
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.blocked_call_notifications_disable_message)
                            .setPositiveButton(R.string.blocked_call_notifications_disable_confirmation,
                                    (d, w) -> App.getSettings().setNotificationsForBlockedCalls(false))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setOnDismissListener(d -> updateBlockedCallNotificationsPreference())
                            .show();
                }
                return true;
            });
        }
    }

    /** Writes the settings and both lists to a file and hands it to whatever can keep it. */
    private void writeBackup() {
        File file = new File(requireContext().getCacheDir(), getBackupFileName());

        try {
            String backup = new BackupService()
                    .write(App.getSettings(), YacbHolder.getBlacklistDao());

            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8")) {
                writer.write(backup);
            }
        } catch (IOException | JSONException e) {
            LOG.warn("writeBackup()", e);

            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
            return;
        }

        FileUtils.shareFile(requireActivity(), file);
    }

    private static String getBackupFileName() {
        return "YetAnotherCallBlocker_backup_"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                + ".json";
    }

    private void pickBackup() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // a file manager may not know what a .json file is

        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_BACKUP);
        } catch (ActivityNotFoundException e) {
            LOG.warn("pickBackup()", e);

            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Puts back what is in the file, and says what came out of it. */
    private void readBackup(Uri uri) {
        BackupService.Result result = null;

        try (InputStream inputStream = requireContext().getContentResolver()
                .openInputStream(uri)) {
            if (inputStream != null) {
                result = new BackupService().read(inputStream, App.getSettings(),
                        YacbHolder.getBlacklistDao(), YacbHolder.getBlacklistService(),
                        YacbHolder.getWhitelistService());
            }
        } catch (IOException e) {
            LOG.warn("readBackup()", e);
        }

        if (result == null || !result.ok) {
            Toast.makeText(requireContext(), R.string.backup_import_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(requireContext(), getString(R.string.backup_import_result,
                result.settings, result.blacklistItems, result.whitelistItems),
                Toast.LENGTH_LONG).show();

        applyImportedSettings();

        // the screen shows the values that were just replaced
        requireActivity().recreate();
    }

    /**
     * Puts the settings that were just read into effect.
     *
     * <p>Most of them are read when a call comes in and need nothing done about them. These
     * few are acted on when they change, which is what an import is.
     */
    private void applyImportedSettings() {
        Settings settings = App.getSettings();
        Context context = requireContext();

        App.setUiMode(settings.getUiMode());

        CallerIdDirectoryProvider.notifyDirectoryChanged(context);

        boolean monitoring = settings.getUseMonitoringService();
        PackageManagerUtils.setComponentEnabledOrDefault(context, StartupReceiver.class, monitoring);
        if (monitoring) {
            CallMonitoringService.start(context);
        } else {
            CallMonitoringService.stop(context);
        }
    }

    /** The row about blocking: what tapping it does, how things stand, and what it has done. */
    private void updateBlockingStatusPreference() {
        Context context = requireContext();

        Preference preference = requirePreference(PREF_BLOCKING_STATUS);
        preference.setTitle(BlockingPauseHelper.getActionTitle(context));
        preference.setSummary(BlockingPauseHelper.getStatus(context)
                + "\n" + getBlockingStats(context));
    }

    /** What the app did about the calls it saw lately, or that it has seen none. */
    private String getBlockingStats(Context context) {
        CallDecisionLog decisionLog = YacbHolder.getCallDecisionLog();

        CallDecisionLog.Stats stats = decisionLog != null
                ? decisionLog.getStats(System.currentTimeMillis() - STATS_PERIOD) : null;

        if (stats == null || stats.getTotal() == 0) {
            return context.getString(R.string.blocking_stats_none);
        }

        return context.getString(R.string.blocking_stats,
                stats.blocked, stats.silenced, stats.getTotal());
    }

    /** Says what the two lists hold, so that neither has to be opened to find out. */
    private void updateListPreferences() {
        BlacklistService blacklistService = YacbHolder.getBlacklistService();
        if (blacklistService != null) {
            BlacklistService.Counts counts = blacklistService.getCounts();

            requirePreference(PREF_BLACKLIST_SCREEN).setSummary(counts.rules > 0
                    ? getString(R.string.blacklist_count_with_rules, counts.total, counts.rules)
                    : getResources().getQuantityString(
                            R.plurals.blacklist_count, counts.total, counts.total));
        }

        int whitelistCount = Whitelist.parse(App.getSettings().getWhitelist()).size();

        requirePreference(PREF_WHITELIST_SCREEN).setSummary(whitelistCount > 0
                ? getResources().getQuantityString(
                        R.plurals.whitelist_count, whitelistCount, whitelistCount)
                : getString(R.string.whitelist_summary));
    }

    /** Says how fresh each source of numbers is, and whether PhoneBlock has anything to block. */
    private void updateSourcePreferences() {
        requirePreference(PREF_DB_MANAGEMENT).setSummary(getCommunityDbStatus());

        requirePreference(PREF_PHONE_BLOCK_SCREEN)
                .setSummary(PhoneBlockHelper.getListStatus(requireContext()));

        // the list can only block while it is kept at all, which is a question of its own screen
        requirePreference(Settings.PREF_BLOCK_PHONE_BLOCK)
                .setEnabled(App.getSettings().getUsePhoneBlock());
    }

    /** Which version of the community database is in use, and when it was last checked. */
    private String getCommunityDbStatus() {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

        String version = communityDatabase != null && communityDatabase.isOperational()
                ? String.valueOf(communityDatabase.getEffectiveDbVersion())
                : getString(R.string.db_version_not_available);

        long lastCheck = App.getSettings().getLastUpdateCheckTime();
        String lastCheckValue = lastCheck != 0
                ? DateUtils.getRelativeTimeSpanString(lastCheck).toString()
                : getString(R.string.db_last_update_check_never);

        return getString(R.string.db_version, version)
                + "\n" + getString(R.string.db_last_update_check, lastCheckValue);
    }

    private void updateCallScreeningPreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        this.<SwitchPreferenceCompat>requirePreference(PREF_USE_CALL_SCREENING_SERVICE)
                .setChecked(PermissionHelper.isCallScreeningHeld(requireContext()));
    }

    private void updateCallerIdOverlayPreference() {
        Settings settings = App.getSettings();

        boolean hasPermission = PermissionHelper.hasOverlayPermission(requireContext());

        if (overlayPermissionRequested && hasPermission) {
            overlayPermissionRequested = false; // the user granted it for this very feature
            settings.setCallerIdOverlay(true);
        }

        /*
         * Without the permission the overlay can't be drawn, which is shown by leaving the
         * switch off - but the setting is left alone, so that granting the permission later
         * brings the overlay back rather than needing the switch to be found again.
         */
        this.<SwitchPreferenceCompat>requirePreference(Settings.PREF_CALLER_ID_OVERLAY)
                .setChecked(settings.getCallerIdOverlay() && hasPermission);
    }

    private void updateBlockedCallNotificationsPreference() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return;

        this.<SwitchPreferenceCompat>requirePreference(PREF_NOTIFICATIONS_BLOCKED_NON_PERSISTENT)
                .setChecked(App.getSettings().getNotificationsForBlockedCalls());
    }

}
