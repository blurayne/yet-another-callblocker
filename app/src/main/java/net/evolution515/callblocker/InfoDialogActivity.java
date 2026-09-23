package net.evolution515.callblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.evolution515.callblocker.data.NumberInfo;
import net.evolution515.callblocker.data.YacbHolder;

public class InfoDialogActivity extends AppCompatActivity {

    public static final String PARAM_NUMBER = "number";

    /**
     * Opens the PhoneBlock report right away instead of the info dialog.
     *
     * <p>It is an action rather than an extra because two pending intents that differ only in
     * their extras count as the same one.
     */
    private static final String ACTION_REPORT = "net.evolution515.callblocker.REPORT";

    public static Intent getIntent(Context context, String number) {
        Intent intent = new Intent(context, InfoDialogActivity.class);
        intent.putExtra(PARAM_NUMBER, number);
        intent.setData(IntentHelper.getUriForPhoneNumber(number));
        return intent;
    }

    /** Reports the number to PhoneBlock, for the action on a call notification. */
    public static Intent getReportIntent(Context context, String number) {
        return getIntent(context, number).setAction(ACTION_REPORT);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String number = getIntent().getStringExtra(PARAM_NUMBER);

        if (ACTION_REPORT.equals(getIntent().getAction())) {
            PhoneBlockHelper.showReportDialog(this, number, this::finish);
            return;
        }

        NumberInfo numberInfo = YacbHolder.getNumberInfo(number,
                App.getSettings().getCachedAutoDetectedCountryCode());

        InfoDialogHelper.showDialog(this, numberInfo, (d) -> finish());
    }

}
