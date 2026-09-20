package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
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

import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.Provider;
import dummydomain.yetanothercallblocker.data.provider.ProviderService;

/**
 * One provider: what it is called, where it sends the number, and the token it needs.
 *
 * <p>PhoneBlock is the one with more to it than an address - the token here is the account's,
 * the same one that is used to report a number - so that row says so and can ask whether the
 * token still works.
 */
public class EditProviderActivity extends AppCompatActivity {

    private static final String PARAM_ID = "providerId";

    public static Intent getIntent(Context context, String id) {
        Intent intent = new Intent(context, EditProviderActivity.class);
        if (id != null) intent.putExtra(PARAM_ID, id);
        return intent;
    }

    private final ProviderService providerService = YacbHolder.getProviderService();

    private Provider provider;

    private TextInputLayout nameTextField, searchUrlTextField, reportUrlTextField,
            patternTextField, usernameTextField, apiUrlTextField, tokenTextField;
    private Spinner authSpinner, apiSpinner;
    private SwitchCompat apiSwitch, enabledSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_provider);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        nameTextField = findViewById(R.id.nameTextField);
        searchUrlTextField = findViewById(R.id.searchUrlTextField);
        reportUrlTextField = findViewById(R.id.reportUrlTextField);
        patternTextField = findViewById(R.id.patternTextField);
        usernameTextField = findViewById(R.id.usernameTextField);
        apiUrlTextField = findViewById(R.id.apiUrlTextField);
        tokenTextField = findViewById(R.id.tokenTextField);
        authSpinner = findViewById(R.id.authSpinner);
        apiSpinner = findViewById(R.id.apiSpinner);
        apiSwitch = findViewById(R.id.apiSwitch);
        enabledSwitch = findViewById(R.id.enabledSwitch);

        setUpSpinner(authSpinner, Provider.Auth.values(), this::getAuthName);
        setUpSpinner(apiSpinner, Provider.Api.values(), this::getApiName);

        String id = getIntent().getStringExtra(PARAM_ID);
        provider = id != null && providerService != null ? providerService.findById(id) : null;

        if (id != null && provider == null) { // it was deleted while this screen was away
            finish();
            return;
        }

        if (provider != null) {
            setTitle(R.string.title_edit_provider_activity);
        } else {
            provider = new Provider();
        }

        if (savedInstanceState == null) fill();

        apiSwitch.setOnCheckedChangeListener((v, checked) -> updateMode());
        authSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAuthFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        apiSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateApiFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateMode();

        boolean phoneBlock = Provider.ID_PHONE_BLOCK.equals(provider.getId());

        findViewById(R.id.phoneBlockNotice).setVisibility(phoneBlock ? View.VISIBLE : View.GONE);
        findViewById(R.id.checkTokenButton).setVisibility(phoneBlock ? View.VISIBLE : View.GONE);

        // the token is on a page of the account, which is easier opened than typed
        findViewById(R.id.getTokenButton).setVisibility(
                phoneBlock && PhoneBlockHelper.getTokenPageUrl() != null
                        ? View.VISIBLE : View.GONE);

        tokenTextField.setHint(getString(phoneBlock
                ? R.string.provider_token_phone_block : R.string.provider_token));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_edit_provider, menu);

        // the three the app knows by itself are kept: switching one off is what there is
        menu.findItem(R.id.menu_delete).setVisible(!provider.isBuiltIn()
                && providerService != null && providerService.findById(provider.getId()) != null);

        return true;
    }

    public void onSaveClicked(MenuItem item) {
        if (providerService == null) return;

        boolean api = apiSwitch.isChecked();

        String searchUrl = getString(searchUrlTextField);
        String reportUrl = getString(reportUrlTextField);
        String apiUrl = getString(apiUrlTextField);

        Provider.Api apiKind = selected(apiSpinner, Provider.Api.values());

        if (api) {
            if (apiKind == Provider.Api.CUSTOM && TextUtils.isEmpty(apiUrl)) {
                apiUrlTextField.setError(getString(R.string.provider_url_empty));
                return;
            }
        } else if (TextUtils.isEmpty(searchUrl) && TextUtils.isEmpty(reportUrl)
                && !provider.hasOwnAddress()) {
            searchUrlTextField.setError(getString(R.string.provider_url_empty));
            return;
        }

        provider.setName(getString(nameTextField));
        provider.setMode(api ? Provider.Mode.API : Provider.Mode.URLS);
        provider.setSearchUrl(searchUrl);
        provider.setReportUrl(reportUrl);
        provider.setPattern(getString(patternTextField));
        provider.setAuth(selected(authSpinner, Provider.Auth.values()));
        provider.setUsername(getString(usernameTextField));
        provider.setApi(apiKind);
        provider.setApiUrl(apiUrl);
        provider.setEnabled(enabledSwitch.isChecked());

        providerService.save(provider);

        // the token is kept apart from the list, so it is saved apart from it too
        providerService.setSecret(provider.getId(), getString(tokenTextField));

        finish();
    }

    public void onDeleteClicked(MenuItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.are_you_sure)
                .setMessage(R.string.provider_delete_message)
                .setPositiveButton(R.string.source_delete, (d, w) -> {
                    if (providerService != null) providerService.remove(provider.getId());
                    finish();
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    /** Opens the page of the account the token is on. */
    public void onGetTokenClicked(View view) {
        String url = PhoneBlockHelper.getTokenPageUrl();
        if (url == null) return;

        IntentHelper.startActivity(this, IntentHelper.getWebIntent(url));
    }

    /** Asks PhoneBlock whether the token that is typed in works. */
    public void onCheckTokenClicked(View view) {
        if (providerService == null) return;

        // the check reads the token where the account keeps it, so what is typed goes in first
        providerService.setSecret(provider.getId(), getString(tokenTextField));

        Context context = getApplicationContext();
        Settings settings = App.getSettings();

        Toast.makeText(context, R.string.phone_block_checking_token, Toast.LENGTH_SHORT).show();

        @SuppressLint("StaticFieldLeak") // the application context outlives the task
        AsyncTask<Void, Void, PhoneBlockService.TokenStatus> task
                = new AsyncTask<Void, Void, PhoneBlockService.TokenStatus>() {
            @Override
            protected PhoneBlockService.TokenStatus doInBackground(Void... voids) {
                return new PhoneBlockService(settings, YacbHolder.getPhoneBlockList(),
                        YacbHolder.getPhoneBlockPersonalLists()).checkToken();
            }

            @Override
            protected void onPostExecute(PhoneBlockService.TokenStatus status) {
                PhoneBlockHelper.handleTokenStatus(context, settings, status);

                int message;
                switch (status) {
                    case OK: message = R.string.phone_block_token_ok; break;
                    case NO_TOKEN: message = R.string.phone_block_token_missing; break;
                    case INVALID: message = R.string.phone_block_token_invalid_text; break;
                    default: message = R.string.phone_block_check_token_failed; break;
                }

                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        };

        task.execute();
    }

    private void fill() {
        setString(nameTextField, ProviderHelper.getName(this, provider));
        setString(searchUrlTextField, provider.getSearchUrl());
        setString(reportUrlTextField, provider.getReportUrl());
        setString(patternTextField, provider.getPattern());
        setString(usernameTextField, provider.getUsername());
        setString(apiUrlTextField, provider.getApiUrl());

        select(authSpinner, Provider.Auth.values(), provider.getAuth());
        select(apiSpinner, Provider.Api.values(), provider.getApi());

        apiSwitch.setChecked(provider.getMode() == Provider.Mode.API);

        if (providerService != null) {
            setString(tokenTextField, providerService.getSecret(provider.getId()));
        }

        enabledSwitch.setChecked(provider.isEnabled());
    }

    /** Addresses and an API are two ways of asking; only one of them is filled in. */
    private void updateMode() {
        boolean api = apiSwitch.isChecked();

        findViewById(R.id.urlsBlock).setVisibility(api ? View.GONE : View.VISIBLE);
        findViewById(R.id.apiBlock).setVisibility(api ? View.VISIBLE : View.GONE);

        if (!api) updateAuthFields();
        if (api) updateApiFields();
    }

    /** A user name is only asked for where the login has one. */
    private void updateAuthFields() {
        usernameTextField.setVisibility(selected(authSpinner, Provider.Auth.values())
                == Provider.Auth.BASIC ? View.VISIBLE : View.GONE);
    }

    /** The two APIs the app knows live at addresses it knows; only a custom one is typed. */
    private void updateApiFields() {
        Provider.Api api = selected(apiSpinner, Provider.Api.values());

        apiUrlTextField.setVisibility(api == Provider.Api.CUSTOM ? View.VISIBLE : View.GONE);

        int notice;
        switch (api) {
            case PHONE_BLOCK: notice = R.string.provider_api_phone_block_notice; break;
            case TELLOWS: notice = R.string.provider_api_tellows_notice; break;
            default: notice = R.string.provider_api_custom_notice; break;
        }

        this.<TextView>findViewById(R.id.apiNotice).setText(notice);
    }

    private String getAuthName(Provider.Auth auth) {
        switch (auth) {
            case BEARER: return getString(R.string.source_auth_bearer);
            case BASIC: return getString(R.string.source_auth_basic);
            default: return getString(R.string.source_auth_none);
        }
    }

    private String getApiName(Provider.Api api) {
        switch (api) {
            case PHONE_BLOCK: return "PhoneBlock";
            case TELLOWS: return "tellows";
            default: return getString(R.string.provider_api_custom);
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
