package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Predicate;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback,
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private static final String EXTRA_SCREEN = "screen";

    /** The filter, under the database screen, as if it had been walked to. */
    public static final String SCREEN_DB_FILTERING = "dbFiltering";

    private static final String SCREEN_DB_MANAGEMENT = "dbManagement";

    public static Intent getIntent(Context context, String screen) {
        return new Intent(context, SettingsActivity.class).putExtra(EXTRA_SCREEN, screen);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new RootSettingsFragment())
                    .commit();

            /*
             * Opened on a screen further in: the screens on the way are put on the back stack
             * as well, so that back walks out the way it would have been walked in.
             */
            if (SCREEN_DB_FILTERING.equals(getIntent().getStringExtra(EXTRA_SCREEN))) {
                push(new DbManagementSettingsFragment(), SCREEN_DB_MANAGEMENT);
                push(new DbFilteringSettingsFragment(), SCREEN_DB_FILTERING);
            }
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void push(Fragment fragment, String key) {
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
        fragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment, key)
                .addToBackStack(key)
                .commit();
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        return applyToBaseSettingsFragment(f -> f.onPreferenceStartScreen(caller, pref));
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        return applyToBaseSettingsFragment(f -> f.onPreferenceStartFragment(caller, pref));
    }

    private boolean applyToBaseSettingsFragment(Predicate<BaseSettingsFragment> predicate) {
        return applyToFragments(f -> f instanceof BaseSettingsFragment
                && predicate.test((BaseSettingsFragment) f));
    }

    private boolean applyToFragments(Predicate<Fragment> predicate) {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (predicate.test(fragment)) return true;
        }
        return false;
    }

}
