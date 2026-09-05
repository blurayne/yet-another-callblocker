package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;

import java.util.Objects;

import dummydomain.yetanothercallblocker.data.Whitelist;
import dummydomain.yetanothercallblocker.data.WhitelistService;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * The dialog for one whitelist entry.
 *
 * <p>It starts out with the number, and the number can be turned into a pattern before it is
 * saved: replacing the last digits with {@code *} lets a whole range through, which is what
 * a company with many extensions needs.
 */
public class WhitelistDialogHelper {

    /** Asks for an entry to add, starting from the number (which may be null). */
    public static void showAddDialog(Context context, String number, Runnable onDone) {
        showDialog(context, null, number, onDone);
    }

    /** Lets an entry be changed. */
    public static void showEditDialog(Context context, String entry, Runnable onDone) {
        showDialog(context, entry, entry, onDone);
    }

    /**
     * @param oldEntry the entry being edited, or null when one is being added
     * @param initial  what the field starts out with
     * @param onDone   run after the entry was saved, may be null
     */
    private static void showDialog(Context context, String oldEntry, String initial,
                                   Runnable onDone) {
        WhitelistService whitelistService = YacbHolder.getWhitelistService();

        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_whitelist_entry, null);

        TextInputLayout entryField = view.findViewById(R.id.entryTextField);
        EditText entryEditText = Objects.requireNonNull(entryField.getEditText());

        if (!TextUtils.isEmpty(initial)) {
            entryEditText.setText(Whitelist.normalize(initial));
            entryEditText.setSelection(entryEditText.getText().length());
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(oldEntry == null
                        ? R.string.whitelist_add_title : R.string.whitelist_edit_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // the dialog stays open when the entry can't be used, so that it can be corrected
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String entry = Whitelist.normalize(entryEditText.getText().toString());

                    if (entry.isEmpty()) {
                        entryField.setError(context.getString(R.string.number_pattern_empty));
                        return;
                    }

                    if (!Whitelist.isValid(entry)) {
                        entryField.setError(context.getString(R.string.number_pattern_incorrect));
                        return;
                    }

                    if (oldEntry == null) {
                        if (whitelistService.add(entry)) {
                            Toast.makeText(context, R.string.added_to_whitelist,
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        whitelistService.replace(oldEntry, entry);
                    }

                    dialog.dismiss();

                    if (onDone != null) onDone.run();
                }));

        dialog.show();

        entryEditText.requestFocus();
    }

    private WhitelistDialogHelper() {
    }

}
