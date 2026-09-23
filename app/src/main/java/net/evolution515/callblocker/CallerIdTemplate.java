package net.evolution515.callblocker;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import net.evolution515.callblocker.data.NumberInfo;
import net.evolution515.callblocker.data.PhoneBlockList;
import net.evolution515.callblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;

/**
 * What the phone app is told about a caller, written the way the user wants it.
 *
 * <p>The app has an answer of its own - a name, and a line of detail next to it - and for
 * most people that is the end of it. Someone who wants the number's category and nothing
 * else, or the ratings in front of the name, can say so here instead, and what they write is
 * what the dialer shows.
 *
 * <p>A template of several lines is split: the first line goes where the name goes, the rest
 * where the detail goes. Most phone apps show both, some show only the first, and none of
 * them show more than those two - which is why the rest is joined into one line rather than
 * handed over as it was typed.
 *
 * <p>A placeholder with nothing behind it disappears, and so does a line that is left empty
 * by it: a template written for a number the community knows well should not leave a trail
 * of separators on a number nobody has ever rated. When nothing at all is left - the template
 * asks for a category and this number has none - the app falls back to its own answer rather
 * than saying nothing: a warning that was there to give is worth more than the wording.
 */
public class CallerIdTemplate {

    /** What the app would have shown by itself. */
    public static final String NAME = "{name}";

    /** The line the app would have put next to it. */
    public static final String INFO = "{info}";

    public static final String NUMBER = "{number}";

    /** The contact's name, when the number is in the contacts. */
    public static final String CONTACT = "{contact}";

    /** The business name the databases have for the number, when they have one. */
    public static final String BUSINESS = "{business}";

    /** What the community database says the number is used for. */
    public static final String CATEGORY = "{category}";

    /** One word: negative, positive or neutral. */
    public static final String RATING = "{rating}";

    public static final String NEGATIVE = "{negative}";
    public static final String POSITIVE = "{positive}";
    public static final String NEUTRAL = "{neutral}";

    /** The name of the list entry that matched, when it is the number itself. */
    public static final String LIST = "{list}";

    /** What PhoneBlock says about the number. */
    public static final String PHONE_BLOCK = "{phoneblock}";

    private CallerIdTemplate() {
    }

    /** Whether the user has written one; without one the app answers the way it always has. */
    public static boolean isSet(Settings settings) {
        return settings != null && !TextUtils.isEmpty(settings.getCallerIdTemplate());
    }

    /**
     * The template with the number's own facts in it.
     *
     * @return what to show, or null when the template says nothing about this number
     */
    public static String render(Context context, NumberInfo numberInfo, String template) {
        if (TextUtils.isEmpty(template) || numberInfo == null) return null;

        CommunityDatabaseItem item = numberInfo.communityDatabaseItem;

        String text = template;

        text = replace(text, NAME, NumberInfoUtils.getDefaultCallerIdName(context, numberInfo));
        text = replace(text, INFO, NumberInfoUtils.getDefaultCallerIdLabel(context, numberInfo));
        text = replace(text, NUMBER, numberInfo.number);
        text = replace(text, CONTACT, numberInfo.contactItem != null
                ? numberInfo.contactItem.displayName : null);
        text = replace(text, BUSINESS, NumberInfoUtils.getBusinessName(numberInfo));
        text = replace(text, CATEGORY, getCategory(context, item));
        text = replace(text, RATING, getRating(context, numberInfo));
        text = replace(text, NEGATIVE, item != null
                ? String.valueOf(item.getNegativeRatingsCount()) : null);
        text = replace(text, POSITIVE, item != null
                ? String.valueOf(item.getPositiveRatingsCount()) : null);
        text = replace(text, NEUTRAL, item != null
                ? String.valueOf(item.getNeutralRatingsCount()) : null);
        text = replace(text, LIST, NumberInfoUtils.getListEntryName(numberInfo));
        text = replace(text, PHONE_BLOCK, NumberInfoUtils.getPhoneBlockStatus(context, numberInfo));

        return tidy(text);
    }

    /**
     * What a caller would look like with this template, for the screen that sets it.
     *
     * <p>Judging a template by its own text is guesswork - what {@code {category}} comes to
     * depends on the number - so it is tried out on one that has something of everything:
     * a number the community calls a telemarketer, rated by ten people, and known to
     * PhoneBlock as advertising.
     *
     * @return {@code {name, label}}, the two things a phone app is handed; either may be null
     */
    public static String[] preview(Context context, String template) {
        NumberInfo sample = sample();

        String custom = render(context, sample, template);

        if (custom != null) return new String[]{firstLine(custom), rest(custom)};

        return new String[]{
                NumberInfoUtils.getDefaultCallerIdName(context, sample),
                NumberInfoUtils.getDefaultCallerIdLabel(context, sample)};
    }

    /** A number that has something of everything, so that a template shows what it does. */
    private static NumberInfo sample() {
        NumberInfo numberInfo = new NumberInfo();

        numberInfo.number = "+493012345678";
        numberInfo.normalizedNumber = numberInfo.number;
        numberInfo.rating = NumberInfo.Rating.NEGATIVE;

        CommunityDatabaseItem item = new CommunityDatabaseItem();
        item.setCategory(NumberCategory.TELEMARKETER.getId());
        item.setNegativeRatingsCount(7);
        item.setPositiveRatingsCount(1);
        item.setNeutralRatingsCount(2);

        numberInfo.communityDatabaseItem = item;
        numberInfo.phoneBlockRating = PhoneBlockList.Rating.ADVERTISING;

        return numberInfo;
    }

    /** The first line of it, which is where a name goes. */
    public static String firstLine(String text) {
        if (text == null) return null;

        int newline = text.indexOf('\n');

        return newline >= 0 ? text.substring(0, newline) : text;
    }

    /** Everything after the first line, in one line, or null when there is nothing. */
    public static String rest(String text) {
        if (text == null) return null;

        int newline = text.indexOf('\n');
        if (newline < 0) return null;

        String rest = text.substring(newline + 1).replace('\n', ' ').trim();

        return !rest.isEmpty() ? rest : null;
    }

    private static String getCategory(Context context, CommunityDatabaseItem item) {
        return item != null ? SiaNumberCategoryUtils.getName(context, item.getCategory()) : null;
    }

    private static String getRating(Context context, NumberInfo numberInfo) {
        switch (numberInfo.rating) {
            case NEGATIVE:
                return context.getString(R.string.notification_incoming_call_negative);

            case POSITIVE:
                return context.getString(R.string.notification_incoming_call_positive);

            case NEUTRAL:
                return context.getString(R.string.notification_incoming_call_neutral);

            default:
                return null;
        }
    }

    private static String replace(String text, String placeholder, String value) {
        return text.contains(placeholder)
                ? text.replace(placeholder, value != null ? value : "") : text;
    }

    /**
     * Throws away what the empty placeholders left behind.
     *
     * <p>Whatever stands between two of them - a dash, a bullet, a comma - is only there to
     * separate something from something else, so a line that is left with nothing but that is
     * dropped, as are the double spaces in the middle of a line that kept some of its parts.
     */
    private static String tidy(String text) {
        List<String> lines = new ArrayList<>();

        for (String line : text.split("\n", -1)) {
            line = line.replaceAll("\\s{2,}", " ").trim();

            // a line of separators only: nothing of the number ended up in it
            if (line.isEmpty() || line.replaceAll("[\\p{Punct}\\s]", "").isEmpty()) continue;

            lines.add(line);
        }

        return !lines.isEmpty() ? TextUtils.join("\n", lines) : null;
    }

}
