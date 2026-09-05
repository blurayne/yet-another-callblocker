package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.PhoneBlockList;
import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * The parts of PhoneBlock that need the user: reporting a number, and the account it needs.
 *
 * <p>Everything else the app does about a call happens on the device; a report is the one thing
 * that sends something out, so it is always the user pressing a button, it says what is sent, and
 * it asks for a comment: a report without one tells the community nothing.
 */
public class PhoneBlockHelper {

    /** The ratings that can be reported, in the order they are offered. */
    private static final PhoneBlockList.Rating[] RATINGS = {
            PhoneBlockList.Rating.ADVERTISING,
            PhoneBlockList.Rating.FRAUD,
            PhoneBlockList.Rating.PING,
            PhoneBlockList.Rating.POLL,
            PhoneBlockList.Rating.GAMBLE,
            PhoneBlockList.Rating.MISSED,
            PhoneBlockList.Rating.LEGITIMATE,
    };

    /** Whether reporting is set up: PhoneBlock is used and there's a token to report with. */
    public static boolean canReport() {
        Settings settings = App.getSettings();
        return settings != null && newService(settings).canReport();
    }

    /**
     * Asks what the call was and reports it.
     *
     * @param onFinished run once the dialog is gone, may be null
     */
    public static void showReportDialog(Context context, String number, Runnable onFinished) {
        Settings settings = App.getSettings();

        if (settings == null || TextUtils.isEmpty(number)) {
            if (onFinished != null) onFinished.run();
            return;
        }

        if (!newService(settings).canReport()) {
            Toast.makeText(context, R.string.phone_block_report_no_token, Toast.LENGTH_LONG).show();
            if (onFinished != null) onFinished.run();
            return;
        }

        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context)
                .inflate(R.layout.phone_block_report_dialog, null);

        RadioGroup ratingGroup = view.findViewById(R.id.ratings);
        EditText commentView = view.findViewById(R.id.comment);

        List<PhoneBlockList.Rating> ratings = new ArrayList<>();

        for (PhoneBlockList.Rating rating : RATINGS) {
            RadioButton button = new RadioButton(context);
            button.setId(ratings.size() + 1); // the group needs an id to keep the choice
            button.setText(NumberInfoUtils.getPhoneBlockRatingName(context, rating));
            ratingGroup.addView(button);

            ratings.add(rating);
        }

        ratingGroup.check(1);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.phone_block_report_title, number))
                .setView(view)
                .setPositiveButton(R.string.phone_block_report_submit, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        if (onFinished != null) {
            dialog.setOnDismissListener(d -> onFinished.run());
        }

        // the dialog stays open when the comment is missing, so nothing that was typed is lost
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String comment = commentView.getText().toString().trim();

                    if (TextUtils.isEmpty(comment)) {
                        commentView.setError(
                                context.getString(R.string.phone_block_report_comment_required));
                        return;
                    }

                    int index = ratingGroup.getCheckedRadioButtonId() - 1;
                    if (index < 0 || index >= ratings.size()) return;

                    report(context.getApplicationContext(), settings,
                            number, ratings.get(index), comment);

                    dialog.dismiss();
                }));

        dialog.show();
    }

    private static void report(Context context, Settings settings, String number,
                               PhoneBlockList.Rating rating, String comment) {
        Toast.makeText(context, R.string.phone_block_report_sending, Toast.LENGTH_SHORT).show();

        @SuppressLint("StaticFieldLeak") // the application context outlives the task
        AsyncTask<Void, Void, PhoneBlockService.ReportStatus> task
                = new AsyncTask<Void, Void, PhoneBlockService.ReportStatus>() {
            @Override
            protected PhoneBlockService.ReportStatus doInBackground(Void... voids) {
                return newService(settings).report(number, rating, comment);
            }

            @Override
            protected void onPostExecute(PhoneBlockService.ReportStatus status) {
                Toast.makeText(context, getResultMessage(status), Toast.LENGTH_LONG).show();

                if (status == PhoneBlockService.ReportStatus.UNAUTHORIZED) {
                    // the user is being told right now, so the background check doesn't repeat it
                    settings.setPhoneBlockTokenProblemNotified(true);
                }
            }
        };

        task.execute();
    }

    private static int getResultMessage(PhoneBlockService.ReportStatus status) {
        switch (status) {
            case REPORTED: return R.string.phone_block_report_done;
            case NO_TOKEN: return R.string.phone_block_report_no_token;
            case UNAUTHORIZED: return R.string.phone_block_token_invalid_text;
            case REJECTED: return R.string.phone_block_report_rejected;
            default: return R.string.phone_block_report_failed;
        }
    }

    /**
     * Checks the token unless it was checked within the last day, and says so if it stopped
     * working.
     *
     * <p>A revoked token would otherwise go unnoticed: the list is downloaded in the background
     * and rarely, so nothing on the screen would show that the account is gone.
     */
    public static void checkTokenIfDue(Context context, Settings settings) {
        PhoneBlockService.TokenStatus status = newService(settings).checkTokenIfDue();

        if (status == PhoneBlockService.TokenStatus.NOT_DUE) {
            // an update may have run into a rejected token since the last check
            status = settings.getPhoneBlockTokenValid()
                    ? PhoneBlockService.TokenStatus.OK : PhoneBlockService.TokenStatus.INVALID;
        }

        handleTokenStatus(context, settings, status);
    }

    /** Posts or takes back the notification about a token that stopped working. */
    public static void handleTokenStatus(Context context, Settings settings,
                                         PhoneBlockService.TokenStatus status) {
        switch (status) {
            case INVALID:
                if (!settings.getPhoneBlockTokenProblemNotified()) {
                    settings.setPhoneBlockTokenProblemNotified(true);
                    NotificationHelper.showPhoneBlockTokenNotification(context);
                }
                break;

            case OK:
            case NO_TOKEN: // a token that isn't there any more isn't a problem either
                if (settings.getPhoneBlockTokenProblemNotified()) {
                    settings.setPhoneBlockTokenProblemNotified(false);
                    NotificationHelper.hidePhoneBlockTokenNotification(context);
                }
                break;

            default: // the check didn't get through, which says nothing about the token
                break;
        }
    }

    private static PhoneBlockService newService(Settings settings) {
        return new PhoneBlockService(settings, YacbHolder.getPhoneBlockList());
    }

    private PhoneBlockHelper() {
    }

}
