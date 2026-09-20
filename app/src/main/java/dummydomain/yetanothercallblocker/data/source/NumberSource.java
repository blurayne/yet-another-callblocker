package dummydomain.yetanothercallblocker.data.source;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One place the app gets numbers from.
 *
 * <p>The password or token is not part of this: it lives apart from the list, so that the
 * list can be written into a backup and the credentials cannot. What is here is what the
 * user would tell a friend about the source - where it is, what it holds, how often to ask.
 */
public class NumberSource {

    /** What the source hands out, which decides what the app does with it. */
    public enum Type {
        /** The community database in the format the app has always read. */
        DATABASE,
        /** A PhoneBlock account and its list. */
        PHONE_BLOCK,
        /** An address book over CardDAV, whose numbers are blocked. */
        CARDDAV
    }

    /** How the app says who it is. */
    public enum Auth {
        NONE, BEARER, BASIC
    }

    /** How often the source is asked, by itself. */
    public enum Updates {
        MANUAL, DAILY, WEEKLY, MONTHLY;

        /** How long a source of this kind may go unasked, or 0 when only the user asks. */
        public long getInterval() {
            switch (this) {
                case DAILY: return 24L * 60 * 60 * 1000;
                case WEEKLY: return 7L * 24 * 60 * 60 * 1000;
                case MONTHLY: return 30L * 24 * 60 * 60 * 1000;
                default: return 0;
            }
        }
    }

    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_TYPE = "type";
    private static final String KEY_URL = "url";
    private static final String KEY_AUTH = "auth";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_UPDATES = "updates";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_UPDATE = "lastUpdate";
    private static final String KEY_LAST_RESULT = "lastResult";

    /** Stays the same for the life of the source: its files and its password hang off it. */
    private final String id;

    private String name;
    private Type type = Type.DATABASE;
    private String url;
    private Auth auth = Auth.NONE;
    private String username;
    private Updates updates = Updates.DAILY;
    private boolean enabled = true;

    private long lastUpdate;
    private String lastResult;

    public NumberSource() {
        this(UUID.randomUUID().toString());
    }

    public NumberSource(String id) {
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

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type != null ? type : Type.DATABASE;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    public Updates getUpdates() {
        return updates;
    }

    public void setUpdates(Updates updates) {
        this.updates = updates != null ? updates : Updates.MANUAL;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(long lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    /** What came of the last attempt, in the user's words, or null when it went fine. */
    public String getLastResult() {
        return lastResult;
    }

    public void setLastResult(String lastResult) {
        this.lastResult = lastResult;
    }

    /** Whether the source is due to be asked again. */
    public boolean isDue(long now) {
        if (!enabled) return false;

        long interval = updates.getInterval();
        if (interval == 0) return false; // only when the user says so

        return lastUpdate <= 0 || now - lastUpdate >= interval;
    }

    /** Where this source keeps its files, apart from every other source's. */
    public String getStoragePrefix() {
        return "source_" + id.replaceAll("[^a-zA-Z0-9_-]", "") + "/";
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(url);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(KEY_ID, id);
        json.put(KEY_NAME, name);
        json.put(KEY_TYPE, type.name());
        json.put(KEY_URL, url);
        json.put(KEY_AUTH, auth.name());
        json.put(KEY_USERNAME, username);
        json.put(KEY_UPDATES, updates.name());
        json.put(KEY_ENABLED, enabled);
        json.put(KEY_LAST_UPDATE, lastUpdate);
        json.put(KEY_LAST_RESULT, lastResult);

        return json;
    }

    public static NumberSource fromJson(JSONObject json) {
        NumberSource source = new NumberSource(json.optString(KEY_ID, null));

        source.name = json.optString(KEY_NAME, null);
        source.type = parse(Type.class, json.optString(KEY_TYPE), Type.DATABASE);
        source.url = json.optString(KEY_URL, null);
        source.auth = parse(Auth.class, json.optString(KEY_AUTH), Auth.NONE);
        source.username = json.optString(KEY_USERNAME, null);
        source.updates = parse(Updates.class, json.optString(KEY_UPDATES), Updates.DAILY);
        source.enabled = json.optBoolean(KEY_ENABLED, true);
        source.lastUpdate = json.optLong(KEY_LAST_UPDATE);
        source.lastResult = json.optString(KEY_LAST_RESULT, null);

        return source;
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

    @Override
    public String toString() {
        return "NumberSource{" + type + " " + (!TextUtils.isEmpty(name) ? name : url) + '}';
    }

}
