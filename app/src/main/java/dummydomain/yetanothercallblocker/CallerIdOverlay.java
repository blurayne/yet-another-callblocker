package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.NumberInfo;

/**
 * A window with the caller info drawn on top of the incoming call screen.
 *
 * <p>It's a fallback for the phone apps that ignore the info provided by
 * {@link CallerIdDirectoryProvider} (some vendor phone apps do). Unlike the directory provider,
 * it requires the "display over other apps" permission.
 *
 * <p>The window is only shown if there's something known about the number,
 * and it's removed as soon as the call is answered or ended.
 */
public class CallerIdOverlay {

    private static final long AUTO_HIDE_DELAY = TimeUnit.MINUTES.toMillis(2);

    private static final Logger LOG = LoggerFactory.getLogger(CallerIdOverlay.class);

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static final Runnable HIDE_RUNNABLE = CallerIdOverlay::hideInternal;

    /*
     * The view holds a themed wrapper around the application context, and it's only kept
     * while the window is shown (hideInternal() clears it), so it doesn't outlive anything.
     */
    @SuppressLint("StaticFieldLeak")
    private static View view;
    private static String shownNumber;

    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true; // granted on install

        return android.provider.Settings.canDrawOverlays(context);
    }

    public static void show(Context context, NumberInfo numberInfo) {
        LOG.debug("show()");

        Context appContext = context.getApplicationContext();
        HANDLER.post(() -> showInternal(appContext, numberInfo));
    }

    public static void hide() {
        LOG.debug("hide()");

        HANDLER.post(HIDE_RUNNABLE);
    }

    private static void showInternal(Context context, NumberInfo numberInfo) {
        if (view != null && TextUtils.equals(shownNumber, numberInfo.number)) {
            LOG.debug("showInternal() already shown for this number");
            return;
        }

        hideInternal();

        if (!hasPermission(context)) {
            LOG.warn("showInternal() no permission to draw overlays");
            return;
        }

        Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);

        String name = NumberInfoUtils.getCallerIdName(themedContext, numberInfo);
        if (TextUtils.isEmpty(name)) {
            LOG.debug("showInternal() nothing to show");
            return;
        }

        try {
            View overlayView = LayoutInflater.from(themedContext)
                    .inflate(R.layout.caller_id_overlay, null);

            populate(themedContext, overlayView, numberInfo, name);

            getWindowManager(themedContext).addView(overlayView, createLayoutParams());

            view = overlayView;
            shownNumber = numberInfo.number;

            HANDLER.postDelayed(HIDE_RUNNABLE, AUTO_HIDE_DELAY);
        } catch (Exception e) {
            LOG.error("showInternal() failed to show the overlay", e);
            view = null;
        }
    }

    private static void hideInternal() {
        HANDLER.removeCallbacks(HIDE_RUNNABLE);

        View overlayView = view;
        if (overlayView == null) return;

        view = null;
        shownNumber = null;

        try {
            getWindowManager(overlayView.getContext()).removeView(overlayView);
        } catch (Exception e) {
            LOG.warn("hideInternal() failed to remove the overlay", e);
        }
    }

    private static void populate(Context context, View view, NumberInfo numberInfo, String name) {
        IconAndColor.forNumberInfo(numberInfo).applyToImageView(view.findViewById(R.id.icon));

        view.<TextView>findViewById(R.id.name).setText(name);

        view.<TextView>findViewById(R.id.number).setText(!numberInfo.noNumber
                ? numberInfo.number : context.getString(R.string.no_number));

        TextView labelView = view.findViewById(R.id.label);
        String label = NumberInfoUtils.getCallerIdLabel(context, numberInfo);
        if (!TextUtils.isEmpty(label)) {
            labelView.setText(label);
        } else {
            labelView.setVisibility(View.GONE);
        }

        ReviewsSummaryHelper.populateSummary(
                view.findViewById(R.id.reviews_summary), numberInfo.communityDatabaseItem);

        AppCompatImageView closeView = view.findViewById(R.id.close);
        closeView.setOnClickListener(v -> hideInternal());

        if (!numberInfo.noNumber) {
            view.setOnClickListener(v -> {
                hideInternal();

                IntentHelper.startActivity(context,
                        InfoDialogActivity.getIntent(context, numberInfo.number)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }
    }

    @SuppressWarnings("deprecation") // TYPE_PHONE is the only option before Android 8
    private static WindowManager.LayoutParams createLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP;

        return params;
    }

    private static WindowManager getWindowManager(Context context) {
        return (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private CallerIdOverlay() {}

}
