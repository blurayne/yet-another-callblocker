package dummydomain.yetanothercallblocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import dummydomain.yetanothercallblocker.data.PhoneBlockList;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.PhoneBlockUpdateFinishedEvent;
import dummydomain.yetanothercallblocker.utils.DebuggingUtils;
import dummydomain.yetanothercallblocker.utils.FileUtils;
import dummydomain.yetanothercallblocker.utils.PackageManagerUtils;
import dummydomain.yetanothercallblocker.work.TaskService;
import dummydomain.yetanothercallblocker.work.UpdateScheduler;

public class RootSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN_ROOT = null;
    private static final String PREF_USE_CALL_SCREENING_SERVICE = "useCallScreeningService";
    private static final String PREF_AUTO_UPDATE_ENABLED = "autoUpdateEnabled";
    private static final String PREF_NOTIFICATION_CHANNEL_SETTINGS = "notificationChannelSettings";
    private static final String PREF_EXPORT_LOGCAT = "exportLogcat";
    private static final String PREF_PHONE_BLOCK_INFO = "phoneBlockInfo";
    private static final String PREF_PHONE_BLOCK_UPDATE = "phoneBlockUpdate";
    private static final String PREF_CATEGORY_NOTIFICATIONS = "categoryNotifications";
    private static final String PREF_CATEGORY_NOTIFICATIONS_LEGACY = "categoryNotificationsLegacy";
    private static final String PREF_NOTIFICATIONS_BLOCKED_NON_PERSISTENT = "showNotificationsForBlockedCallsNonPersistent";

    private static final Logger LOG = LoggerFactory.getLogger(RootSettingsFragment.class);

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

        EventUtils.register(this);

        // may be changed externally
        updateCallScreeningPreference();

        // needs to be updated after the confirmation dialog was closed
        // due to activity recreation (orientation change, etc.)
        updateBlockedCallNotificationsPreference();

        // the permission may be granted (or revoked) in the system settings
        updateCallerIdOverlayPreference();

        updatePhoneBlockPreference();
    }

    @Override
    public void onStop() {
        EventUtils.unregister(this);

        super.onStop();
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

        requirePreference(PREF_EXPORT_LOGCAT)
                .setOnPreferenceClickListener(preference -> {
                    exportLogcat();
                    return true;
                });

        requirePreference(PREF_PHONE_BLOCK_INFO).setOnPreferenceClickListener(pref -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_category_phone_block)
                    .setMessage(pref.getSummary())
                    .setNegativeButton(R.string.back, null)
                    .show();
            return true;
        });

        requirePreference(PREF_PHONE_BLOCK_UPDATE).setOnPreferenceClickListener(preference -> {
            TaskService.start(requireContext(), TaskService.TASK_UPDATE_PHONE_BLOCK);
            return true;
        });

        setPrefChangeListener(Settings.PREF_USE_PHONE_BLOCK, (preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                // there is nothing to use until the list has been fetched
                TaskService.start(requireContext(), TaskService.TASK_UPDATE_PHONE_BLOCK);
            }
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

            requirePreference(PREF_CATEGORY_NOTIFICATIONS_LEGACY).setVisible(false);
        } else {
            requirePreference(PREF_CATEGORY_NOTIFICATIONS).setVisible(false);

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

private void exportLogcat() {
        Activity activity = requireActivity();

        String path = null;
        try {
            path = DebuggingUtils.saveLogcatInCache(activity);
            DebuggingUtils.appendDeviceInfo(path);
        } catch (IOException | InterruptedException e) {
            LOG.warn("exportLogcat()", e);
        }

        if (path != null) {
            FileUtils.shareFile(activity, new File(path));
        }
    }

    private void updateCallScreeningPreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        this.<SwitchPreferenceCompat>requirePreference(PREF_USE_CALL_SCREENING_SERVICE)
                .setChecked(PermissionHelper.isCallScreeningHeld(requireContext()));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onPhoneBlockUpdateFinished(PhoneBlockUpdateFinishedEvent event) {
        updatePhoneBlockPreference();

        int size = event.result.size;

        String message;
        switch (event.result.status) {
            case UPDATED:
                message = getString(R.string.phone_block_update_result, size);
                break;

            case NOT_DUE:
                message = getString(R.string.phone_block_update_not_due, size);
                break;

            case NOT_CONFIGURED:
                return; // the list is turned off, there is nothing to say

            default:
                message = getString(R.string.phone_block_update_failed);
                break;
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    /** Says how big the list is and when it was last fetched. */
    private void updatePhoneBlockPreference() {
        PhoneBlockList list = YacbHolder.getPhoneBlockList();
        long lastUpdate = App.getSettings().getPhoneBlockLastUpdateTime();

        String summary;
        if (list == null || list.isEmpty() || lastUpdate <= 0) {
            summary = getString(R.string.phone_block_status_empty);
        } else {
            summary = getString(R.string.phone_block_status, list.getSize(),
                    DateUtils.getRelativeTimeSpanString(lastUpdate, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS));
        }

        requirePreference(PREF_PHONE_BLOCK_UPDATE).setSummary(summary);
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
