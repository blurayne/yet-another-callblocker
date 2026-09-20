package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;

/** One source: where it is, what it holds, what it needs to let us in, how often to ask. */
public class EditNumberSourceActivity extends AppCompatActivity {

    private static final String PARAM_ID = "sourceId";

    public static Intent getIntent(Context context, String id) {
        Intent intent = new Intent(context, EditNumberSourceActivity.class);
        if (id != null) intent.putExtra(PARAM_ID, id);
        return intent;
    }

    private final SourceService sourceService = YacbHolder.getSourceService();

    private NumberSource source;

    private TextInputLayout nameTextField, urlTextField, usernameTextField, secretTextField;
    private Spinner typeSpinner, authSpinner, updatesSpinner;
    private SwitchCompat enabledSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_number_source);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        nameTextField = findViewById(R.id.nameTextField);
        urlTextField = findViewById(R.id.urlTextField);
        usernameTextField = findViewById(R.id.usernameTextField);
        secretTextField = findViewById(R.id.secretTextField);
        typeSpinner = findViewById(R.id.typeSpinner);
        authSpinner = findViewById(R.id.authSpinner);
        updatesSpinner = findViewById(R.id.updatesSpinner);
        enabledSwitch = findViewById(R.id.enabledSwitch);

        setUpSpinner(typeSpinner, NumberSource.Type.values(),
                type -> getString(NumberSourcesActivity.getTypeName(type)));
        setUpSpinner(authSpinner, NumberSource.Auth.values(), this::getAuthName);
        setUpSpinner(updatesSpinner, NumberSource.Updates.values(),
                updates -> getString(NumberSourcesActivity.getUpdatesName(updates)));

        String id = getIntent().getStringExtra(PARAM_ID);
        source = id != null && sourceService != null ? sourceService.findById(id) : null;

        if (id != null && source == null) { // it was deleted while this screen was away
            finish();
            return;
        }

        if (source != null) {
            setTitle(R.string.title_edit_source_activity);
        } else {
            source = new NumberSource();
        }

        if (savedInstanceState == null) fill();

        authSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAuthFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateAuthFields();
        updateStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_edit_number_source, menu);

        // a source that was never saved has nothing to delete
        menu.findItem(R.id.menu_delete).setVisible(
                sourceService != null && sourceService.findById(source.getId()) != null);

        return true;
    }

    public void onSaveClicked(MenuItem item) {
        if (sourceService == null) return;

        String url = getString(urlTextField);
        if (TextUtils.isEmpty(url)) {
            urlTextField.setError(getString(R.string.source_url_empty));
            return;
        }

        source.setName(getString(nameTextField));
        source.setUrl(url);
        source.setType(selected(typeSpinner, NumberSource.Type.values()));
        source.setAuth(selected(authSpinner, NumberSource.Auth.values()));
        source.setUsername(getString(usernameTextField));
        source.setUpdates(selected(updatesSpinner, NumberSource.Updates.values()));
        source.setEnabled(enabledSwitch.isChecked());

        sourceService.save(source);

        // the secret is kept apart from the list, so it is saved apart from it too
        String secret = getString(secretTextField);
        if (source.getAuth() == NumberSource.Auth.NONE) {
            sourceService.setSecret(source.getId(), null);
        } else if (!TextUtils.isEmpty(secret)) {
            sourceService.setSecret(source.getId(), secret);
        }

        finish();
    }

    public void onDeleteClicked(MenuItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.are_you_sure)
                .setMessage(R.string.source_delete_message)
                .setPositiveButton(R.string.source_delete, (d, w) -> {
                    if (sourceService != null) sourceService.remove(source.getId());
                    finish();
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    private void fill() {
        setString(nameTextField, source.getName());
        setString(urlTextField, source.getUrl());
        setString(usernameTextField, source.getUsername());

        if (sourceService != null) {
            setString(secretTextField, sourceService.getSecret(source.getId()));
        }

        select(typeSpinner, NumberSource.Type.values(), source.getType());
        select(authSpinner, NumberSource.Auth.values(), source.getAuth());
        select(updatesSpinner, NumberSource.Updates.values(), source.getUpdates());

        enabledSwitch.setChecked(source.isEnabled());
    }

    /** Only the fields the chosen way of logging in needs are shown. */
    private void updateAuthFields() {
        NumberSource.Auth auth = selected(authSpinner, NumberSource.Auth.values());

        usernameTextField.setVisibility(auth == NumberSource.Auth.BASIC
                ? View.VISIBLE : View.GONE);

        boolean needsSecret = auth != NumberSource.Auth.NONE;
        secretTextField.setVisibility(needsSecret ? View.VISIBLE : View.GONE);
        findViewById(R.id.secretNotice).setVisibility(needsSecret ? View.VISIBLE : View.GONE);
    }

    private void updateStatus() {
        TextView status = findViewById(R.id.status);

        if (!TextUtils.isEmpty(source.getLastResult())) {
            status.setText(source.getLastResult());
        } else if (source.getLastUpdate() > 0) {
            status.setText(getString(R.string.source_last_update,
                    DateUtils.getRelativeTimeSpanString(source.getLastUpdate(),
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
        } else {
            status.setText(R.string.source_never_fetched);
        }
    }

    private String getAuthName(NumberSource.Auth auth) {
        switch (auth) {
            case BEARER: return getString(R.string.source_auth_bearer);
            case BASIC: return getString(R.string.source_auth_basic);
            default: return getString(R.string.source_auth_none);
        }
    }

    /** A spinner over the values of an enum, shown by name. */
    private <T> void setUpSpinner(Spinner spinner, T[] values, Namer<T> namer) {
        List<String> names = new ArrayList<>(values.length);
        for (T value : values) {
            names.add(namer.getName(value));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinner.setAdapter(adapter);
    }

    /** The value behind what the spinner shows: it holds names, the enum holds meaning. */
    private <T> T selected(Spinner spinner, T[] values) {
        int position = spinner.getSelectedItemPosition();

        return position >= 0 && position < values.length ? values[position] : values[0];
    }

    private <T> void select(Spinner spinner, T[] values, T value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private interface Namer<T> {
        String getName(T value);
    }

    private String getString(TextInputLayout textInputLayout) {
        return Objects.requireNonNull(textInputLayout.getEditText()).getText().toString().trim();
    }

    private void setString(TextInputLayout textInputLayout, String value) {
        Objects.requireNonNull(textInputLayout.getEditText()).setText(value);
    }

}
