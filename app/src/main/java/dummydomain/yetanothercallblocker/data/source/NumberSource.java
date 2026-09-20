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

    /**
     * What a database source carries.
     *
     * <p>The whole database is one thing and changes to it are another: the first is fetched
     * once and is worth fetching again only now and then, the second is only ever the
     * difference and is asked for as often as the user wants to hear about new numbers.
     */
    public enum Role {
        /** The database itself, an archive of everything the source knows. */
        BASE,
        /** Numbers to add to what is already there, and numbers to take out again. */
        UPDATES
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
    private static final String KEY_ROLE = "role";
    private static final String KEY_LAST_UPDATE = "lastUpdate";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final String KEY_LAST_RESULT = "lastResult";
    private static final String KEY_VERSION = "version";
    private static final String KEY_ENTRIES = "entries";

    /** Stays the same for the life of the source: its files and its password hang off it. */
    private final String id;

    private String name;
    private Type type = Type.DATABASE;
    private String url;
    private Auth auth = Auth.NONE;
    private String username;
    private Updates updates = Updates.DAILY;
    private Role role = Role.BASE;
    private boolean enabled = true;

    private long lastUpdate;
    private long lastCheck;
    private String lastResult;

    /** What the source said its data was, the last time it handed any over. */
    private int version;

    /** How many numbers of the built database came from here. */
    private long entries;

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

    /** Whether this source carries the database itself or changes to it. */
    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role != null ? role : Role.BASE;
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

    /** When the source was last asked, whether or not it had anything to say. */
    public long getLastCheck() {
        return lastCheck;
    }

    public void setLastCheck(long lastCheck) {
        this.lastCheck = lastCheck;
    }

    /** The version of what it last handed over, as the data itself says. */
    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /** How much of the built database came from here. */
    public long getEntries() {
        return entries;
    }

    public void setEntries(long entries) {
        this.entries = entries;
    }

    /**
     * Whether the source is due to be asked again.
     *
     * <p>A database that carries the whole thing is fetched once and then only on the
     * schedule the user chose - tens of megabytes are not something to ask for daily by
     * accident. Changes to it are asked for whenever the schedule says.
     */
    public boolean isDue(long now) {
        if (!enabled) return false;

        if (role == Role.BASE && lastUpdate <= 0) return true; // there is nothing yet

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
        json.put(KEY_ROLE, role.name());
        json.put(KEY_ENABLED, enabled);
        json.put(KEY_LAST_UPDATE, lastUpdate);
        json.put(KEY_LAST_CHECK, lastCheck);
        json.put(KEY_LAST_RESULT, lastResult);
        json.put(KEY_VERSION, version);
        json.put(KEY_ENTRIES, entries);

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
        source.role = parse(Role.class, json.optString(KEY_ROLE), Role.BASE);
        source.enabled = json.optBoolean(KEY_ENABLED, true);
        source.lastUpdate = json.optLong(KEY_LAST_UPDATE);
        source.lastCheck = json.optLong(KEY_LAST_CHECK);
        source.lastResult = json.optString(KEY_LAST_RESULT, null);
        source.version = json.optInt(KEY_VERSION);
        source.entries = json.optLong(KEY_ENTRIES);

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
