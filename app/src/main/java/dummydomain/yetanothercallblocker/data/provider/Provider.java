package dummydomain.yetanothercallblocker.data.provider;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * A place on the web that knows something about a number.
 *
 * <p>The app doesn't ask any of them by itself - that would tell them who is calling whom -
 * so a provider is an address with a place for the number in it, offered in the dialog about
 * a call and opened when the user decides to look. PhoneBlock, tellows and a web search were
 * three fixed rows there; they are three of these now, and there can be others.
 *
 * <p>The token, where one is needed, is kept apart from the list, the way the passwords of
 * the sources are: the list can go into a backup, the token only when the user says so.
 */
public class Provider {

    /** The account the app can also report numbers to; its page about a number. */
    public static final String ID_PHONE_BLOCK = "phoneblock";

    public static final String ID_TELLOWS = "tellows";

    /** Looking the number up on the web, which is where it has always gone. */
    public static final String ID_WEB_SEARCH = "websearch";

    /** Where the number goes in the address. */
    public static final String PLACEHOLDER_NUMBER = "{number}";

    /** Where the token goes in the address, for a provider that wants one there. */
    public static final String PLACEHOLDER_TOKEN = "{token}";

    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_URL = "url";
    private static final String KEY_ENABLED = "enabled";

    /** Stays the same for the life of the provider: its token hangs off it. */
    private final String id;

    private String name;
    private String url;
    private boolean enabled = true;

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

    /** The address of the page about a number, with {@link #PLACEHOLDER_NUMBER} in it. */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** Whether the dialog about a call offers it. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** One of the three the app knows by itself, which are kept rather than deleted. */
    public boolean isBuiltIn() {
        return ID_PHONE_BLOCK.equals(id) || ID_TELLOWS.equals(id) || ID_WEB_SEARCH.equals(id);
    }

    /** Whether the address is built by the app rather than written out here. */
    public boolean hasOwnAddress() {
        return ID_PHONE_BLOCK.equals(id) && TextUtils.isEmpty(url);
    }

    /** Whether there is enough here to open anything. */
    public boolean isValid() {
        return !TextUtils.isEmpty(url) || hasOwnAddress();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(KEY_ID, id);
        json.put(KEY_NAME, name);
        json.put(KEY_URL, url);
        json.put(KEY_ENABLED, enabled);

        return json;
    }

    public static Provider fromJson(JSONObject json) {
        Provider provider = new Provider(json.optString(KEY_ID, null));

        provider.setName(json.optString(KEY_NAME, null));
        provider.setUrl(json.optString(KEY_URL, null));
        provider.setEnabled(json.optBoolean(KEY_ENABLED, true));

        return provider;
    }

}
