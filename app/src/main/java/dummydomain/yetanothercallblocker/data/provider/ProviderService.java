package dummydomain.yetanothercallblocker.data.provider;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.BlacklistUtils;
import dummydomain.yetanothercallblocker.data.NumberUtils;

/**
 * The providers the app knows, in the order the dialog about a call offers them.
 *
 * <p>Three of them are there from the start, because they were three fixed rows before:
 * PhoneBlock, tellows and a web search. They can be switched off, renamed, pointed somewhere
 * else, and others can be added next to them - what a row does is an address with the number
 * put into it, so there is nothing special about the three.
 *
 * <p>Tokens live apart from the list, as the passwords of the sources do. PhoneBlock's is the
 * one the account screen already keeps: read and written where it has always been, so there
 * is one token rather than two that have to agree.
 */
public class ProviderService {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderService.class);

    private static final String TELLOWS_URL
            = "https://www.tellows.de/num/" + Provider.PLACEHOLDER_NUMBER;

    private static final String WEB_SEARCH_URL
            = "https://www.google.com/search?q=" + Provider.PLACEHOLDER_NUMBER;

    private static final String CLEVER_DIALER_URL
            = "https://www.cleverdialer.de/telefonnummer/" + Provider.PLACEHOLDER_NATIONAL;

    private static final String DASOERTLICHE_URL
            = "https://www.dasoertliche.de/?form_name=search_inv&ph="
            + Provider.PLACEHOLDER_NATIONAL;

    /**
     * The providers the app offers by itself, and the version it learned each of them in.
     *
     * <p>A version is what keeps a later one from bringing back what the user has thrown
     * away: everything up to the version that was last put in is left alone, and only what
     * came after it is added.
     */
    private static final Defaults[] DEFAULTS = {
            new Defaults(Provider.ID_PHONE_BLOCK, null, 1),
            new Defaults(Provider.ID_TELLOWS, TELLOWS_URL, 1),
            new Defaults(Provider.ID_WEB_SEARCH, WEB_SEARCH_URL, 1),
            new Defaults(Provider.ID_CLEVER_DIALER, CLEVER_DIALER_URL, 2),
            new Defaults(Provider.ID_DASOERTLICHE, DASOERTLICHE_URL, 2),
    };

    /** The highest version in {@link #DEFAULTS}. */
    private static final int SEED_VERSION = 2;

    private static class Defaults {

        final String id;
        final String url;
        final int version;

        Defaults(String id, String url, int version) {
            this.id = id;
            this.url = url;
            this.version = version;
        }

    }

    private final Settings settings;

    public ProviderService(Settings settings) {
        this.settings = settings;
    }

    /** The providers, in the order the user put them in. */
    public List<Provider> getProviders() {
        if (settings.getProvidersSeededVersion() < SEED_VERSION) seed();

        return parse(settings.getProviders());
    }

    /** The ones the dialog about a call offers, in order. */
    public List<Provider> getEnabledProviders() {
        List<Provider> providers = new ArrayList<>();

        for (Provider provider : getProviders()) {
            if (provider.isEnabled() && provider.isValid()) providers.add(provider);
        }

        return providers;
    }

    public Provider findById(String id) {
        if (TextUtils.isEmpty(id)) return null;

        for (Provider provider : getProviders()) {
            if (provider.getId().equals(id)) return provider;
        }

        return null;
    }

    /** Adds a provider, or replaces the one with the same id. */
    public void save(Provider provider) {
        List<Provider> providers = getProviders();

        boolean replaced = false;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getId().equals(provider.getId())) {
                providers.set(i, provider);
                replaced = true;
                break;
            }
        }

        if (!replaced) providers.add(provider);

        save(providers);
    }

    public void save(List<Provider> providers) {
        JSONArray array = new JSONArray();

        for (Provider provider : providers) {
            try {
                array.put(provider.toJson());
            } catch (Exception e) {
                LOG.warn("save() couldn't write {}", provider.getId(), e);
            }
        }

        settings.setProviders(array.length() != 0 ? array.toString() : "");
    }

    public void remove(String id) {
        List<Provider> providers = getProviders();

        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getId().equals(id)) {
                providers.remove(i);
                break;
            }
        }

        save(providers);
        setSecret(id, null);
    }

    /** The token of a provider, kept where a backup only reaches when asked. */
    public String getSecret(String id) {
        if (TextUtils.isEmpty(id)) return null;

        if (Provider.ID_PHONE_BLOCK.equals(id)) {
            return settings.getPhoneBlockToken(); // the account's token, wherever it was typed
        }

        try {
            JSONObject secrets = new JSONObject(orEmptyObject(settings.getProviderSecrets()));
            return secrets.optString(id, null);
        } catch (Exception e) {
            LOG.warn("getSecret()", e);
            return null;
        }
    }

    public void setSecret(String id, String secret) {
        if (TextUtils.isEmpty(id)) return;

        if (Provider.ID_PHONE_BLOCK.equals(id)) {
            settings.setPhoneBlockToken(secret);
            settings.resetPhoneBlockTokenState();
            return;
        }

        try {
            JSONObject secrets = new JSONObject(orEmptyObject(settings.getProviderSecrets()));

            if (TextUtils.isEmpty(secret)) {
                secrets.remove(id);
            } else {
                secrets.put(id, secret);
            }

            settings.setProviderSecrets(secrets.length() != 0 ? secrets.toString() : "");
        } catch (Exception e) {
            LOG.warn("setSecret()", e);
        }
    }

    /**
     * The address of the page about a number, or null when there is none to open.
     *
     * <p>The number is put in the form the address asks for - with a plus, with 00, as digits,
     * or the way it is written at home - and the token only where the address asks for it,
     * which is how a provider whose API wants a key in the query can be written down without
     * the app knowing anything about that API.
     */
    public String getUrl(Provider provider, String number) {
        if (provider == null || TextUtils.isEmpty(number)) return null;

        String url = provider.getUrl();
        if (TextUtils.isEmpty(url)) return null;

        String cleanNumber = BlacklistUtils.cleanNumber(number);

        String international = null, national = null;

        for (String variant : NumberUtils.getVariants(number,
                NumberUtils.normalizeNumber(number, settings.getCountryCode()),
                settings.getCountryCode())) {
            if (variant.startsWith("+")) {
                if (international == null) international = variant;
            } else if (variant.startsWith("00")) {
                if (international == null) international = "+" + variant.substring(2);
            } else if (variant.startsWith("0") && national == null) {
                national = variant;
            }
        }

        // a number that doesn't normalize is used as it came in, whatever form was asked for
        if (international == null) international = cleanNumber;
        if (national == null) national = cleanNumber;

        String digits = international.startsWith("+")
                ? international.substring(1) : international;

        url = url.replace(Provider.PLACEHOLDER_NUMBER_00,
                        encode(international.startsWith("+")
                                ? "00" + digits : international))
                .replace(Provider.PLACEHOLDER_DIGITS, encode(digits))
                .replace(Provider.PLACEHOLDER_NATIONAL, encode(national))
                .replace(Provider.PLACEHOLDER_NUMBER, encode(international));

        if (url.contains(Provider.PLACEHOLDER_TOKEN)) {
            String secret = getSecret(provider.getId());
            url = url.replace(Provider.PLACEHOLDER_TOKEN, encode(secret != null ? secret : ""));
        }

        return url;
    }

    private static String encode(String value) {
        return Uri.encode(value);
    }

    /**
     * Puts the ones the app knows into the list - each of them once, ever.
     *
     * <p>Which ones have been offered is written down as a number, so that a later version
     * can add what it has learned since without bringing back what the user threw away.
     */
    private void seed() {
        List<Provider> providers = parse(settings.getProviders());

        int seeded = settings.getProvidersSeededVersion();

        // the version that only knew whether it had happened at all offered the first three
        if (seeded == 0 && settings.getProvidersSeeded()) seeded = 1;

        boolean added = false;

        for (Defaults defaults : DEFAULTS) {
            if (defaults.version <= seeded) continue; // offered once already

            boolean present = false;
            for (Provider provider : providers) {
                if (provider.getId().equals(defaults.id)) {
                    present = true;
                    break;
                }
            }

            if (present) continue;

            providers.add(builtIn(defaults.id, defaults.url));
            added = true;

            LOG.info("seed() added {}", defaults.id);
        }

        if (added) save(providers);

        settings.setProvidersSeeded(true);
        settings.setProvidersSeededVersion(SEED_VERSION);
    }

    private static Provider builtIn(String id, String url) {
        Provider provider = new Provider(id);
        provider.setUrl(url);

        return provider;
    }

    private static String orEmptyObject(String value) {
        return !TextUtils.isEmpty(value) ? value : "{}";
    }

    private static List<Provider> parse(String value) {
        List<Provider> providers = new ArrayList<>();

        if (TextUtils.isEmpty(value)) return providers;

        try {
            JSONArray array = new JSONArray(value);

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) providers.add(Provider.fromJson(json));
            }
        } catch (Exception e) {
            LOG.error("parse() couldn't read the providers", e);
        }

        return providers;
    }

}
