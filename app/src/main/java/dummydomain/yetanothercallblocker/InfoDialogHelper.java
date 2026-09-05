package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.Whitelist;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

/**
 * The dialog about a number: what is known about it on top, what can be done with it below.
 *
 * <p>The actions are one list of rows rather than dialog buttons, because with six of them
 * (and the length of their translations) buttons end up stacked in a column anyway.
 */
public class InfoDialogHelper {

    public static void showDialog(Context context, NumberInfo numberInfo,
                                  DialogInterface.OnDismissListener onDismissListener) {
        showDialog(context, numberInfo, null, onDismissListener);
    }

    /**
     * @param callInfo what is known about the call the dialog was opened from, may be null
     *                 (it is when the dialog is opened from a notification)
     */
    public static void showDialog(Context context, NumberInfo numberInfo, CharSequence callInfo,
                                  DialogInterface.OnDismissListener onDismissListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context).inflate(R.layout.info_dialog, null);
        builder.setView(view);

        // what is known about the number

        // the number is part of the view rather than the dialog title, so that it is centered
        // like everything else in the header
        setText(view, R.id.number, !numberInfo.noNumber
                ? numberInfo.number : context.getString(R.string.no_number));

        String contactName = numberInfo.contactItem != null
                ? numberInfo.contactItem.displayName : null;
        setText(view, R.id.name, contactName);

        String featuredName = numberInfo.featuredDatabaseItem != null
                ? numberInfo.featuredDatabaseItem.getName() : null;
        setText(view, R.id.featured_name, featuredName);

        NumberCategory category = numberInfo.communityDatabaseItem != null
                ? NumberCategory.getById(numberInfo.communityDatabaseItem.getCategory())
                : null;
        setText(view, R.id.category, category != null && category != NumberCategory.NONE
                ? SiaNumberCategoryUtils.getName(context, category) : null);

        ReviewsSummaryHelper.populateSummary(view.findViewById(R.id.reviews_summary),
                numberInfo.communityDatabaseItem);

        String blacklistName = numberInfo.blacklistItem != null
                ? numberInfo.blacklistItem.getName() : null;
        setText(view, R.id.blacklist_name, blacklistName);

        view.findViewById(R.id.whitelisted).setVisibility(
                numberInfo.whitelisted ? View.VISIBLE : View.GONE);

        TextView inBlacklistView = view.findViewById(R.id.in_blacklist);
        if (numberInfo.blacklistItem != null) {
            if (numberInfo.contactItem != null) {
                inBlacklistView.setText(R.string.info_in_blacklist_contact);
            }
        } else {
            inBlacklistView.setVisibility(View.GONE);
        }

        TextView phoneBlockView = view.findViewById(R.id.phone_block);
        String phoneBlockText = NumberInfoUtils.getPhoneBlockStatus(context, numberInfo);
        if (!TextUtils.isEmpty(phoneBlockText)) {
            phoneBlockView.setText(phoneBlockText);
            phoneBlockView.setTextColor(UiUtils.getColorInt(context,
                    numberInfo.phoneBlockPersonalAllowed
                            ? R.color.ratePositive : R.color.rateNegative));
        } else {
            phoneBlockView.setVisibility(View.GONE);
        }

        setText(view, R.id.call_info, callInfo);

        if (onDismissListener != null) builder.setOnDismissListener(onDismissListener);

        AlertDialog dialog = builder.create();

        // what can be done with it

        if (numberInfo.noNumber) {
            view.findViewById(R.id.actions_divider).setVisibility(View.GONE);
            for (int id : new int[]{R.id.action_whitelist, R.id.action_blacklist,
                    R.id.action_contacts, R.id.action_phone_block, R.id.action_reviews,
                    R.id.action_web_review}) {
                view.findViewById(id).setVisibility(View.GONE);
            }

            dialog.show();
            return;
        }

        String number = numberInfo.number;

        bindAction(view, R.id.action_whitelist, R.drawable.ic_check_24dp,
                R.string.add_to_whitelist, !numberInfo.whitelisted, () -> {
                    Settings settings = App.getSettings();
                    settings.setWhitelist(Whitelist.add(settings.getWhitelist(), number));

                    Toast.makeText(context, R.string.added_to_whitelist, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });

        bindAction(view, R.id.action_blacklist, R.drawable.ic_brick_24dp,
                numberInfo.blacklistItem != null
                        ? R.string.edit_blacklist_entry : R.string.add_to_blacklist,
                true, () -> {
                    FeaturedDatabaseItem featuredItem = numberInfo.featuredDatabaseItem;
                    String name = featuredItem != null ? featuredItem.getName() : null;
                    context.startActivity(EditBlacklistItemActivity.getIntent(context, name, number));
                    dialog.dismiss();
                });

        bindAction(view, R.id.action_contacts, R.drawable.ic_person_24dp,
                R.string.add_to_contacts, numberInfo.contactItem == null, () -> {
                    IntentHelper.startActivity(context, IntentHelper.getAddToContactsIntent(number));
                    dialog.dismiss();
                });

        // reporting is only offered when there's an account to report with;
        // the dialog stays until the report is done, so that going back returns to it
        bindAction(view, R.id.action_phone_block, R.drawable.ic_thumb_down_24dp,
                R.string.phone_block_report_title_short, PhoneBlockHelper.canReport(),
                () -> PhoneBlockHelper.showReportDialog(context, number, dialog::dismiss));

        // the reviews are fetched from the web, which tells the web service about the number:
        // for a contact, that is asked about first
        bindAction(view, R.id.action_reviews, R.drawable.ic_thumbs_up_down_24dp,
                R.string.online_reviews, true, () -> confirmForContact(context, numberInfo, () -> {
                    ReviewsActivity.startForNumber(context, number);
                    dialog.dismiss();
                }));

        bindAction(view, R.id.action_web_review, R.drawable.ic_plus_24dp,
                R.string.add_web_review, true, () -> confirmForContact(context, numberInfo, () -> {
                    Uri uri = Uri.parse(YacbHolder.getWebService().getWebReviewsUrlPart() + number);
                    IntentHelper.startActivity(context, new Intent(Intent.ACTION_VIEW, uri));
                    dialog.dismiss();
                }));

        dialog.show();
    }

    /** Shows the text, or hides the view when there is none. */
    private static void setText(View view, int id, CharSequence text) {
        TextView textView = view.findViewById(id);

        if (!TextUtils.isEmpty(text)) {
            textView.setText(text);
        } else {
            textView.setVisibility(View.GONE);
        }
    }

    /** Sets up one of the action rows, or hides it when it doesn't apply. */
    private static void bindAction(View view, int id, int iconResId, int labelResId,
                                   boolean applies, Runnable action) {
        View row = view.findViewById(id);

        if (!applies) {
            row.setVisibility(View.GONE);
            return;
        }

        row.<ImageView>findViewById(R.id.icon).setImageResource(iconResId);
        row.<TextView>findViewById(R.id.label).setText(labelResId);
        row.setOnClickListener(v -> action.run());
    }

    /** Runs the action, after asking when the number is a contact's. */
    private static void confirmForContact(Context context, NumberInfo numberInfo, Runnable action) {
        if (numberInfo.contactItem == null) {
            action.run();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.are_you_sure)
                .setMessage(R.string.load_reviews_confirmation_message)
                .setPositiveButton(R.string.yes, (d, w) -> action.run())
                .setNegativeButton(R.string.no, null)
                .show();
    }

}
