package net.evolution515.callblocker;

import android.content.Context;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.Date;

/**
 * Pausing what the app does about calls, for as long as the user picks.
 *
 * <p>A pause stops the blocking and the silencing, and leaves the caller ID and the
 * notifications alone: it is there to let a call through, not to hear less about it.
 */
public class BlockingPauseHelper {

    /** How long a pause can last, in minutes, in the order the dialog offers them. */
    private static final int[] DURATIONS_MINUTES = {15, 30, 60, 120, 480, 0}; // 0: until told

    /**
     * Asks how long to pause for - or ends the pause, when there is one, because that is what
     * the row that opens this says it does.
     *
     * @param onChanged run after the pause was set or ended, may be null
     */
    public static void show(Context context, Runnable onChanged) {
        Settings settings = App.getSettings();

        if (settings.isBlockingPaused()) {
            settings.setBlockingPausedUntil(0);

            Toast.makeText(context, R.string.blocking_resumed, Toast.LENGTH_SHORT).show();
            if (onChanged != null) onChanged.run();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.pause_blocking)
                .setItems(R.array.pause_blocking_entries, (dialog, which) -> {
                    int minutes = which >= 0 && which < DURATIONS_MINUTES.length
                            ? DURATIONS_MINUTES[which] : 0;

                    settings.setBlockingPausedUntil(minutes > 0
                            ? System.currentTimeMillis() + minutes * 60_000L
                            : Long.MAX_VALUE);

                    Toast.makeText(context, getStatus(context), Toast.LENGTH_LONG).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    /** What the menu row says: pause, or end the pause and by when it would end by itself. */
    public static String getActionTitle(Context context) {
        Settings settings = App.getSettings();

        if (!settings.isBlockingPaused()) return context.getString(R.string.pause_blocking);

        String until = getUntil(context, settings.getBlockingPausedUntil());

        return until != null
                ? context.getString(R.string.resume_blocking_until, until)
                : context.getString(R.string.resume_blocking);
    }

    /** Whether blocking is on, paused, or on but with nothing switched on to block. */
    public static String getStatus(Context context) {
        Settings settings = App.getSettings();

        if (settings.isBlockingPaused()) {
            String until = getUntil(context, settings.getBlockingPausedUntil());

            return until != null
                    ? context.getString(R.string.blocking_paused_until, until)
                    : context.getString(R.string.blocking_paused_indefinitely);
        }

        return context.getString(settings.getCallBlockingEnabled()
                ? R.string.blocking_on : R.string.blocking_off);
    }

    /** The time of day a pause ends, or null when it only ends when the user says so. */
    private static String getUntil(Context context, long time) {
        if (time == Long.MAX_VALUE) return null;

        return DateFormat.getTimeFormat(context).format(new Date(time));
    }

}
