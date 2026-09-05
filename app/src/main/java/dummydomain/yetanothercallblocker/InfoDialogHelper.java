package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.Whitelist;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

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
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(!numberInfo.noNumber
                        ? numberInfo.number : context.getString(R.string.no_number));

        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context).inflate(R.layout.info_dialog, null);
        builder.setView(view);

        TextView categoryView = view.findViewById(R.id.category);

        NumberCategory category = numberInfo.communityDatabaseItem != null
                ? NumberCategory.getById(numberInfo.communityDatabaseItem.getCategory())
                : null;

        if (category != null && category != NumberCategory.NONE) {
            categoryView.setText(SiaNumberCategoryUtils.getName(context, category));
        } else {
            categoryView.setVisibility(View.GONE);
        }

        TextView nameView = view.findViewById(R.id.name);

        String contactName = numberInfo.contactItem != null
                ? numberInfo.contactItem.displayName : null;

        if (!TextUtils.isEmpty(contactName)) {
            nameView.setText(contactName);
        } else {
            nameView.setVisibility(View.GONE);
        }

        TextView featuredNameView = view.findViewById(R.id.featured_name);

        String featuredName = numberInfo.featuredDatabaseItem != null
                ? numberInfo.featuredDatabaseItem.getName() : null;

        if (!TextUtils.isEmpty(featuredName)) {
            featuredNameView.setText(featuredName);
        } else {
            featuredNameView.setVisibility(View.GONE);
        }

        String blacklistName = null;

        TextView whitelistedView = view.findViewById(R.id.whitelisted);
        if (!numberInfo.whitelisted) whitelistedView.setVisibility(View.GONE);

        TextView inBlacklistView = view.findViewById(R.id.in_blacklist);
        if (numberInfo.blacklistItem != null) {
            blacklistName = numberInfo.blacklistItem.getName();
            if (numberInfo.contactItem != null) {
                inBlacklistView.setText(R.string.info_in_blacklist_contact);
            }
        } else {
            inBlacklistView.setVisibility(View.GONE);
        }

        TextView blacklistNameView = view.findViewById(R.id.blacklist_name);
        if (!TextUtils.isEmpty(blacklistName)) {
            blacklistNameView.setText(blacklistName);
        } else {
            blacklistNameView.setVisibility(View.GONE);
        }

        ReviewsSummaryHelper.populateSummary(view.findViewById(R.id.reviews_summary),
                numberInfo.communityDatabaseItem);

        // a number that isn't in the contacts yet can be put there from here
        boolean canAddToContacts = !numberInfo.noNumber && numberInfo.contactItem == null;

        boolean canAddToWhitelist = !numberInfo.noNumber && !numberInfo.whitelisted;

        TextView addToWhitelistView = view.findViewById(R.id.add_to_whitelist);
        if (!canAddToWhitelist) addToWhitelistView.setVisibility(View.GONE);

        TextView addToContactsView = view.findViewById(R.id.add_to_contacts);
        if (!canAddToContacts) addToContactsView.setVisibility(View.GONE);

        // reporting is only offered when there's an account to report with
        boolean canReportToPhoneBlock = !numberInfo.noNumber && PhoneBlockHelper.canReport();

        TextView reportToPhoneBlockView = view.findViewById(R.id.report_to_phone_block);
        if (!canReportToPhoneBlock) reportToPhoneBlockView.setVisibility(View.GONE);

        TextView callInfoView = view.findViewById(R.id.call_info);
        if (!TextUtils.isEmpty(callInfo)) {
            callInfoView.setText(callInfo);
        } else {
            callInfoView.setVisibility(View.GONE);
        }

        if (onDismissListener != null) builder.setOnDismissListener(onDismissListener);

        if (numberInfo.noNumber) {
            builder.show();
            return;
        }

        Runnable reviewsAction = () -> ReviewsActivity.startForNumber(context, numberInfo.number);

        Runnable webReviewAction = () -> {
            Uri uri = Uri.parse(YacbHolder.getWebService().getWebReviewsUrlPart()
                    + numberInfo.number);
            IntentHelper.startActivity(context, new Intent(Intent.ACTION_VIEW, uri));
        };

        Runnable addToBlacklistAction = () -> {
            FeaturedDatabaseItem featuredDatabaseItem = numberInfo.featuredDatabaseItem;
            String name = featuredDatabaseItem != null ? featuredDatabaseItem.getName() : null;
            context.startActivity(EditBlacklistItemActivity
                    .getIntent(context, name, numberInfo.number));
        };

        builder.setPositiveButton(R.string.add_web_review, null)
                .setNeutralButton(R.string.online_reviews, null)
                .setNegativeButton(R.string.add_to_blacklist, (dialog, which)
                        -> addToBlacklistAction.run());

        AlertDialog dialog = builder.create();

        if (canAddToWhitelist) {
            addToWhitelistView.setOnClickListener(v -> {
                dialog.dismiss();

                Settings settings = App.getSettings();
                settings.setWhitelist(Whitelist.add(settings.getWhitelist(), numberInfo.number));

                Toast.makeText(context, R.string.added_to_whitelist, Toast.LENGTH_SHORT).show();
            });
        }

        if (canReportToPhoneBlock) {
            // the dialog stays until the report is done, so that going back returns to it
            reportToPhoneBlockView.setOnClickListener(v -> PhoneBlockHelper
                    .showReportDialog(context, numberInfo.number, dialog::dismiss));
        }

        if (canAddToContacts) {
            addToContactsView.setOnClickListener(v -> {
                dialog.dismiss();

                IntentHelper.startActivity(context,
                        IntentHelper.getAddToContactsIntent(numberInfo.number));
            });
        }

        // avoid dismissing the original dialog on button press

        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (numberInfo.contactItem != null) {
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.load_reviews_confirmation_message)
                            .setPositiveButton(R.string.yes, (d1, w) -> {
                                reviewsAction.run();
                                dialog.dismiss();
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                } else {
                    reviewsAction.run();
                    dialog.dismiss();
                }
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (numberInfo.contactItem != null) {
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.load_reviews_confirmation_message)
                            .setPositiveButton(R.string.yes, (d1, w) -> {
                                webReviewAction.run();
                                dialog.dismiss();
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                } else {
                    webReviewAction.run();
                    dialog.dismiss();
                }
            });
        });

        dialog.show();
    }

}
