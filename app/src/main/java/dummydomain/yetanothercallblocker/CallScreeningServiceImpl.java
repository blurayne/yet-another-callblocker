package dummydomain.yetanothercallblocker;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.telecom.Connection;
import android.telecom.GatewayInfo;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.NumberInfoService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.CallEndedEvent;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;

@RequiresApi(Build.VERSION_CODES.N)
public class CallScreeningServiceImpl extends CallScreeningService {

    private static final Logger LOG = LoggerFactory.getLogger(CallScreeningServiceImpl.class);

    private NumberInfoService numberInfoService = YacbHolder.getNumberInfoService();

    @Override
    public void onScreenCall(@NonNull Call.Details callDetails) {
        LOG.info("onScreenCall({})", callDetails);

        boolean shouldBlock = false;
        boolean shouldSilence = false;
        NumberInfo numberInfo = null;

        boolean blockingEnabled = false;
        boolean callerIdEnabled = false;
        boolean silencingEnabled = false;

        try {
            blockingEnabled = App.getSettings().getCallBlockingEnabled();
            callerIdEnabled = App.getSettings().getCallerIdEnabled();
            // silencing the ringer is only possible on Android 10+
            silencingEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && App.getSettings().getSilenceCallsEnabled();

            boolean ignore = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (callDetails.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
                    ignore = true;
                }
            }

            if (!ignore && !blockingEnabled && !callerIdEnabled && !silencingEnabled) {
                ignore = true;
            }

            extraLogging(callDetails); // TODO: make optional or remove

            String number = null;

            if (!ignore) {
                Uri handle = callDetails.getHandle();
                LOG.trace("onScreenCall() handle: {}", handle);

                if (handle != null && PhoneAccount.SCHEME_TEL.equals(handle.getScheme())) {
                    number = handle.getSchemeSpecificPart();
                    LOG.debug("onScreenCall() number from handle: {}", number);
                }

                if (number == null) {
                    Bundle intentExtras = callDetails.getIntentExtras();
                    if (intentExtras != null) {
                        Object o = intentExtras.get(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS);
                        LOG.trace("onScreenCall() EXTRA_INCOMING_CALL_ADDRESS={}", o);

                        if (o instanceof Uri) {
                            Uri uri = (Uri) o;
                            if (PhoneAccount.SCHEME_TEL.equals(uri.getScheme())) {
                                number = uri.getSchemeSpecificPart();
                            }
                        }

                        if (number == null && intentExtras.containsKey(
                                "com.google.android.apps.hangouts.telephony.hangout_info_bundle")) {
                            // NB: SIA doesn't block (based on number) hangouts if there's no number in intentExtras
                            number = "YACB_hangouts_stub";
                        }
                    }

                    if (number == null && callDetails.getExtras() != null) {
                        // NB: this part is broken in SIA
                        number = callDetails.getExtras().getString(Connection.EXTRA_CHILD_ADDRESS);
                        LOG.trace("onScreenCall() EXTRA_CHILD_ADDRESS={}", number);
                    }
                }

                if (TextUtils.isEmpty(number)
                        && !PermissionHelper.hasNumberInfoPermissions(this)) {
                    ignore = true;
                    LOG.warn("onScreenCall() no info permissions");
                }
            }

            if (!ignore) {
                // the full info is needed to display the blacklist entry as the caller ID
                numberInfo = numberInfoService.getNumberInfo(number,
                        App.getSettings().getCachedAutoDetectedCountryCode(), callerIdEnabled);

                /*
                 * Where the network supports it (STIR/SHAKEN, Android 11+), it tells us whether
                 * the number the call claims to come from is really the caller's. A call that
                 * fails that check carries a forged number, whatever the number itself says.
                 */
                numberInfo.failedVerification = hasFailedVerification(callDetails)
                        && numberInfo.contactItem == null;

                shouldBlock = blockingEnabled && numberInfoService.shouldBlock(numberInfo);

                if (!shouldBlock && blockingEnabled && numberInfo.failedVerification
                        && App.getSettings().getBlockFailedVerification()) {
                    shouldBlock = true;
                    numberInfo.blockingReason = NumberInfo.BlockingReason.FAILED_VERIFICATION;
                }

                shouldSilence = !shouldBlock && silencingEnabled
                        && (numberInfoService.shouldSilence(numberInfo)
                        || numberInfo.failedVerification && App.getSettings().getSilenceCalls()
                        .contains(Settings.PREF_SILENCE_CALLS_UNVERIFIED));
            }
        } finally {
            LOG.debug("onScreenCall() blocking call: {}, silencing call: {}",
                    shouldBlock, shouldSilence);

            CallScreeningService.CallResponse.Builder responseBuilder = new CallResponse.Builder();

            if (shouldBlock) {
                responseBuilder
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipNotification(true);
            } else if (shouldSilence && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // the call goes through as usual, just without the ringtone
                responseBuilder.setSilenceCall(true);
            }

            boolean blocked = shouldBlock;
            try {
                respondToCall(callDetails, responseBuilder.build());
            } catch (Exception e) {
                LOG.error("onScreenCall() error invoking respondToCall()", e);
                blocked = false;
            }

            if (blocked) {
                LOG.info("onScreenCall() blocked call");

                NotificationHelper.showBlockedCallNotification(this, numberInfo);

                numberInfoService.blockedCall(numberInfo);

                postEvent(new CallEndedEvent());
            } else if (callerIdEnabled) {
                // the phone app queries the directory provider right after this,
                // so the info is resolved before the phone even starts ringing
                CallerIdHelper.onIncomingCall(this, numberInfo);
            }
        }

        LOG.debug("onScreenCall() finished");
    }

    /**
     * Whether the network says the number of the call is forged. It only knows that where
     * STIR/SHAKEN is deployed - everywhere else every call is simply "not verified",
     * which says nothing either way.
     */
    private static boolean hasFailedVerification(Call.Details callDetails) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;

        boolean failed = callDetails.getCallerNumberVerificationStatus()
                == Connection.VERIFICATION_STATUS_FAILED;

        if (failed) LOG.info("hasFailedVerification() the number of the call is not verified");

        return failed;
    }

    private void extraLogging(Call.Details callDetails) {
        LOG.trace("extraLogging() handle={}", callDetails.getHandle());

        if (callDetails.getStatusHints() != null) {
            LOG.trace("extraLogging() statusHints.label={}",
                    callDetails.getStatusHints().getLabel());
        }

        GatewayInfo gatewayInfo = callDetails.getGatewayInfo();
        if (gatewayInfo != null) {
            LOG.trace("extraLogging() gatewayInfo provider={}," +
                            "gatewayAddress={}, originalAddress={}",
                    gatewayInfo.getGatewayProviderPackageName(),
                    gatewayInfo.getGatewayAddress(),
                    gatewayInfo.getOriginalAddress());
        }

        Bundle intentExtras = callDetails.getIntentExtras();
        if (intentExtras != null) {
            LOG.trace("extraLogging() intentExtras:");
            for (String k : intentExtras.keySet()) {
                LOG.trace("extraLogging() key={}, value={}", k, intentExtras.get(k));
            }
        }

        Bundle extras = callDetails.getExtras();
        if (intentExtras != null) {
            LOG.trace("extraLogging() intentExtras:");
            for (String k : extras.keySet()) {
                LOG.trace("extraLogging() key={}, value={}", k, extras.get(k));
            }
        }
    }

}
