package net.evolution515.callblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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

import net.evolution515.callblocker.data.PhoneBlockService;
import net.evolution515.callblocker.data.YacbHolder;
import net.evolution515.callblocker.data.provider.Provider;
import net.evolution515.callblocker.data.provider.ProviderService;

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
            patternTextField, usernameTextField, passwordTextField, apiUrlTextField,
            tokenTextField;
    private Spinner authSpinner, typeSpinner;
    private SwitchCompat enabledSwitch, reportEnabledSwitch;

    /**
     * What the type spinner offers: addresses (null), or one of the APIs. One choice rather
     * than a switch and a second spinner that only means something when the switch is on.
     */
    private static final Provider.Api[] TYPES = {
            null, Provider.Api.PHONE_BLOCK, Provider.Api.TELLOWS, Provider.Api.CUSTOM};

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
        passwordTextField = findViewById(R.id.passwordTextField);
        apiUrlTextField = findViewById(R.id.apiUrlTextField);
        tokenTextField = findViewById(R.id.tokenTextField);
        authSpinner = findViewById(R.id.authSpinner);
        typeSpinner = findViewById(R.id.typeSpinner);
        enabledSwitch = findViewById(R.id.enabledSwitch);
        reportEnabledSwitch = findViewById(R.id.reportEnabledSwitch);

        setUpSpinner(authSpinner, Provider.Auth.values(), this::getAuthName);
        setUpSpinner(typeSpinner, TYPES, this::getTypeName);

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

        authSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAuthFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateMode();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextWatcher tokenWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateTokenField();
            }
        };

        editTextOf(searchUrlTextField).addTextChangedListener(tokenWatcher);
        editTextOf(reportUrlTextField).addTextChangedListener(tokenWatcher);

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

        updateTokenField();
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

        boolean api = isApi();

        String searchUrl = getString(searchUrlTextField);
        String reportUrl = getString(reportUrlTextField);
        String apiUrl = getString(apiUrlTextField);

        // a provider switched to addresses keeps the API it had, for when it is switched back
        Provider.Api apiKind = api ? selected(typeSpinner, TYPES) : provider.getApi();

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
        provider.setReportEnabled(reportEnabledSwitch.isChecked());

        providerService.save(provider);

        // the token is kept apart from the list, so it is saved apart from it too
        providerService.setSecret(provider.getId(), getString(tokenTextField));

        // and the password apart from the token: a login is not an API key
        providerService.setPassword(provider.getId(), getString(passwordTextField));

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
        select(typeSpinner, TYPES,
                provider.getMode() == Provider.Mode.API ? provider.getApi() : null);

        if (providerService != null) {
            setString(tokenTextField, providerService.getSecret(provider.getId()));
            setString(passwordTextField, providerService.getPassword(provider.getId()));
        }

        enabledSwitch.setChecked(provider.isEnabled());
        reportEnabledSwitch.setChecked(provider.isReportEnabled());
    }

    /** Addresses and an API are two ways of asking; only one of them is filled in. */
    private void updateMode() {
        boolean api = isApi();

        findViewById(R.id.urlsBlock).setVisibility(api ? View.GONE : View.VISIBLE);
        findViewById(R.id.apiBlock).setVisibility(api ? View.VISIBLE : View.GONE);

        if (!api) updateAuthFields();
        if (api) updateApiFields();

        updateTokenField();
    }

    /** A user name and a password are only asked for where the login has them. */
    private void updateAuthFields() {
        boolean basic = selected(authSpinner, Provider.Auth.values()) == Provider.Auth.BASIC;

        usernameTextField.setVisibility(basic ? View.VISIBLE : View.GONE);
        passwordTextField.setVisibility(basic ? View.VISIBLE : View.GONE);

        updateTokenField();
    }

    /**
     * The token is asked for where one is any use, and nowhere else.
     *
     * <p>That is: an API, which is opened with a key; a login that says Bearer; an address
     * that has {@code {token}} written into it; and PhoneBlock, whose row holds the token of
     * the account the app reports numbers to whatever else is set here.
     */
    private void updateTokenField() {
        findViewById(R.id.tokenBlock).setVisibility(wantsToken() ? View.VISIBLE : View.GONE);
    }

    private boolean wantsToken() {
        if (Provider.ID_PHONE_BLOCK.equals(provider.getId())) return true;

        if (isApi()) return true;

        if (selected(authSpinner, Provider.Auth.values()) == Provider.Auth.BEARER) return true;

        return getString(searchUrlTextField).contains(Provider.PLACEHOLDER_TOKEN)
                || getString(reportUrlTextField).contains(Provider.PLACEHOLDER_TOKEN);
    }

    /** The two APIs the app knows live at addresses it knows; only a custom one is typed. */
    private void updateApiFields() {
        Provider.Api api = selected(typeSpinner, TYPES);
        if (api == null) return;

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

    /** Whether the type is one of the APIs rather than addresses. */
    private boolean isApi() {
        return selected(typeSpinner, TYPES) != null;
    }

    private String getTypeName(Provider.Api api) {
        if (api == null) return getString(R.string.provider_type_url);

        switch (api) {
            case PHONE_BLOCK: return "PhoneBlock API";
            case TELLOWS: return "tellows API";
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

    private EditText editTextOf(TextInputLayout textInputLayout) {
        return Objects.requireNonNull(textInputLayout.getEditText());
    }

    private String getString(TextInputLayout textInputLayout) {
        return editTextOf(textInputLayout).getText().toString().trim();
    }

    private void setString(TextInputLayout textInputLayout, String value) {
        editTextOf(textInputLayout).setText(value);
    }

}
