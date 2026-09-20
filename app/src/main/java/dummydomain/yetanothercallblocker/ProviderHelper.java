package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.Provider;
import dummydomain.yetanothercallblocker.data.provider.ProviderService;

/**
 * What a provider is called and where it sends the user - the parts that need words or the
 * PhoneBlock account, and so don't belong with the list itself.
 */
public class ProviderHelper {

    private ProviderHelper() {
    }

    /** What the provider is called: what the user named it, or what it is. */
    public static String getName(Context context, Provider provider) {
        if (provider == null) return "";

        if (!TextUtils.isEmpty(provider.getName())) return provider.getName();

        switch (provider.getId()) {
            case Provider.ID_PHONE_BLOCK: return "PhoneBlock";
            case Provider.ID_TELLOWS: return "tellows";
            case Provider.ID_WEB_SEARCH: return context.getString(R.string.provider_web_search);
            case Provider.ID_CLEVER_DIALER: return "Clever Dialer";
            case Provider.ID_DASOERTLICHE: return "Das \u00d6rtliche";
            default: return context.getString(R.string.provider);
        }
    }

    /** What the row under the name says: where it goes. */
    public static String getAddress(Context context, Provider provider) {
        if (provider == null) return "";

        if (provider.hasOwnAddress()) {
            return context.getString(R.string.provider_phone_block_address);
        }

        return provider.getUrl() != null ? provider.getUrl() : "";
    }

    /**
     * The page about a number, or null when there is none.
     *
     * <p>PhoneBlock's own page is built from the address of the account, so that it points at
     * the installation the user reports to rather than at phoneblock.net by name.
     */
    public static String getUrl(Provider provider, String number) {
        if (provider == null) return null;

        if (provider.hasOwnAddress()) return PhoneBlockHelper.getNumberPageUrl(number);

        ProviderService providerService = YacbHolder.getProviderService();

        return providerService != null ? providerService.getUrl(provider, number) : null;
    }

    /** Who is told about the number, as the confirmation puts it: the host, or the name. */
    public static String getHost(Context context, Provider provider, String url) {
        String host = null;

        try {
            if (!TextUtils.isEmpty(url)) host = Uri.parse(url).getHost();
        } catch (Exception ignored) {
            // an address that can't be read is one the user typed; the name will do
        }

        return !TextUtils.isEmpty(host) ? host : getName(context, provider);
    }

}
