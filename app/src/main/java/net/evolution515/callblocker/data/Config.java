package net.evolution515.callblocker.data;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import net.evolution515.callblocker.NotificationService;
import net.evolution515.callblocker.PhoneStateHandler;
import net.evolution515.callblocker.data.db.BlacklistDao;
import net.evolution515.callblocker.data.db.YacbDaoSessionFactory;
import dummydomain.yetanothercallblocker.sia.Settings;
import dummydomain.yetanothercallblocker.sia.SettingsImpl;
import net.evolution515.callblocker.data.numbers.NumbersLookup;
import dummydomain.yetanothercallblocker.sia.Storage;
import dummydomain.yetanothercallblocker.sia.model.CommunityReviewsLoader;
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata;
import dummydomain.yetanothercallblocker.sia.model.database.AbstractDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.network.DbDownloader;
import dummydomain.yetanothercallblocker.sia.network.DbUpdateRequester;
import net.evolution515.callblocker.data.provider.ProviderService;
import net.evolution515.callblocker.data.source.NumberSource;
import net.evolution515.callblocker.data.source.SourceHttp;
import net.evolution515.callblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.sia.network.OkHttpClientFactory;
import dummydomain.yetanothercallblocker.sia.network.WebService;
import dummydomain.yetanothercallblocker.sia.utils.Utils;
import net.evolution515.callblocker.utils.DbFilteringUtils;
import net.evolution515.callblocker.utils.DeferredInit;
import net.evolution515.callblocker.utils.SystemUtils;
import okhttp3.OkHttpClient;

import static net.evolution515.callblocker.data.SiaConstants.SIA_PATH_PREFIX;
import static net.evolution515.callblocker.data.SiaConstants.SIA_PROPERTIES;
import static net.evolution515.callblocker.data.SiaConstants.SIA_SECONDARY_PATH_PREFIX;

public class Config {

    private static class WSParameterProvider extends WebService.DefaultWSParameterProvider {
        final net.evolution515.callblocker.Settings settings;
        final SiaMetadata siaMetadata;
        final CommunityDatabase communityDatabase;

        volatile String appId;
        volatile long appIdTimestamp;

        WSParameterProvider(net.evolution515.callblocker.Settings settings,
                            SiaMetadata siaMetadata, CommunityDatabase communityDatabase) {
            this.settings = settings;
            this.siaMetadata = siaMetadata;
            this.communityDatabase = communityDatabase;
        }

        @Override
        public String getAppId() {
            String appId = this.appId;
            if (appId != null && System.nanoTime() >
                    appIdTimestamp + TimeUnit.MINUTES.toNanos(5)) {
                appId = null;
            }

            if (appId == null) {
                this.appId = appId = Utils.generateAppId();
                appIdTimestamp = System.nanoTime();
            }

            return appId;
        }

        @Override
        public int getAppVersion() {
            return siaMetadata.getSiaAppVersion();
        }

        @Override
        public String getOkHttpVersion() {
            return siaMetadata.getSiaOkHttpVersion();
        }

        @Override
        public int getDbVersion() {
            return communityDatabase.getEffectiveDbVersion();
        }

        @Override
        public SiaMetadata.Country getCountry() {
            return siaMetadata.getCountry(settings.getCountryCode());
        }
    }

    public static void init(Context context, net.evolution515.callblocker.Settings settings) {
        Storage storage = new AndroidStorage(context);
        Settings siaSettings
                = new SettingsImpl(new AndroidProperties(context, SIA_PROPERTIES));

        OkHttpClientFactory okHttpClientFactory = () -> {
            DeferredInit.initNetwork();
            return new OkHttpClient();
        };

        SourceService sourceService = new SourceService(settings);
        YacbHolder.setSourceService(sourceService);

        YacbHolder.setProviderService(new ProviderService(settings));

        /*
         * The database is fetched the way the source it comes from says: with whatever it
         * needs to let us in, and unpacked when it arrives packed. Which source that is can
         * change while the app runs - and changes as a compile walks the list - so it is
         * looked up per request rather than kept.
         */
        OkHttpClientFactory dbClientFactory = () -> {
            DeferredInit.initNetwork();

            NumberSource source = sourceService.getFetchingSource();

            /*
             * Logged in as that source, and not a byte more: this client feeds the
             * library's own downloader, which is handed a zip and unpacks it itself. An
             * answer that is unpacked on the way in arrives there as a single file that is
             * not an archive, unpacks into nothing, and replaces the database with it.
             */
            return SourceHttp.decorate(new OkHttpClient(), source,
                    source != null ? sourceService.getSecret(source.getId()) : null, false);
        };

        YacbHolder.setStorage(storage);
        YacbHolder.setSiaSettings(siaSettings);

        CommunityDatabase communityDatabase = new CommunityDatabase(
                storage, AbstractDatabase.Source.ANY, SIA_PATH_PREFIX,
                SIA_SECONDARY_PATH_PREFIX, siaSettings);
        YacbHolder.setCommunityDatabase(communityDatabase);

        SiaMetadata siaMetadata = new SiaMetadata(storage, SIA_PATH_PREFIX,
                communityDatabase::isUsingInternal);
        YacbHolder.setSiaMetadata(siaMetadata);

        FeaturedDatabase featuredDatabase = new FeaturedDatabase(
                storage, AbstractDatabase.Source.ANY, SIA_PATH_PREFIX);
        YacbHolder.setFeaturedDatabase(featuredDatabase);

        WSParameterProvider wsParameterProvider = new WSParameterProvider(
                settings, siaMetadata, communityDatabase);

        WebService webService = new WebService(wsParameterProvider, okHttpClientFactory);
        YacbHolder.setWebService(webService);

        YacbHolder.setDbManager(new DbManager(storage, SIA_PATH_PREFIX,
                new DbDownloader(dbClientFactory), new DbUpdateRequester(webService),
                communityDatabase));

        YacbHolder.getDbManager().setNumberFilter(DbFilteringUtils.getNumberFilter(settings));

        YacbHolder.setCommunityReviewsLoader(new CommunityReviewsLoader(webService));

        YacbDaoSessionFactory daoSessionFactory = new YacbDaoSessionFactory(context, "YACB");

        BlacklistDao blacklistDao = new BlacklistDao(daoSessionFactory::getDaoSession);
        YacbHolder.setBlacklistDao(blacklistDao);

        BlacklistService blacklistService = new BlacklistService(
                settings::setBlacklistIsNotEmpty, blacklistDao);
        YacbHolder.setBlacklistService(blacklistService);

        ContactsProvider contactsProvider = new ContactsProvider() {
            @Override
            public ContactItem get(String number) {
                return settings.getUseContacts() ? ContactsHelper.getContact(context, number) : null;
            }

            @Override
            public boolean isInLimitedMode() {
                return !SystemUtils.isUserUnlocked(context);
            }
        };

        PhoneBlockList phoneBlockList = new PhoneBlockList(storage::getDataDirPath);
        YacbHolder.setPhoneBlockList(phoneBlockList);

        PhoneBlockPersonalLists phoneBlockPersonalLists
                = new PhoneBlockPersonalLists(storage::getDataDirPath);
        YacbHolder.setPhoneBlockPersonalLists(phoneBlockPersonalLists);

        // the table every source is built into, which is where a number is looked up
        NumbersLookup numbersLookup = new NumbersLookup(context);
        YacbHolder.setNumbersLookup(numbersLookup);

        NumberInfoService numberInfoService = new NumberInfoService(
                settings, NumberUtils::isHiddenNumber, NumberUtils::normalizeNumber,
                communityDatabase, featuredDatabase, contactsProvider, blacklistService);
        numberInfoService.setNumbersLookup(numbersLookup);

        // where a number is from; copied out of the assets now rather than on the first call
        GeoLookup geoLookup = new GeoLookup(context);
        YacbHolder.setGeoLookup(geoLookup);
        numberInfoService.setGeoLookup(geoLookup);
        new Thread(geoLookup::prepare, "yacb-geo").start();
        numberInfoService.setPhoneBlockList(phoneBlockList);
        numberInfoService.setPhoneBlockPersonalLists(phoneBlockPersonalLists);
        numberInfoService.setWhitelist(new Whitelist(settings));

        // each list takes a number off the other when it is put on as itself
        WhitelistService whitelistService = new WhitelistService(settings, blacklistService);
        blacklistService.setWhitelistService(whitelistService);
        YacbHolder.setWhitelistService(whitelistService);
        YacbHolder.setNumberInfoService(numberInfoService);

        YacbHolder.setNumberInfoCache(new NumberInfoCache());

        // the call log shows what the app did about a call, which only the app knows
        YacbHolder.setCallDecisionLog(new CallDecisionLog(settings));

        NotificationService notificationService = new NotificationService(context);
        YacbHolder.setNotificationService(notificationService);

        YacbHolder.setPhoneStateHandler(
                new PhoneStateHandler(context, settings, numberInfoService, notificationService));
    }

}
