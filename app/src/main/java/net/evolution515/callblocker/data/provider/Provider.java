package net.evolution515.callblocker.data.provider;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import net.evolution515.callblocker.data.BlacklistUtils;

/**
 * A place that knows something about a number.
 *
 * <p>The app doesn't ask any of them by itself - that would tell them who is calling whom -
 * so a provider is offered in the dialog about a call and only ever asked when the user
 * decides to ask. Most of them are an address with a place for the number in it; some speak
 * an API instead and want a token rather than a link.
 *
 * <p>A provider can say which numbers it is worth anything for: a German phone book has
 * nothing to say about an Australian number, and the row is left out rather than opened for
 * nothing. Without that, it applies to every number.
 *
 * <p>The token, where one is needed, is kept apart from the list, the way the passwords of
 * the sources are: the list can go into a backup, the token only when the user says so.
 */
public class Provider {

    /** How the app talks to it. */
    public enum Mode {
        /** Addresses that are opened in a browser. */
        URLS,
        /** An API the app asks itself, with a token. */
        API
    }

    /** Which API a provider speaks, for the ones that do. */
    public enum Api {
        PHONE_BLOCK, TELLOWS, CUSTOM
    }

    /** How the app says who it is, when an address wants to know. */
    public enum Auth {
        NONE, BEARER, BASIC
    }

    /** The account the app can also report numbers to; its page about a number. */
    public static final String ID_PHONE_BLOCK = "phoneblock";

    public static final String ID_TELLOWS = "tellows";

    /** Looking the number up on the web, which is where it has always gone. */
    public static final String ID_WEB_SEARCH = "websearch";

    public static final String ID_CLEVER_DIALER = "cleverdialer";

    /** The phone book, which knows the numbers that are in it rather than the spam ones. */
    public static final String ID_DASOERTLICHE = "dasoertliche";

    /**
     * Where the number goes in the address, in the form the provider wants it.
     *
     * <p>The same number is written four ways, and which one a site understands is a question
     * only that site can answer: one takes {@code +4930123456}, the next wants the same thing
     * as {@code 004930123456}, a third has no room for either sign, and a fourth knows only
     * the form the number has at home, {@code 030123456}.
     */
    public static final String PLACEHOLDER_NUMBER = "{number}";

    /** The international form with 00 instead of the plus. */
    public static final String PLACEHOLDER_NUMBER_00 = "{number00}";

    /** Nothing but the digits: no plus, no 00, no leading zero. */
    public static final String PLACEHOLDER_DIGITS = "{digits}";

    /** The form the number has in its own country, with the trunk zero, where that is known. */
    public static final String PLACEHOLDER_NATIONAL = "{national}";

    /** Where the token goes in the address, for a provider that wants one there. */
    public static final String PLACEHOLDER_TOKEN = "{token}";

    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_MODE = "mode";
    /** What the address used to be called, when there was only one. */
    private static final String KEY_URL = "url";
    private static final String KEY_SEARCH_URL = "searchUrl";
    private static final String KEY_REPORT_URL = "reportUrl";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_AUTH = "auth";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_API = "api";
    private static final String KEY_API_URL = "apiUrl";
    private static final String KEY_ENABLED = "enabled";

    /** Stays the same for the life of the provider: its token hangs off it. */
    private final String id;

    private String name;
    private Mode mode = Mode.URLS;
    private boolean enabled = true;

    /** Which numbers it is worth asking about; empty means all of them. */
    private String pattern;

    private String searchUrl;
    private String reportUrl;
    private Auth auth = Auth.NONE;
    private String username;

    private Api api = Api.CUSTOM;
    private String apiUrl;

    public Provider() {
        this(UUID.randomUUID().toString());
    }

    public Provider(String id) {
        this.id = !TextUtils.isEmpty(id) ? id : UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.URLS;
    }

    /** Whether the dialog about a call offers it. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The numbers this provider is offered for, as a pattern with {@code *} in it.
     *
     * <p>Empty means every number. {@code 49*} means the German ones - which is the whole of
     * what a German phone book can answer.
     */
    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /** The address of the page about a number, with {@link #PLACEHOLDER_NUMBER} in it. */
    public String getSearchUrl() {
        return searchUrl;
    }

    public void setSearchUrl(String searchUrl) {
        this.searchUrl = searchUrl;
    }

    /** Where a number is reported, when the provider takes reports through an address. */
    public String getReportUrl() {
        return reportUrl;
    }

    public void setReportUrl(String reportUrl) {
        this.reportUrl = reportUrl;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth != null ? auth : Auth.NONE;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api != null ? api : Api.CUSTOM;
    }

    /** Where the API lives, for one the app doesn't know by itself. */
    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    /** One of the three the app knows by itself, which are kept rather than deleted. */
    public boolean isBuiltIn() {
        return ID_PHONE_BLOCK.equals(id);
    }

    /** Whether the address is built by the app rather than written out here. */
    public boolean hasOwnAddress() {
        return ID_PHONE_BLOCK.equals(id) && TextUtils.isEmpty(searchUrl);
    }

    /** Whether there is enough here to do anything at all. */
    public boolean isValid() {
        if (mode == Mode.API) {
            return api != Api.CUSTOM || !TextUtils.isEmpty(apiUrl);
        }

        return !TextUtils.isEmpty(searchUrl) || !TextUtils.isEmpty(reportUrl) || hasOwnAddress();
    }

    /**
     * Whether this provider has anything to say about the number.
     *
     * <p>The pattern is matched the way the two lists match theirs, so what is written here
     * means what it means everywhere else in the app.
     */
    public boolean appliesTo(String number) {
        if (TextUtils.isEmpty(pattern)) return true;
        if (TextUtils.isEmpty(number)) return false;

        String cleanNumber = BlacklistUtils.cleanNumber(number);
        String digits = cleanNumber.startsWith("+") ? cleanNumber.substring(1) : cleanNumber;

        return BlacklistUtils.matches(BlacklistUtils.patternFromHumanReadable(pattern), cleanNumber)
                || BlacklistUtils.matches(
                        BlacklistUtils.patternFromHumanReadable(pattern), digits);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(KEY_ID, id);
        json.put(KEY_NAME, name);
        json.put(KEY_MODE, mode.name());
        json.put(KEY_SEARCH_URL, searchUrl);
        json.put(KEY_REPORT_URL, reportUrl);
        json.put(KEY_PATTERN, pattern);
        json.put(KEY_AUTH, auth.name());
        json.put(KEY_USERNAME, username);
        json.put(KEY_API, api.name());
        json.put(KEY_API_URL, apiUrl);
        json.put(KEY_ENABLED, enabled);

        return json;
    }

    public static Provider fromJson(JSONObject json) {
        Provider provider = new Provider(json.optString(KEY_ID, null));

        provider.setName(json.optString(KEY_NAME, null));
        provider.setMode(parse(Mode.class, json.optString(KEY_MODE), Mode.URLS));

        // what one version wrote as "url" is what this one calls the search address
        String searchUrl = json.optString(KEY_SEARCH_URL, null);
        provider.setSearchUrl(!TextUtils.isEmpty(searchUrl)
                ? searchUrl : json.optString(KEY_URL, null));

        provider.setReportUrl(json.optString(KEY_REPORT_URL, null));
        provider.setPattern(json.optString(KEY_PATTERN, null));
        provider.setAuth(parse(Auth.class, json.optString(KEY_AUTH), Auth.NONE));
        provider.setUsername(json.optString(KEY_USERNAME, null));
        provider.setApi(parse(Api.class, json.optString(KEY_API), Api.CUSTOM));
        provider.setApiUrl(json.optString(KEY_API_URL, null));
        provider.setEnabled(json.optBoolean(KEY_ENABLED, true));

        return provider;
    }

    /** A value a later version wrote, or nonsense, reads as the one that is always safe. */
    private static <T extends Enum<T>> T parse(Class<T> type, String value, T defaultValue) {
        if (TextUtils.isEmpty(value)) return defaultValue;

        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

}
