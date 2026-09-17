package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.Whitelist;
import dummydomain.yetanothercallblocker.data.WhitelistItem;
import dummydomain.yetanothercallblocker.data.WhitelistService;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * One whitelist entry, the way the blacklist has one for its entries.
 *
 * <p>The pattern starts out as the number the screen was opened for, so that the last digits
 * can be replaced with {@code *} to let a whole range through before it is saved.
 */
public class EditWhitelistItemActivity extends AppCompatActivity {

    private static final String PARAM_NAME = "itemName";
    private static final String PARAM_PATTERN = "numberPattern";
    private static final String PARAM_EDIT = "edit";

    private final WhitelistService whitelistService = YacbHolder.getWhitelistService();

    private TextInputLayout nameTextField;
    private TextInputLayout patternTextField;

    /** The pattern of the entry being edited, or null when one is being added. */
    private String editedPattern;

    /** Opens the screen for a new entry, filled in with what is known about the number. */
    public static Intent getIntent(Context context, String name, String pattern) {
        Intent intent = new Intent(context, EditWhitelistItemActivity.class);
        intent.putExtra(PARAM_NAME, name);
        intent.putExtra(PARAM_PATTERN, pattern);
        return intent;
    }

    /** Opens the screen for the entry with this pattern. */
    public static Intent getEditIntent(Context context, String pattern) {
        Intent intent = new Intent(context, EditWhitelistItemActivity.class);
        intent.putExtra(PARAM_PATTERN, pattern);
        intent.putExtra(PARAM_EDIT, true);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_whitelist_item);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        nameTextField = findViewById(R.id.nameTextField);
        patternTextField = findViewById(R.id.patternTextField);

        EditText patternEditText = Objects.requireNonNull(patternTextField.getEditText());
        patternEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                validate();
            }
        });
        patternEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSaveClicked(null);
                return true;
            }
            return false;
        });

        PatternKeys.setUp(findViewById(R.id.patternKeys), patternEditText);

        String name = getIntent().getStringExtra(PARAM_NAME);
        String pattern = Whitelist.normalize(getIntent().getStringExtra(PARAM_PATTERN));

        if (getIntent().getBooleanExtra(PARAM_EDIT, false)) {
            WhitelistItem item = whitelistService.findByPattern(pattern);
            if (item == null) {
                finish();
                return;
            }

            editedPattern = item.getPattern();
            name = item.getName();

            setTitle(R.string.title_edit_whitelist_item_activity);
        }

        if (savedInstanceState == null) {
            setString(nameTextField, name);
            setString(patternTextField, pattern);
        }

        patternTextField.requestFocus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_edit_whitelist_item, menu);

        if (editedPattern == null) {
            menu.findItem(R.id.menu_delete).setVisible(false);
        }

        return true;
    }

    public void onSaveClicked(MenuItem item) {
        if (!validate()) return;

        WhitelistItem whitelistItem = new WhitelistItem(
                getString(nameTextField), getString(patternTextField));

        boolean saved = editedPattern != null
                ? whitelistService.replace(editedPattern, whitelistItem)
                : whitelistService.add(whitelistItem);

        if (!saved) {
            // the only thing that stops a valid entry is another entry with the same pattern
            patternTextField.setError(getString(R.string.whitelist_pattern_in_use));
            return;
        }

        finish();
    }

    public void onDeleteClicked(MenuItem item) {
        List<String> patterns = new ArrayList<>(1);
        patterns.add(editedPattern);

        whitelistService.remove(patterns);

        Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean validate() {
        String pattern = Whitelist.normalize(getString(patternTextField));

        boolean empty = TextUtils.isEmpty(pattern);
        boolean valid = !empty && new WhitelistItem(null, pattern).isValid();

        patternTextField.setError(!valid ? getString(
                empty ? R.string.number_pattern_empty : R.string.number_pattern_incorrect) : null);

        return valid;
    }

    private String getString(TextInputLayout textInputLayout) {
        return Objects.requireNonNull(textInputLayout.getEditText()).getText().toString();
    }

    private void setString(TextInputLayout textInputLayout, String s) {
        Objects.requireNonNull(textInputLayout.getEditText()).setText(s);
    }

}
