package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.PhoneBlockUpdateFinishedEvent;
import dummydomain.yetanothercallblocker.work.TaskService;

/**
 * The PhoneBlock account and its list: where the numbers come from, not what is done with them.
 *
 * <p>Whether those numbers are blocked is a blocking question and is answered with the other
 * blocking settings.
 */
public class PhoneBlockSettingsFragment extends BaseSettingsFragment {

    private static final String PREF_SCREEN_PHONE_BLOCK = "phoneBlockScreen";
    private static final String PREF_PHONE_BLOCK_INFO = "phoneBlockInfo";
    private static final String PREF_PHONE_BLOCK_UPDATE = "phoneBlockUpdate";
    private static final String PREF_PHONE_BLOCK_CHECK_TOKEN = "phoneBlockCheckToken";

    @Override
    protected String getScreenKey() {
        return PREF_SCREEN_PHONE_BLOCK;
    }

    @Override
    protected int getPreferencesResId() {
        return R.xml.phone_block_preferences;
    }

    @Override
    public void onStart() {
        super.onStart();

        EventUtils.register(this);

        updateListStatus();
    }

    @Override
    public void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Override
    protected void initScreen() {
        requirePreference(PREF_PHONE_BLOCK_INFO).setOnPreferenceClickListener(pref -> {
            String tokenPageUrl = PhoneBlockHelper.getTokenPageUrl();

            AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_category_phone_block)
                    .setMessage(pref.getSummary())
                    .setNegativeButton(R.string.back, null);

            // the token is on a page of the account, which is easier opened than typed
            if (tokenPageUrl != null) {
                builder.setPositiveButton(R.string.phone_block_get_token, (d, w) ->
                        IntentHelper.startActivity(requireContext(),
                                IntentHelper.getWebIntent(tokenPageUrl)));
            }

            builder.show();
            return true;
        });

        requirePreference(PREF_PHONE_BLOCK_UPDATE).setOnPreferenceClickListener(preference -> {
            TaskService.start(requireContext(), TaskService.TASK_UPDATE_PHONE_BLOCK);
            return true;
        });

        requirePreference(PREF_PHONE_BLOCK_CHECK_TOKEN).setOnPreferenceClickListener(preference -> {
            checkToken();
            return true;
        });

        setPrefChangeListener(Settings.PREF_PHONE_BLOCK_TOKEN, (preference, newValue) -> {
            // whatever was known about the old token doesn't apply to this one
            App.getSettings().resetPhoneBlockTokenState();
            NotificationHelper.hidePhoneBlockTokenNotification(requireContext());
            return true;
        });

        setPrefChangeListener(Settings.PREF_USE_PHONE_BLOCK, (preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                // there is nothing to use until the list has been fetched
                TaskService.start(requireContext(), TaskService.TASK_UPDATE_PHONE_BLOCK);
            }
            return true;
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onPhoneBlockUpdateFinished(PhoneBlockUpdateFinishedEvent event) {
        updateListStatus();

        int size = event.result.size;

        String message;
        switch (event.result.status) {
            case UPDATED:
                message = getString(R.string.phone_block_update_result, size);
                break;

            case NOT_DUE:
                message = getString(R.string.phone_block_update_not_due, size);
                break;

            case NOT_CONFIGURED:
                return; // the list is turned off, there is nothing to say

            default:
                message = getString(R.string.phone_block_update_failed);
                break;
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    /** Says how big the list is and when it was last fetched. */
    private void updateListStatus() {
        requirePreference(PREF_PHONE_BLOCK_UPDATE)
                .setSummary(PhoneBlockHelper.getListStatus(requireContext()));
    }

    /** Asks PhoneBlock whether the token works and says what it answered. */
    private void checkToken() {
        Context context = requireContext().getApplicationContext();
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

}
