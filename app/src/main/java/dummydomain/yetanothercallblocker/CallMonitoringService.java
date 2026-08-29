package dummydomain.yetanothercallblocker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.YacbHolder;

import static dummydomain.yetanothercallblocker.utils.StringUtils.quote;
import static java.util.Objects.requireNonNull;

public class CallMonitoringService extends Service {

    private static final String ACTION_START = "YACB_ACTION_START";
    private static final String ACTION_STOP = "YACB_ACTION_STOP";

    private static final Logger LOG = LoggerFactory.getLogger(CallMonitoringService.class);

    private final MyPhoneStateListener phoneStateListener = new MyPhoneStateListener();
    private MyTelephonyCallback telephonyCallback;
    private final PhoneStateBroadcastReceiver phoneStateBroadcastReceiver
            = new PhoneStateBroadcastReceiver(
            PhoneStateHandler.Source.PHONE_STATE_BROADCAST_RECEIVER_MONITORING);

    private boolean monitoringStarted;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, getIntent(context, ACTION_START));
        } catch (Exception e) {
            /*
             * An app targeting Android 12+ can't start a foreground service while it's in the
             * background, and the app process is started in the background often enough
             * (an incoming call, a query from the phone app). Failing to monitor is bad,
             * crashing the process is worse: the service is started again when the app is
             * opened or the device boots.
             */
            LOG.warn("start() couldn't start the monitoring service", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(getIntent(context, ACTION_STOP));
    }

    private static Intent getIntent(Context context, String action) {
        Intent intent = new Intent(context, CallMonitoringService.class);
        intent.setAction(action);
        return intent;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LOG.debug("onStartCommand({})", intent);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            stopForeground();
            stopSelf();
        } else {
            startForeground();
            startMonitoring();
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        LOG.debug("onBind({})", intent);
        return null;
    }

    @Override
    public void onDestroy() {
        LOG.debug("onDestroy()");
        stopMonitoring();
    }

    private void startForeground() {
        startForeground(NotificationHelper.NOTIFICATION_ID_MONITORING_SERVICE,
                NotificationHelper.createMonitoringServiceNotification(this));
    }

    private void stopForeground() {
        stopForeground(true);
    }

    private void startMonitoring() {
        if (monitoringStarted) return;
        monitoringStarted = true;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerTelephonyCallback();
            } else {
                getTelephonyManager().listen(
                        phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(TelephonyManager.EXTRA_STATE_RINGING); // TODO: check
            intentFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(phoneStateBroadcastReceiver, intentFilter);
        } catch (Exception e) {
            LOG.error("startMonitoring()", e);
        }
    }

    private void stopMonitoring() {
        if (!monitoringStarted) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                unregisterTelephonyCallback();
            } else {
                getTelephonyManager().listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }

            unregisterReceiver(phoneStateBroadcastReceiver);
        } catch (Exception e) {
            LOG.error("stopMonitoring()", e);
        }

        monitoringStarted = false;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void registerTelephonyCallback() {
        MyTelephonyCallback callback = telephonyCallback = new MyTelephonyCallback();

        getTelephonyManager().registerTelephonyCallback(getMainExecutor(), callback);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void unregisterTelephonyCallback() {
        if (telephonyCallback == null) return;

        getTelephonyManager().unregisterTelephonyCallback(telephonyCallback);
        telephonyCallback = null;
    }

    private static void handleCallState(PhoneStateHandler.Source source,
                                        int state, String phoneNumber) {
        PhoneStateHandler phoneStateHandler = YacbHolder.getPhoneStateHandler();

        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE:
                phoneStateHandler.onIdle(source, phoneNumber);
                break;
            case TelephonyManager.CALL_STATE_RINGING:
                phoneStateHandler.onRinging(source, phoneNumber);
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                phoneStateHandler.onOffHook(source, phoneNumber);
                break;
        }
    }

    private TelephonyManager getTelephonyManager() {
        return requireNonNull(
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE));
    }

    private static class MyPhoneStateListener extends PhoneStateListener {

        private static final Logger LOG = LoggerFactory.getLogger(MyPhoneStateListener.class);

        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            LOG.info("onCallStateChanged({}, {})", state, quote(phoneNumber));

            /*
             * According to docs, an empty string may be passed if the app lacks permissions.
             * The app deals with permissions in PhoneStateHandler.
             */
            if (TextUtils.isEmpty(phoneNumber)) {
                phoneNumber = null;
            }

            handleCallState(PhoneStateHandler.Source.PHONE_STATE_LISTENER, state, phoneNumber);
        }
    }

    /**
     * The replacement for {@link PhoneStateListener}, which is deprecated since Android 12.
     * It only reports the state: the number of the call comes from the broadcast receiver.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static class MyTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {

        private static final Logger LOG = LoggerFactory.getLogger(MyTelephonyCallback.class);

        @Override
        public void onCallStateChanged(int state) {
            LOG.info("onCallStateChanged({})", state);

            handleCallState(PhoneStateHandler.Source.TELEPHONY_CALLBACK, state, null);
        }
    }

}
