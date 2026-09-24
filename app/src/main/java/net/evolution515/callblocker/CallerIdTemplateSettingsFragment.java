package net.evolution515.callblocker;

import android.text.InputType;
import android.text.TextUtils;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

/**
 * What the phone app is told about a caller: what it will look like, and how to say it.
 *
 * <p>A template is hard to judge from its own text - what {@code {category}} amounts to
 * depends on what is known about the number - so the screen shows what a caller would look
 * like with it, on a made-up number that has something of everything. Underneath it are a few
 * ready-made ones: most people want one of those rather than to write anything.
 */
public class CallerIdTemplateSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN = "callerIdTemplateScreen";
    private static final String PREF_PREVIEW = "callerIdPreview";

    /** The ready-made ones: the row they sit in, and what they say. */
    private static final String[] EXAMPLE_KEYS = {
            "callerIdExampleDefault",
            "callerIdExampleCategory",
            "callerIdExampleRatings",
            "callerIdExampleNumber",
            "callerIdExampleEverything",
    };

    /** In the same order; 0 means the app's own wording, which is no template at all. */
    private static final int[] EXAMPLE_VALUES = {
            0,
            R.string.caller_id_template_value_category,
            R.string.caller_id_template_value_ratings,
            R.string.caller_id_template_value_number,
            R.string.caller_id_template_value_everything,
    };

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.caller_id_template_preferences;
    }

    @Override
    protected void initScreen() {
        EditTextPreference template = requirePreference(Settings.PREF_CALLER_ID_TEMPLATE);

        // several lines are worth having: the first goes where the name goes, the rest beside it
        template.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            editText.setSingleLine(false);
            editText.setMinLines(3);
            editText.setHorizontallyScrolling(false);
        });

        setPrefChangeListener(Settings.PREF_CALLER_ID_TEMPLATE, (preference, newValue) -> {
            // the value is written after this returns, so what it looks like is read after that
            requireView().post(this::updatePreview);
            return true;
        });

        for (int i = 0; i < EXAMPLE_KEYS.length; i++) {
            String value = getExample(i);

            Preference example = requirePreference(EXAMPLE_KEYS[i]);

            example.setSummary(!TextUtils.isEmpty(value)
                    ? value : getString(R.string.caller_id_template_default));

            example.setOnPreferenceClickListener(preference -> {
                // written through the field, so that the field shows what was chosen
                template.setText(value);

                updatePreview();
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        updatePreview();
    }

    private String getExample(int index) {
        int resId = EXAMPLE_VALUES[index];

        return resId != 0 ? getString(resId) : "";
    }

    /** What a caller would look like right now, on a number that has something of everything. */
    private void updatePreview() {
        if (!isAdded()) return;

        String[] preview = CallerIdTemplate.preview(requireContext(),
                App.getSettings().getCallerIdTemplate());

        Preference preference = requirePreference(PREF_PREVIEW);

        preference.setTitle(!TextUtils.isEmpty(preview[0])
                ? preview[0] : getString(R.string.caller_id_template_preview_empty));

        preference.setSummary(!TextUtils.isEmpty(preview[1])
                ? preview[1] : getString(R.string.caller_id_template_preview_number));
    }

}
