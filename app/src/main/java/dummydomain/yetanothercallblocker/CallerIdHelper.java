package dummydomain.yetanothercallblocker;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.NumberInfoCache;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * Displays the caller info while a call is incoming.
 *
 * <p>There are two ways of showing it, both handled here:
 * {@link CallerIdDirectoryProvider} (the info is displayed by the phone app itself) and
 * {@link CallerIdOverlay} (the info is drawn over the phone app).
 */
public class CallerIdHelper {

    private static final Logger LOG = LoggerFactory.getLogger(CallerIdHelper.class);

    /**
     * Handles an incoming call that is not blocked.
     *
     * <p>Should be called as early as possible: the resolved info is remembered here,
     * so that the phone app's directory query (which follows within milliseconds
     * when the call is screened) is answered without doing the lookup again.
     */
    public static void onIncomingCall(Context context, NumberInfo numberInfo) {
        if (numberInfo == null) return;

        Settings settings = App.getSettings();
        if (settings == null) return;

        if (!numberInfo.noNumber && settings.getCallerIdDirectory()) {
            NumberInfoCache cache = YacbHolder.getNumberInfoCache();
            if (cache != null) {
                LOG.debug("onIncomingCall() caching the info for the directory provider");
                cache.put(numberInfo.number, numberInfo);
            }
        }

        if (settings.getCallerIdOverlay()) {
            CallerIdOverlay.show(context, numberInfo);
        }
    }

    /** Handles the end of a call (or the call being answered). */
    public static void onCallFinished() {
        CallerIdOverlay.hide();
    }

    private CallerIdHelper() {}

}
