package dummydomain.yetanothercallblocker;

import android.view.View;
import android.widget.EditText;

/**
 * The buttons that write the characters a pattern is made of.
 *
 * <p>The pattern field asks for the phone keypad, because a pattern is mostly a number - but
 * that keypad has no braces and often no comma, which would leave the groups of alternatives
 * impossible to type. These put them in at the cursor.
 */
public class PatternKeys {

    /**
     * @param keys  the row of buttons
     * @param field the field they write into
     */
    public static void setUp(View keys, EditText field) {
        bind(keys, R.id.keyAnyDigits, field, "*", 1);
        bind(keys, R.id.keyOneDigit, field, "#", 1);
        bind(keys, R.id.keyGroup, field, "{}", 1); // the cursor lands between the braces
        bind(keys, R.id.keyComma, field, ",", 1);
    }

    private static void bind(View keys, int id, EditText field, String text, int cursorOffset) {
        keys.findViewById(id).setOnClickListener(v -> insert(field, text, cursorOffset));
    }

    /** Puts the text where the cursor is, replacing what is selected. */
    private static void insert(EditText field, String text, int cursorOffset) {
        int start = Math.max(field.getSelectionStart(), 0);
        int end = Math.max(field.getSelectionEnd(), 0);

        int from = Math.min(start, end);
        int to = Math.max(start, end);

        field.getText().replace(from, to, text);
        field.setSelection(Math.min(from + cursorOffset, field.getText().length()));

        field.requestFocus();
    }

    private PatternKeys() {
    }

}
