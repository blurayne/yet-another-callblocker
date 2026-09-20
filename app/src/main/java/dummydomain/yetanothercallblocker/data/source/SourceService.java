package dummydomain.yetanothercallblocker.data.source;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.Settings;

/**
 * The sources the app knows, and the credentials that go with them.
 *
 * <p>The two are kept apart on purpose. The list says where the numbers come from and is
 * worth carrying to another phone; the passwords and tokens are not, and stay out of the
 * backup the way the PhoneBlock token does.
 */
public class SourceService {

    private static final Logger LOG = LoggerFactory.getLogger(SourceService.class);

    private final Settings settings;

    public SourceService(Settings settings) {
        this.settings = settings;
    }

    /** The sources, in the order the user put them in. */
    public List<NumberSource> getSources() {
        if (!settings.getSourcesMigrated()) migrate();

        return parse(settings.getNumberSources());
    }

    /** The sources that are asked for numbers, in order. */
    public List<NumberSource> getEnabledSources(NumberSource.Type type) {
        List<NumberSource> sources = new ArrayList<>();

        for (NumberSource source : getSources()) {
            if (source.isEnabled() && source.getType() == type && source.isValid()) {
                sources.add(source);
            }
        }

        return sources;
    }

    /** The database the app downloads: the first one that is switched on. */
    public NumberSource getActiveDatabaseSource() {
        List<NumberSource> sources = getEnabledSources(NumberSource.Type.DATABASE);

        return !sources.isEmpty() ? sources.get(0) : null;
    }

    /** Where the community database comes from, whatever the list has to say about it. */
    public String getDatabaseUrl() {
        NumberSource source = getActiveDatabaseSource();

        return source != null && !TextUtils.isEmpty(source.getUrl())
                ? source.getUrl() : settings.getDatabaseDownloadUrl();
    }

    public NumberSource findById(String id) {
        if (TextUtils.isEmpty(id)) return null;

        for (NumberSource source : getSources()) {
            if (source.getId().equals(id)) return source;
        }

        return null;
    }

    /** Adds a source, or replaces the one with the same id. */
    public void save(NumberSource source) {
        List<NumberSource> sources = getSources();

        boolean replaced = false;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getId().equals(source.getId())) {
                sources.set(i, source);
                replaced = true;
                break;
            }
        }

        if (!replaced) sources.add(source);

        save(sources);

        applyPhoneBlock(source);
    }

    /**
     * A PhoneBlock source is the account the app already has, shown in the list like the
     * others: what the user changes here is written where PhoneBlock reads it, so that there
     * is one answer to where the list comes from and not two.
     */
    private void applyPhoneBlock(NumberSource source) {
        if (source.getType() != NumberSource.Type.PHONE_BLOCK) return;

        if (!TextUtils.isEmpty(source.getUrl())) settings.setPhoneBlockUrl(source.getUrl());

        settings.setUsePhoneBlock(source.isEnabled());
    }

    public void remove(String id) {
        List<NumberSource> sources = getSources();

        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getId().equals(id)) {
                sources.remove(i);
                break;
            }
        }

        save(sources);
        setSecret(id, null);
    }

    public void save(List<NumberSource> sources) {
        JSONArray array = new JSONArray();

        for (NumberSource source : sources) {
            try {
                array.put(source.toJson());
            } catch (Exception e) {
                LOG.warn("save() couldn't write {}", source, e);
            }
        }

        settings.setNumberSources(array.length() != 0 ? array.toString() : "");
    }

    /** The password or token of a source, kept where a backup doesn't reach. */
    public String getSecret(String id) {
        if (TextUtils.isEmpty(id)) return null;

        NumberSource source = findById(id);
        if (source != null && source.getType() == NumberSource.Type.PHONE_BLOCK) {
            return settings.getPhoneBlockToken(); // the account's token, wherever it was typed
        }

        try {
            JSONObject secrets = new JSONObject(orEmptyObject(settings.getSourceSecrets()));
            return secrets.optString(id, null);
        } catch (Exception e) {
            LOG.warn("getSecret()", e);
            return null;
        }
    }

    public void setSecret(String id, String secret) {
        if (TextUtils.isEmpty(id)) return;

        NumberSource source = findById(id);
        if (source != null && source.getType() == NumberSource.Type.PHONE_BLOCK) {
            settings.setPhoneBlockToken(secret);
            settings.resetPhoneBlockTokenState();
            return;
        }

        try {
            JSONObject secrets = new JSONObject(orEmptyObject(settings.getSourceSecrets()));

            if (TextUtils.isEmpty(secret)) {
                secrets.remove(id);
            } else {
                secrets.put(id, secret);
            }

            settings.setSourceSecrets(secrets.length() != 0 ? secrets.toString() : "");
        } catch (Exception e) {
            LOG.warn("setSecret()", e);
        }
    }

    private static String orEmptyObject(String value) {
        return !TextUtils.isEmpty(value) ? value : "{}";
    }

    private static List<NumberSource> parse(String value) {
        List<NumberSource> sources = new ArrayList<>();

        if (TextUtils.isEmpty(value)) return sources;

        try {
            JSONArray array = new JSONArray(value);

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) sources.add(NumberSource.fromJson(json));
            }
        } catch (Exception e) {
            LOG.error("parse() couldn't read the sources", e);
        }

        return sources;
    }

    /**
     * Turns what an older version kept into the list this one has - once.
     *
     * <p>An update finds the address of the database and the PhoneBlock account where they
     * have always been, and makes a source of each: the same two places the app fetched from
     * before, with the addresses the user had chosen and PhoneBlock switched on or off the
     * way they left it. Its token doesn't have to be carried anywhere - a PhoneBlock source
     * reads it where the account screen keeps it, so it is the same secret, not a copy.
     *
     * <p>It happens once and is written down as having happened, so that a user who deletes
     * every source gets an empty list rather than these two back.
     */
    private void migrate() {
        List<NumberSource> sources = parse(settings.getNumberSources());

        if (sources.isEmpty()) {
            NumberSource database = new NumberSource();
            database.setType(NumberSource.Type.DATABASE);
            database.setUrl(settings.getDatabaseDownloadUrl());
            database.setUpdates(NumberSource.Updates.DAILY);
            sources.add(database);

            NumberSource phoneBlock = new NumberSource();
            phoneBlock.setType(NumberSource.Type.PHONE_BLOCK);
            phoneBlock.setUrl(settings.getPhoneBlockUrl());
            phoneBlock.setAuth(NumberSource.Auth.BEARER);
            phoneBlock.setUpdates(NumberSource.Updates.DAILY);
            phoneBlock.setEnabled(settings.getUsePhoneBlock());
            sources.add(phoneBlock);

            save(sources);

            LOG.info("migrate() made sources of the database and the PhoneBlock account");
        }

        settings.setSourcesMigrated(true);
    }

}
