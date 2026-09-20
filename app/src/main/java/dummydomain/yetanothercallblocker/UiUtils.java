package dummydomain.yetanothercallblocker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.MultiSelectListPreference;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class UiUtils {

    /**
     * Halves the time a list takes to show a change.
     *
     * <p>The rows of this app carry short, plain text, so the framework's default pace - a
     * fifth of a second for a row that moves or changes - is longer than it takes to read
     * what moved. At half of it the change is still visible as a movement rather than a jump.
     *
     * <p>Whatever the framework's defaults are is what gets halved, so this follows them if
     * they ever change.
     */
    public static void speedUpAnimations(RecyclerView list) {
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator == null) return;

        animator.setAddDuration(animator.getAddDuration() / 2);
        animator.setRemoveDuration(animator.getRemoveDuration() / 2);
        animator.setMoveDuration(animator.getMoveDuration() / 2);
        animator.setChangeDuration(animator.getChangeDuration() / 2);
    }

    @ColorInt
    public static int getColorInt(@NonNull Context context, @ColorRes int colorResId) {
        return ResourcesCompat.getColor(context.getResources(), colorResId, context.getTheme());
    }

    /**
     * Puts the number on the clipboard and says so.
     *
     * <p>Android 13 and newer show that themselves, so there is nothing to say there.
     */
    public static void copyToClipboard(@NonNull Context context, String number) {
        ClipboardManager clipboardManager
                = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) return;

        clipboardManager.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.app_name), number));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.number_copied, Toast.LENGTH_SHORT).show();
        }
    }

    public static String getSummary(@NonNull Context context,
                                    @NonNull MultiSelectListPreference preference) {
        List<String> selectedEntries = getSelectedEntries(preference);

        String valuesString = selectedEntries.isEmpty()
                ? context.getString(R.string.selected_value_nothing)
                : TextUtils.join(", ", selectedEntries);

        return context.getResources().getQuantityString(R.plurals.selected_values,
                selectedEntries.size(), valuesString);
    }

    public static List<String> getSelectedEntries(MultiSelectListPreference preference) {
        CharSequence[] entries = preference.getEntries();
        CharSequence[] entryValues = preference.getEntryValues();
        Set<String> values = preference.getValues();

        if (values.isEmpty()) return Collections.emptyList();

        List<String> result = new ArrayList<>(values.size());

        for (int i = 0; i < entries.length; i++) {
            if (values.contains(entryValues[i].toString())) {
                result.add(entries[i].toString());
            }
        }

        return result;
    }

}
