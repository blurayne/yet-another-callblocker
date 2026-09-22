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
        /** Only when asked: by hand, or by a build that reads the sources again. */
        MANUAL,
        /**
         * Once, and then never again by itself.
         *
         * <p>For a list that is what it is - somebody's collection, a file that was put
         * somewhere for this phone to pick up. It is fetched while there is nothing of it
         * here, and after that it is left alone: not by the schedule, and not by a build
         * either. Asking for it by hand still fetches it.
         */
        ONCE,
        DAILY, WEEKLY, MONTHLY;

        /** How long a source of this kind may go unasked, or 0 when only the user asks. */
        public long getInterval() {
            switch (this) {
                case DAILY: return 24L * 60 * 60 * 1000;
                case WEEKLY: return 7L * 24 * 60 * 60 * 1000;
                case MONTHLY: return 30L * 24 * 60 * 60 * 1000;
                default: return 0;
            }
        }

        /** Whether this one comes round by itself at all. */
        public boolean isScheduled() {
            return getInterval() != 0;
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
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final String KEY_LAST_RESULT = "lastResult";
    private static final String KEY_VERSION = "version";
    private static final String KEY_FETCHED_URL = "fetchedUrl";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_DROP_FILES = "dropFiles";
    private static final String KEY_CONTENT = "content";

    /** Stays the same for the life of the source: its files and its password hang off it. */
    private final String id;

    private String name;
    private Type type = Type.DATABASE;
    private String url;
    private Auth auth = Auth.NONE;
    private String username;
    private Updates updates = Updates.DAILY;
    private boolean enabled = true;

    /**
     * Whether what this source handed over is thrown away once it is in the table.
     *
     * <p>Off, because the files are what a build reads: keeping them means the next build -
     * after a changed filter, a new source, a different order - reads them again instead of
     * downloading them again. Whoever would rather have the room back than the download
     * spared says so here, per source, because it is the source's own data.
     *
     * <p>The numbers themselves are in the table either way. What goes is only the copy they
     * were read out of.
     */
    private boolean dropFilesAfterBuild;

    private long lastUpdate;
    private long lastCheck;
    private String lastResult;

    /** What the source said its data was, the last time it handed any over. */
    private int version;

    /**
     * The address the database that is lying on the phone actually came from.
     *
     * <p>A source that is pointed somewhere else has to be fetched again, whatever its
     * schedule says: what is on the phone came from the old address and is not what the
     * source stands for any more. Without this a changed address quietly changed nothing.
     */
    private String fetchedUrl;

    /** How many numbers of the built database came from here. */
    private long entries;

    /**
     * What this source hands over, once anything has looked.
     *
     * <p>Not something the user picks: a URL is a URL, and what is behind it is a question
     * the bytes answer. The test button asks it without downloading the whole thing, and a
     * fetch answers it again from what actually arrived - so this is what was last seen
     * there rather than a promise about what will be.
     */
    private ArchiveUtils.Content content = ArchiveUtils.Content.UNKNOWN;

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

    /** Whether the files this source handed over are thrown away once they are in the table. */
    public boolean getDropFilesAfterBuild() {
        return dropFilesAfterBuild;
    }

    public void setDropFilesAfterBuild(boolean drop) {
        this.dropFilesAfterBuild = drop;
    }

    /** What was last seen at this address: slice files, a SQLite database, or nothing yet. */
    public ArchiveUtils.Content getContent() {
        return content != null ? content : ArchiveUtils.Content.UNKNOWN;
    }

    public void setContent(ArchiveUtils.Content content) {
        this.content = content != null ? content : ArchiveUtils.Content.UNKNOWN;
    }

    /**
     * Forgets everything that describes what was fetched, leaving what the user typed.
     *
     * <p>For a fetch that failed and for an address that changed: the count, the version and
     * the kind of thing behind it all describe data that is either gone or was never this
     * source's. A row that goes on saying "400.000 numbers, version 1800" about an address
     * that answers 404 is worse than one that says nothing.
     */
    public void forgetFetched() {
        entries = 0;
        version = 0;
        fetchedUrl = null;
        content = ArchiveUtils.Content.UNKNOWN;
        lastUpdate = 0;
    }

    /**
     * The same source again under a new id: the address, the login, the schedule, everything
     * the user set - and nothing of what a fetch found out, because this one has fetched
     * nothing yet. The secret is kept apart from the source and is copied by whoever asks
     * for the copy.
     */
    public NumberSource copy() {
        try {
            JSONObject json = toJson();
            json.remove(KEY_ID);

            NumberSource copy = fromJson(json);

            copy.forgetFetched();
            copy.lastCheck = 0;
            copy.lastResult = null;

            return copy;
        } catch (JSONException e) {
            throw new IllegalStateException(e); // our own fields, which always serialize
        }
    }

    /** Whether this source hands over files at all, which is what there would be to drop. */
    public boolean hasFiles() {
        return type == Type.DATABASE;
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
    public String getFetchedUrl() {
        return fetchedUrl;
    }

    public void setFetchedUrl(String fetchedUrl) {
        this.fetchedUrl = fetchedUrl;
    }

    /** Whether what is on the phone came from somewhere else than this source now points. */
    public boolean hasMoved() {
        return !TextUtils.equals(url, fetchedUrl);
    }

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
     * Whether the source's own schedule says it is time.
     *
     * <p>Only about the schedule: whether there is anything of it on the phone is a question
     * about the phone and is asked there. A source that comes round by itself has gone long
     * enough unasked; one that doesn't, never has.
     */
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
        json.put(KEY_LAST_CHECK, lastCheck);
        json.put(KEY_LAST_RESULT, lastResult);
        json.put(KEY_VERSION, version);
        json.put(KEY_FETCHED_URL, fetchedUrl);
        json.put(KEY_ENTRIES, entries);
        json.put(KEY_DROP_FILES, dropFilesAfterBuild);
        json.put(KEY_CONTENT, getContent().name());

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
        source.lastCheck = json.optLong(KEY_LAST_CHECK);
        source.lastResult = json.optString(KEY_LAST_RESULT, null);
        source.version = json.optInt(KEY_VERSION);
        source.fetchedUrl = json.optString(KEY_FETCHED_URL, null);
        source.entries = json.optLong(KEY_ENTRIES);
        source.dropFilesAfterBuild = json.optBoolean(KEY_DROP_FILES, false);
        source.content = parse(ArchiveUtils.Content.class, json.optString(KEY_CONTENT),
                ArchiveUtils.Content.UNKNOWN);

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
