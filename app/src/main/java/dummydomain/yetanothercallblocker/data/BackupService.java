package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.db.BlacklistDao;
import dummydomain.yetanothercallblocker.data.db.BlacklistItem;

/**
 * The settings and both lists in one file, to carry to another phone or keep as a backup.
 *
 * <p>The PhoneBlock token is never part of it. It is the password to an account rather than a
 * setting, a backup tends to end up in a cloud or a chat, and typing it again on the other
 * phone costs one visit to the account page - so the file is worth less to whoever finds it,
 * at a price the user pays once.
 *
 * <p>What is left out besides the token is what only describes this phone: when its database
 * was last downloaded, what the app did about the last calls, whether blocking is paused right
 * now. Those would say nothing useful on the phone the file is read on.
 */
public class BackupService {

    /** What the file says it is, so that another kind of file is not read as a backup. */
    public static final String FORMAT = "yet-another-callblocker-backup";

    /** The version of the format, in case a later one has to read this one. */
    public static final int VERSION = 1;

    /** What the app puts in the file, and what it takes out of one. */
    public static class Result {
        public boolean ok;
        public int settings;
        public int blacklistItems;
        public int whitelistItems;
    }

    private static final String KEY_FORMAT = "format";
    private static final String KEY_VERSION = "version";
    private static final String KEY_CREATED = "created";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_BLACKLIST = "blacklist";
    private static final String KEY_WHITELIST = "whitelist";

    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";

    private static final String KEY_NAME = "name";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_CREATION_TIME = "creationTime";
    private static final String KEY_NUMBER_OF_CALLS = "numberOfCalls";
    private static final String KEY_LAST_CALL_TIME = "lastCallTime";

    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_STRING_SET = "stringSet";

    /**
     * The settings that are not written out, and are ignored when they are found in a file
     * anyway: the token, and everything that is about this phone rather than about how the
     * user wants the app to behave.
     */
    private static final Set<String> EXCLUDED_SETTINGS = new HashSet<>(Arrays.asList(
            Settings.PREF_PHONE_BLOCK_TOKEN,
            Settings.PREF_PHONE_BLOCK_TOKEN_VALID,
            Settings.PREF_PHONE_BLOCK_LAST_TOKEN_CHECK_TIME,
            Settings.PREF_PHONE_BLOCK_TOKEN_PROBLEM_NOTIFIED,
            Settings.PREF_WHITELIST, // a list of its own in the file
            Settings.PREF_CALL_DECISIONS,
            Settings.PREF_BLACKLIST_IS_NOT_EMPTY,
            Settings.PREF_BLOCKING_PAUSED_UNTIL,
            Settings.PREF_AUTO_UPDATE_SET_UP,
            Settings.PREF_LAST_UPDATE_TIME,
            Settings.PREF_LAST_UPDATE_CHECK_TIME,
            Settings.PREF_PHONE_BLOCK_LAST_UPDATE_TIME,
            Settings.PREF_PHONE_BLOCK_LAST_FULL_UPDATE_TIME,
            Settings.PREF_PHONE_BLOCK_NEXT_UPDATE_TIME,
            Settings.PREF_PHONE_BLOCK_PERSONAL_NEXT_UPDATE_TIME,
            Settings.PREF_DB_FILTERED,
            Settings.PREF_DB_FILTERING_PREFIXES_PREFILLED));

    /** How much of a file the app is willing to read as a backup. */
    private static final int MAX_SIZE = 8 * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

    /** The backup as it is written to a file. */
    public String write(Settings settings, BlacklistDao blacklistDao) throws JSONException {
        JSONObject backup = new JSONObject();

        backup.put(KEY_FORMAT, FORMAT);
        backup.put(KEY_VERSION, VERSION);
        backup.put(KEY_CREATED, new Date().getTime());
        backup.put(KEY_SETTINGS, writeSettings(settings));
        backup.put(KEY_BLACKLIST, writeBlacklist(blacklistDao));
        backup.put(KEY_WHITELIST, writeWhitelist(settings));

        return backup.toString(2);
    }

    private JSONObject writeSettings(Settings settings) throws JSONException {
        JSONObject json = new JSONObject();

        for (Map.Entry<String, ?> entry : settings.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isExcluded(key) || value == null) continue;

            String type;
            Object written = value;

            if (value instanceof Boolean) {
                type = TYPE_BOOLEAN;
            } else if (value instanceof Integer) {
                type = TYPE_INT;
            } else if (value instanceof Long) {
                type = TYPE_LONG;
            } else if (value instanceof Float) {
                type = TYPE_FLOAT;
            } else if (value instanceof String) {
                type = TYPE_STRING;
            } else if (value instanceof Set) {
                type = TYPE_STRING_SET;
                written = new JSONArray((Set<?>) value);
            } else {
                LOG.debug("writeSettings() {} is of a kind that isn't written out", key);
                continue;
            }

            JSONObject setting = new JSONObject();
            setting.put(KEY_TYPE, type);
            setting.put(KEY_VALUE, written);

            json.put(key, setting);
        }

        return json;
    }

    private JSONArray writeBlacklist(BlacklistDao blacklistDao) throws JSONException {
        JSONArray array = new JSONArray();

        for (BlacklistItem item : blacklistDao.loadAll()) {
            JSONObject json = new JSONObject();

            json.put(KEY_NAME, item.getName());
            // the pattern is written the way the user writes it, not the way SQL matches it
            json.put(KEY_PATTERN, BlacklistUtils.patternToHumanReadable(item.getPattern()));

            if (item.getCreationDate() != null) {
                json.put(KEY_CREATION_TIME, item.getCreationDate().getTime());
            }
            if (item.getNumberOfCalls() != 0) {
                json.put(KEY_NUMBER_OF_CALLS, item.getNumberOfCalls());
            }
            if (item.getLastCallDate() != null) {
                json.put(KEY_LAST_CALL_TIME, item.getLastCallDate().getTime());
            }

            array.put(json);
        }

        return array;
    }

    private JSONArray writeWhitelist(Settings settings) throws JSONException {
        JSONArray array = new JSONArray();

        for (WhitelistItem item : Whitelist.parse(settings.getWhitelist())) {
            JSONObject json = new JSONObject();

            json.put(KEY_NAME, item.getName());
            json.put(KEY_PATTERN, item.getPattern());

            array.put(json);
        }

        return array;
    }

    /**
     * Reads a backup and puts what is in it back: the settings replace the ones on this phone,
     * the list entries are added to the lists, and an entry that is already there is left alone.
     *
     * @return what was read, or a result that is not ok when the file isn't a backup
     */
    public Result read(InputStream inputStream, Settings settings, BlacklistDao blacklistDao,
                       BlacklistService blacklistService, WhitelistService whitelistService) {
        Result result = new Result();

        JSONObject backup;
        try {
            backup = new JSONObject(readFully(inputStream));
        } catch (IOException | JSONException e) {
            LOG.warn("read() couldn't read the file", e);
            return result;
        }

        if (!FORMAT.equals(backup.optString(KEY_FORMAT))) {
            LOG.info("read() not a backup of this app");
            return result;
        }

        int version = backup.optInt(KEY_VERSION, 0);
        if (version < 1 || version > VERSION) {
            LOG.info("read() version {} can't be read", version);
            return result;
        }

        result.settings = readSettings(backup.optJSONObject(KEY_SETTINGS), settings);
        result.blacklistItems = readBlacklist(backup.optJSONArray(KEY_BLACKLIST),
                blacklistDao, blacklistService);
        result.whitelistItems = readWhitelist(backup.optJSONArray(KEY_WHITELIST),
                whitelistService);

        result.ok = true;

        LOG.info("read() {} settings, {} blacklist entries, {} whitelist entries",
                result.settings, result.blacklistItems, result.whitelistItems);

        return result;
    }

    private int readSettings(JSONObject json, Settings settings) {
        if (json == null) return 0;

        int count = 0;

        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();

            // a token in a file is not read back, whoever put it there
            if (isExcluded(key)) continue;

            JSONObject setting = json.optJSONObject(key);
            if (setting == null) continue;

            String type = setting.optString(KEY_TYPE);

            try {
                switch (type) {
                    case TYPE_BOOLEAN:
                        settings.setBoolean(key, setting.getBoolean(KEY_VALUE));
                        break;

                    case TYPE_INT:
                        settings.setInt(key, setting.getInt(KEY_VALUE));
                        break;

                    case TYPE_LONG:
                        settings.setLong(key, setting.getLong(KEY_VALUE));
                        break;

                    case TYPE_FLOAT:
                        settings.setFloat(key, (float) setting.getDouble(KEY_VALUE));
                        break;

                    case TYPE_STRING:
                        settings.setString(key, setting.getString(KEY_VALUE));
                        break;

                    case TYPE_STRING_SET:
                        settings.setStringSet(key, toStringSet(setting.getJSONArray(KEY_VALUE)));
                        break;

                    default:
                        LOG.debug("readSettings() {} is of an unknown kind", key);
                        continue;
                }
            } catch (JSONException e) {
                LOG.debug("readSettings() couldn't read {}", key, e);
                continue;
            }

            count++;
        }

        return count;
    }

    private int readBlacklist(JSONArray array, BlacklistDao blacklistDao,
                              BlacklistService blacklistService) {
        if (array == null) return 0;

        int count = 0;

        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;

            String pattern = BlacklistUtils.cleanPattern(BlacklistUtils
                    .patternFromHumanReadable(json.optString(KEY_PATTERN)));

            if (!BlacklistUtils.isValidPattern(pattern)) {
                LOG.debug("readBlacklist() skipping an entry that isn't a pattern");
                continue;
            }

            if (blacklistDao.findByPattern(pattern) != null) continue; // already on the list

            BlacklistItem item = new BlacklistItem();
            item.setPattern(pattern);
            item.setName(json.optString(KEY_NAME));
            item.setNumberOfCalls(json.optInt(KEY_NUMBER_OF_CALLS));

            long creationTime = json.optLong(KEY_CREATION_TIME);
            item.setCreationDate(new Date(creationTime > 0 ? creationTime : new Date().getTime()));

            long lastCallTime = json.optLong(KEY_LAST_CALL_TIME);
            if (lastCallTime > 0) item.setLastCallDate(new Date(lastCallTime));

            blacklistService.insert(item);
            count++;
        }

        return count;
    }

    private int readWhitelist(JSONArray array, WhitelistService whitelistService) {
        if (array == null) return 0;

        int count = 0;

        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;

            String pattern = json.optString(KEY_PATTERN);
            if (TextUtils.isEmpty(pattern)) continue;

            // add() leaves an entry that is already there alone, and says so
            if (whitelistService.add(new WhitelistItem(json.optString(KEY_NAME), pattern))) {
                count++;
            }
        }

        return count;
    }

    private static boolean isExcluded(String key) {
        // keys the app keeps for itself start with two underscores
        return TextUtils.isEmpty(key) || key.startsWith("__") || EXCLUDED_SETTINGS.contains(key);
    }

    private static Set<String> toStringSet(JSONArray array) {
        Set<String> values = new HashSet<>(array.length());

        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (value != null) values.add(value);
        }

        return values;
    }

    private static String readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            if (out.size() + read > MAX_SIZE) throw new IOException("the file is too big");

            out.write(buffer, 0, read);
        }

        return out.toString("UTF-8");
    }

}
