package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import dummydomain.yetanothercallblocker.data.BlacklistUtils;
import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.NumberInfoService;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.WhitelistItem;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.db.BlacklistItem;
import dummydomain.yetanothercallblocker.data.provider.Provider;
import dummydomain.yetanothercallblocker.data.provider.ProviderService;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;

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

        String whitelistName = numberInfo.whitelistItem != null
                ? numberInfo.whitelistItem.getName() : null;
        setText(view, R.id.whitelist_name, whitelistName);

        // what the user wrote down about the number, whichever list it is on
        setText(view, R.id.list_notes, getListNotes(numberInfo));

        // the lists the number is on, each with the entry that matched when it isn't the
        // number itself - a pattern covering the number is worth knowing about
        setText(view, R.id.whitelisted, NumberInfoUtils.getWhitelistStatus(context, numberInfo));
        setText(view, R.id.in_blacklist, NumberInfoUtils.getBlacklistStatus(context, numberInfo));

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
            for (int id : new int[]{R.id.action_copy, R.id.action_open_contact,
                    R.id.action_whitelist, R.id.action_whitelist_rule,
                    R.id.action_blacklist, R.id.action_blacklist_rule, R.id.action_contacts,
                    R.id.action_phone_block, R.id.action_reviews, R.id.action_web_review}) {
                view.findViewById(id).setVisibility(View.GONE);
            }

            view.findViewById(R.id.provider_actions).setVisibility(View.GONE);

            dialog.show();
            return;
        }

        String number = numberInfo.number;

        // a new entry starts out with what the number is known as
        String suggestedName = numberInfo.contactItem != null
                ? numberInfo.contactItem.displayName
                : numberInfo.featuredDatabaseItem != null
                ? numberInfo.featuredDatabaseItem.getName() : null;

        bindAction(view, R.id.action_copy, R.drawable.ic_content_copy_24dp,
                R.string.copy_number, true, () -> {
                    UiUtils.copyToClipboard(context, number);
                    dialog.dismiss();
                });

        bindAction(view, R.id.action_open_contact, R.drawable.ic_person_24dp,
                R.string.open_contact, numberInfo.contactItem != null, () -> {
                    IntentHelper.startActivity(context,
                            IntentHelper.getViewContactIntent(numberInfo.contactItem.id));
                    dialog.dismiss();
                });

        /*
         * Each list offers two things: this number, and the rule it falls under. An entry that
         * is the number itself is edited where it is; a rule covering a whole range is left
         * alone unless the user picks it, and this number can be given an entry of its own.
         *
         * The entry is opened for editing rather than saved right away, so that it can be
         * given a name or turned into a pattern first - the way the blacklist works.
         */
        NumberInfoService numberInfoService = YacbHolder.getNumberInfoService();

        WhitelistItem whitelistEntry = getEntryForNumber(numberInfo.whitelistItem);
        bindAction(view, R.id.action_whitelist, R.drawable.ic_check_24dp,
                whitelistEntry != null
                        ? R.string.edit_number_in_whitelist : R.string.allow_this_number, true,
                () -> {
                    context.startActivity(whitelistEntry != null
                            ? EditWhitelistItemActivity.getEditIntent(
                                    context, whitelistEntry.getPattern())
                            : EditWhitelistItemActivity.getIntent(
                                    context, suggestedName, number));
                    dialog.dismiss();
                });

        WhitelistItem whitelistRule = numberInfoService != null
                ? numberInfoService.getWhitelistRule(numberInfo) : null;
        bindAction(view, R.id.action_whitelist_rule, R.drawable.ic_check_24dp,
                whitelistRule != null ? context.getString(
                        R.string.edit_whitelist_rule, whitelistRule.getPattern()) : null,
                whitelistRule != null,
                () -> {
                    context.startActivity(EditWhitelistItemActivity.getEditIntent(
                            context, whitelistRule.getPattern()));
                    dialog.dismiss();
                });

        BlacklistItem blacklistEntry = getEntryForNumber(numberInfo.blacklistItem);
        bindAction(view, R.id.action_blacklist, R.drawable.ic_brick_24dp,
                blacklistEntry != null
                        ? R.string.edit_number_in_blacklist : R.string.block_this_number,
                true, () -> {
                    Intent intent;
                    if (blacklistEntry != null) {
                        intent = EditBlacklistItemActivity.getIntent(
                                context, blacklistEntry.getId());
                    } else {
                        intent = EditBlacklistItemActivity.getIntent(
                                context, suggestedName, number);
                    }

                    context.startActivity(intent);
                    dialog.dismiss();
                });

        BlacklistItem blacklistRule = numberInfoService != null
                ? numberInfoService.getBlacklistRule(numberInfo) : null;
        bindAction(view, R.id.action_blacklist_rule, R.drawable.ic_brick_24dp,
                blacklistRule != null ? context.getString(R.string.edit_blacklist_rule,
                        blacklistRule.getHumanReadablePattern()) : null,
                blacklistRule != null,
                () -> {
                    context.startActivity(EditBlacklistItemActivity.getIntent(
                            context, blacklistRule.getId()));
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
                R.string.online_reviews, true, () -> confirmForContact(context, numberInfo,
                        R.string.load_reviews_confirmation_message, () -> {
                            ReviewsActivity.startForNumber(context, number);
                            dialog.dismiss();
                        }));

        bindAction(view, R.id.action_web_review, R.drawable.ic_plus_24dp,
                R.string.add_web_review, true, () -> confirmForContact(context, numberInfo,
                        R.string.load_reviews_confirmation_message, () -> {
                            Uri uri = Uri.parse(
                                    YacbHolder.getWebService().getWebReviewsUrlPart() + number);
                            IntentHelper.startActivity(context, new Intent(Intent.ACTION_VIEW, uri));
                            dialog.dismiss();
                        }));

        addProviderActions(context, view, numberInfo, number, dialog);

        dialog.show();
    }

    /**
     * A row for every provider that is switched on: "look this number up over there".
     *
     * <p>Nothing is asked until one is tapped, and what that tells whom is what the
     * confirmation is about, so the rows are addresses and not lookups.
     */
    private static void addProviderActions(Context context, View view, NumberInfo numberInfo,
                                           String number, AlertDialog dialog) {
        ViewGroup container = view.findViewById(R.id.provider_actions);

        ProviderService providerService = YacbHolder.getProviderService();
        if (providerService == null) return;

        LayoutInflater inflater = LayoutInflater.from(context);

        for (Provider provider : providerService.getEnabledProviders()) {
            // a German phone book has nothing to say about an Australian number
            if (!provider.appliesTo(number)) continue;

            // an API is asked by the app rather than opened; nothing to offer here yet
            if (provider.getMode() == Provider.Mode.API) continue;

            String name = ProviderHelper.getName(context, provider);

            addProviderAction(context, container, inflater, numberInfo, dialog,
                    ProviderHelper.getUrl(provider, number), provider,
                    context.getString(R.string.provider_lookup, name),
                    R.drawable.ic_search_24dp);

            addProviderAction(context, container, inflater, numberInfo, dialog,
                    ProviderHelper.getReportUrl(provider, number), provider,
                    context.getString(R.string.provider_report, name),
                    R.drawable.ic_thumb_down_24dp);
        }
    }

    /** One row, when there is an address behind it. */
    private static void addProviderAction(Context context, ViewGroup container,
                                          LayoutInflater inflater, NumberInfo numberInfo,
                                          AlertDialog dialog, String url, Provider provider,
                                          CharSequence label, int iconResId) {
        if (TextUtils.isEmpty(url)) return; // an account it doesn't have, say

        String host = ProviderHelper.getHost(context, provider, url);

        View row = inflater.inflate(R.layout.info_dialog_action, container, false);

        row.<ImageView>findViewById(R.id.icon).setImageResource(iconResId);
        row.<TextView>findViewById(R.id.label).setText(label);

        row.setOnClickListener(v -> confirmForLookup(context, numberInfo, host, () -> {
            IntentHelper.startActivity(context, IntentHelper.getWebIntent(url));
            dialog.dismiss();
        }));

        container.addView(row);
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
        View row = bindRow(view, id, iconResId, applies, action);
        if (row != null) row.<TextView>findViewById(R.id.label).setText(labelResId);
    }

    private static void bindAction(View view, int id, int iconResId, CharSequence label,
                                   boolean applies, Runnable action) {
        View row = bindRow(view, id, iconResId, applies, action);
        if (row != null) row.<TextView>findViewById(R.id.label).setText(label);
    }

    /** @return the row to put a label on, or null when it doesn't apply to this number */
    private static View bindRow(View view, int id, int iconResId,
                                boolean applies, Runnable action) {
        View row = view.findViewById(id);

        if (!applies) {
            row.setVisibility(View.GONE);
            return null;
        }

        row.setVisibility(View.VISIBLE);
        row.<ImageView>findViewById(R.id.icon).setImageResource(iconResId);
        row.setOnClickListener(v -> action.run());

        return row;
    }

    /** What the user wrote down about the number in either list, or null when nothing. */
    private static String getListNotes(NumberInfo numberInfo) {
        if (numberInfo.blacklistItem != null
                && !TextUtils.isEmpty(numberInfo.blacklistItem.getNotes())) {
            return numberInfo.blacklistItem.getNotes();
        }

        if (numberInfo.whitelistItem != null
                && !TextUtils.isEmpty(numberInfo.whitelistItem.getNotes())) {
            return numberInfo.whitelistItem.getNotes();
        }

        return null;
    }

    /** The entry that is the number itself, or null when a pattern is what matched. */
    private static BlacklistItem getEntryForNumber(BlacklistItem item) {
        return item != null && BlacklistUtils.isLiteralPattern(item.getPattern()) ? item : null;
    }

    /** The entry that is the number itself, or null when a pattern is what matched. */
    private static WhitelistItem getEntryForNumber(WhitelistItem item) {
        return item != null && BlacklistUtils.isLiteralPattern(item.getPattern()) ? item : null;
    }

    /** Runs the action, after asking when the number is a contact's and would be sent away. */
    private static void confirmForLookup(Context context, NumberInfo numberInfo,
                                         String where, Runnable action) {
        confirmForContact(context, numberInfo,
                context.getString(R.string.web_lookup_confirmation_message, where), action);
    }

    private static void confirmForContact(Context context, NumberInfo numberInfo,
                                          int messageResId, Runnable action) {
        confirmForContact(context, numberInfo, context.getString(messageResId), action);
    }

    /** Runs the action, after asking when the number is a contact's. */
    private static void confirmForContact(Context context, NumberInfo numberInfo,
                                          CharSequence message, Runnable action) {
        if (numberInfo.contactItem == null) {
            action.run();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.are_you_sure)
                .setMessage(message)
                .setPositiveButton(R.string.yes, (d, w) -> action.run())
                .setNegativeButton(R.string.no, null)
                .show();
    }

}
