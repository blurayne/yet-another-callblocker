package net.evolution515.callblocker;

import android.text.InputType;
import android.text.method.NumberKeyListener;
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
        /*
         * The keypad comes with a filter of its own that drops everything a phone number can't
         * hold - including anything put in from here, which is how the braces went missing.
         * This one asks for the same keypad but accepts what a pattern is made of.
         */
        field.setKeyListener(new PatternKeyListener());

        bind(keys, R.id.keyAnyDigits, field, "*");
        bind(keys, R.id.keyOneDigit, field, "#");
        bind(keys, R.id.keyGroupOpen, field, "{");
        bind(keys, R.id.keyGroupClose, field, "}");
        bind(keys, R.id.keyComma, field, ",");
    }

    private static void bind(View keys, int id, EditText field, String text) {
        keys.findViewById(id).setOnClickListener(v -> insert(field, text));
    }

    /** Puts the text where the cursor is, replacing what is selected. */
    private static void insert(EditText field, String text) {
        int start = Math.max(field.getSelectionStart(), 0);
        int end = Math.max(field.getSelectionEnd(), 0);

        int from = Math.min(start, end);
        int to = Math.max(start, end);

        field.getText().replace(from, to, text);
        field.setSelection(Math.min(from + text.length(), field.getText().length()));

        field.requestFocus();
    }

    /** The phone keypad, accepting everything a pattern is written with. */
    private static class PatternKeyListener extends NumberKeyListener {

        private static final char[] ACCEPTED = "0123456789+*#{},".toCharArray();

        @Override
        protected char[] getAcceptedChars() {
            return ACCEPTED;
        }

        @Override
        public int getInputType() {
            return InputType.TYPE_CLASS_PHONE;
        }

    }

    private PatternKeys() {
    }

}
