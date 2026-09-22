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
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.source.ArchiveUtils;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceNames;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.work.TaskService;

/** One source: where it is, what it holds, what it needs to let us in, how often to ask. */
public class EditNumberSourceActivity extends AppCompatActivity {

    private static final String PARAM_ID = "sourceId";
    private static final String PARAM_TYPE = "sourceType";
    private static final String PARAM_URL = "sourceUrl";
    private static final String PARAM_AUTH = "sourceAuth";

    public static Intent getIntent(Context context, String id) {
        Intent intent = new Intent(context, EditNumberSourceActivity.class);
        if (id != null) intent.putExtra(PARAM_ID, id);
        return intent;
    }

    /** A new source, with what is known about one of the places the app can fetch from. */
    public static Intent getIntent(Context context, NumberSource.Type type, String url,
                                   NumberSource.Auth auth) {
        return new Intent(context, EditNumberSourceActivity.class)
                .putExtra(PARAM_TYPE, type.name())
                .putExtra(PARAM_URL, url)
                .putExtra(PARAM_AUTH, auth.name());
    }

    private final SourceService sourceService = YacbHolder.getSourceService();

    private NumberSource source;

    private TextInputLayout nameTextField, urlTextField, usernameTextField, secretTextField;
    private Spinner typeSpinner, authSpinner, updatesSpinner;
    private SwitchCompat dropFilesSwitch;

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
        dropFilesSwitch = findViewById(R.id.dropFilesSwitch);

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

            // what the user picked when adding it, which is what the form starts from
            Intent intent = getIntent();
            if (intent.hasExtra(PARAM_TYPE)) {
                source.setType(NumberSource.Type.valueOf(intent.getStringExtra(PARAM_TYPE)));

                source.setName(freeName(getString(SourceNames.getTypeName(source.getType()))));
                source.setUrl(intent.getStringExtra(PARAM_URL));
                source.setAuth(NumberSource.Auth.valueOf(intent.getStringExtra(PARAM_AUTH)));
            }
        }

        if (savedInstanceState == null) fill();

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAuthFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

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

        // a source that was never saved has nothing to delete, and nothing to copy either
        boolean saved = sourceService != null && sourceService.findById(source.getId()) != null;

        menu.findItem(R.id.menu_delete).setVisible(saved);
        menu.findItem(R.id.menu_duplicate).setVisible(saved);

        return true;
    }

    public void onSaveClicked(MenuItem item) {
        if (save()) finish();
    }

    /** Writes the form down, secret included; false when the form isn't a source yet. */
    private boolean save() {
        if (sourceService == null) return false;

        if (!apply(source)) return false;

        sourceService.save(source);

        // the secret is kept apart from the list, so it is saved apart from it too
        String secret = getString(secretTextField);
        if (source.getAuth() == NumberSource.Auth.NONE) {
            sourceService.setSecret(source.getId(), null);
        } else if (!TextUtils.isEmpty(secret)) {
            sourceService.setSecret(source.getId(), secret);
        }

        return true;
    }

    /** The button under the form does what the menu item does. */
    public void onTestButtonClicked(View view) {
        onTestClicked(null);
    }

    /**
     * Saves and fetches: what is fetched is the stored source, so the form is written down
     * first - pressing "fetch now" on an address that was just typed means that address.
     * The list is where the fetch is followed, so the screen closes onto it.
     */
    public void onFetchClicked(View view) {
        if (!save()) return;

        boolean database = source.getType() == NumberSource.Type.DATABASE;

        if (!database) {
            Toast.makeText(this, R.string.source_fetching, Toast.LENGTH_SHORT).show();
        }

        BuildStarter.start(this, database
                        ? TaskService.TASK_DOWNLOAD_MAIN_DB
                        : TaskService.TASK_UPDATE_PHONE_BLOCK,
                DbCompileService.Trigger.FORCED);

        finish();
    }

    /**
     * Tries the source out as it stands on the screen, which is not always what is stored:
     * an address that was just typed is exactly the one worth trying.
     */
    public void onTestClicked(MenuItem item) {
        NumberSource candidate = new NumberSource(source.getId());
        if (!apply(candidate)) return;

        String secret = getString(secretTextField);
        if (TextUtils.isEmpty(secret) && sourceService != null) {
            secret = sourceService.getSecret(source.getId()); // kept, rather than typed again
        }

        TextView status = findViewById(R.id.status);
        status.setText(R.string.source_test_running);

        SourceTestHelper.test(this, candidate, secret, (result, message) -> {
            if (isFinishing()) return;

            status.setText(message);

            /*
             * And what it found is kept: what is behind a URL decides how the source is read,
             * so having just been told, the source is not left guessing. It is written down
             * on the stored source rather than only on the one that was tested, because the
             * user may well close the screen having read the answer and nothing else.
             */
            if (result.isOk() && result.content != ArchiveUtils.Content.UNKNOWN) {
                source.setContent(result.content);

                if (sourceService != null) sourceService.save(source);
            } else if (!result.isOk()) {
                /*
                 * And a test that failed is an answer too: whatever the row was claiming
                 * about this source described an address that doesn't answer like that any
                 * more, so it stops claiming it.
                 */
                source.forgetFetched();
                source.setLastResult(message);

                if (sourceService != null) sourceService.save(source);
            }
        });
    }

    /** Puts what is on the screen into a source; false when there isn't enough of it. */
    private boolean apply(NumberSource target) {
        /*
         * A name, and one that isn't taken: it is what this source is called in the list, in
         * the build log and in anything the app says about where a number came from, and two
         * sources with the same name make all three of those useless.
         */
        String name = getString(nameTextField);

        if (TextUtils.isEmpty(name)) {
            nameTextField.setError(getString(R.string.source_name_empty));
            return false;
        }

        if (isNameTaken(target, name)) {
            nameTextField.setError(getString(R.string.source_name_taken));
            return false;
        }

        nameTextField.setError(null);

        String url = getString(urlTextField);
        if (TextUtils.isEmpty(url)) {
            urlTextField.setError(getString(R.string.source_url_empty));
            return false;
        }
        urlTextField.setError(null);

        /*
         * A new address makes everything the source said about itself wrong: the count, the
         * version and the kind of thing behind it all describe what the old one handed over.
         * Better to say nothing until the new one has been asked.
         */
        if (!url.equals(target.getUrl())) target.forgetFetched();

        target.setName(name);
        target.setUrl(url);
        target.setType(selected(typeSpinner, NumberSource.Type.values()));
        target.setAuth(selected(typeSpinner, NumberSource.Type.values())
                == NumberSource.Type.PHONE_BLOCK
                ? NumberSource.Auth.BEARER
                : selected(authSpinner, NumberSource.Auth.values()));
        target.setUsername(getString(usernameTextField));
        target.setUpdates(selected(updatesSpinner, NumberSource.Updates.values()));
        target.setDropFilesAfterBuild(dropFilesSwitch.isChecked());

        // whether a source is used is decided in the list, where all of them are side by side

        return true;
    }

    /** The name with a number after it when something else already has it. */
    private String freeName(String name) {
        return sourceService != null ? sourceService.freeName(source, name) : name;
    }

    private boolean isNameTaken(NumberSource target, String name) {
        return sourceService != null && sourceService.isNameTaken(target, name);
    }

    /**
     * Makes a second source out of this one, and opens it.
     *
     * <p>Copied as saved, not as the form has it: the form may hold half an edit, and what
     * the user asked for is "one more like this", which is the one on disk. The copy goes to
     * the end of the list with a name of its own and the same secret; it has fetched nothing
     * yet, so its row starts out blank.
     */
    public void onDuplicateClicked(MenuItem item) {
        if (sourceService == null) return;

        NumberSource copy = sourceService.duplicate(source.getId(),
                NumberSourcesActivity.copyName(this, source));
        if (copy == null) return;

        Toast.makeText(this, getString(R.string.source_duplicated, copy.getName()),
                Toast.LENGTH_SHORT).show();

        startActivity(getIntent(this, copy.getId()));
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

        dropFilesSwitch.setChecked(source.getDropFilesAfterBuild());
    }

    /** Only the fields the chosen way of logging in needs are shown. */
    private void updateAuthFields() {
        /*
         * A PhoneBlock account has one way of logging in and it isn't a choice: the token is
         * sent as a bearer token, which is what that API takes. Offering the three ways here
         * would be offering two that cannot work - so the token is asked for and nothing
         * else. It is the account's token, the same one the PhoneBlock screen asks for.
         */
        boolean phoneBlock = selected(typeSpinner, NumberSource.Type.values())
                == NumberSource.Type.PHONE_BLOCK;

        findViewById(R.id.authLabel).setVisibility(phoneBlock ? View.GONE : View.VISIBLE);
        authSpinner.setVisibility(phoneBlock ? View.GONE : View.VISIBLE);

        /*
         * Only a source that hands over files has any to drop. A PhoneBlock account keeps its
         * list itself, and there is nothing of it lying about for this to be about.
         */
        boolean files = selected(typeSpinner, NumberSource.Type.values())
                == NumberSource.Type.DATABASE;

        dropFilesSwitch.setVisibility(files ? View.VISIBLE : View.GONE);
        findViewById(R.id.dropFilesNotice).setVisibility(files ? View.VISIBLE : View.GONE);

        NumberSource.Auth auth = selected(authSpinner, NumberSource.Auth.values());

        usernameTextField.setVisibility(!phoneBlock && auth == NumberSource.Auth.BASIC
                ? View.VISIBLE : View.GONE);

        boolean needsSecret = phoneBlock || auth != NumberSource.Auth.NONE;
        secretTextField.setVisibility(needsSecret ? View.VISIBLE : View.GONE);
        findViewById(R.id.secretNotice).setVisibility(needsSecret ? View.VISIBLE : View.GONE);

        secretTextField.setHint(getString(phoneBlock
                ? R.string.source_secret_token : R.string.source_secret));
    }

    /**
     * What is known about this source: its version, its share of the database, and when it
     * was last asked. It used to be said about the database as a whole, which is no longer a
     * thing that exists - every number in it came from one of these.
     */
    private void updateStatus() {
        TextView status = findViewById(R.id.status);

        List<String> lines = new ArrayList<>(4);

        if (source.getVersion() > 0) {
            lines.add(getString(R.string.source_version, source.getVersion()));
        }

        if (source.getEntries() > 0) {
            lines.add(getString(R.string.db_filtering_status_numbers,
                    NumberFormat.getInstance().format(source.getEntries())));
        }

        if (source.getLastUpdate() > 0) {
            lines.add(getString(R.string.source_last_update, relative(source.getLastUpdate())));
        } else {
            lines.add(getString(R.string.source_never_fetched));
        }

        if (source.getLastCheck() > 0 && source.getLastCheck() != source.getLastUpdate()) {
            lines.add(getString(R.string.source_last_check, relative(source.getLastCheck())));
        }

        if (!TextUtils.isEmpty(source.getLastResult())) lines.add(source.getLastResult());

        status.setText(TextUtils.join("\n", lines));
    }

    private CharSequence relative(long time) {
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
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
