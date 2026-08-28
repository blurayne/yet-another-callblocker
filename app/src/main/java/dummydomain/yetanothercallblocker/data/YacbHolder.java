package dummydomain.yetanothercallblocker.data;

import android.annotation.SuppressLint;

import dummydomain.yetanothercallblocker.NotificationService;
import dummydomain.yetanothercallblocker.PhoneStateHandler;
import dummydomain.yetanothercallblocker.data.db.BlacklistDao;
import dummydomain.yetanothercallblocker.sia.Storage;
import dummydomain.yetanothercallblocker.sia.model.CommunityReviewsLoader;
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.network.WebService;

public class YacbHolder {

    private static Storage storage;
    private static dummydomain.yetanothercallblocker.sia.Settings siaSettings;
    private static WebService webService;
    private static DbManager dbManager;
    private static SiaMetadata siaMetadata;
    private static CommunityDatabase communityDatabase;
    private static FeaturedDatabase featuredDatabase;
    private static CommunityReviewsLoader communityReviewsLoader;

    private static BlacklistDao blacklistDao;
    private static BlacklistService blacklistService;

    private static NumberInfoService numberInfoService;
    private static NumberInfoCache numberInfoCache;

    @SuppressLint("StaticFieldLeak")
    private static NotificationService notificationService;

    @SuppressLint("StaticFieldLeak")
    private static PhoneStateHandler phoneStateHandler;

    static void setStorage(Storage storage) {
        YacbHolder.storage = storage;
    }

    static void setSiaSettings(dummydomain.yetanothercallblocker.sia.Settings siaSettings) {
        YacbHolder.siaSettings = siaSettings;
    }

    static void setWebService(WebService webService) {
        YacbHolder.webService = webService;
    }

    static void setDbManager(DbManager dbManager) {
        YacbHolder.dbManager = dbManager;
    }

    static void setSiaMetadata(SiaMetadata siaMetadata) {
        YacbHolder.siaMetadata = siaMetadata;
    }

    static void setCommunityDatabase(CommunityDatabase communityDatabase) {
        YacbHolder.communityDatabase = communityDatabase;
    }

    static void setFeaturedDatabase(FeaturedDatabase featuredDatabase) {
        YacbHolder.featuredDatabase = featuredDatabase;
    }

    static void setCommunityReviewsLoader(CommunityReviewsLoader communityReviewsLoader) {
        YacbHolder.communityReviewsLoader = communityReviewsLoader;
    }

    static void setBlacklistDao(BlacklistDao blacklistDao) {
        YacbHolder.blacklistDao = blacklistDao;
    }

    static void setBlacklistService(BlacklistService blacklistService) {
        YacbHolder.blacklistService = blacklistService;
    }

    static void setNumberInfoService(NumberInfoService numberInfoService) {
        YacbHolder.numberInfoService = numberInfoService;
    }

    static void setNumberInfoCache(NumberInfoCache numberInfoCache) {
        YacbHolder.numberInfoCache = numberInfoCache;
    }

    static void setNotificationService(NotificationService notificationService) {
        YacbHolder.notificationService = notificationService;
    }

    static void setPhoneStateHandler(PhoneStateHandler phoneStateHandler) {
        YacbHolder.phoneStateHandler = phoneStateHandler;
    }

    /** The storage the databases live in. */
    public static Storage getStorage() {
        return storage;
    }

    /** The settings of the SIA library (the database versions). */
    public static dummydomain.yetanothercallblocker.sia.Settings getSiaSettings() {
        return siaSettings;
    }

    public static WebService getWebService() {
        return webService;
    }

    public static DbManager getDbManager() {
        return dbManager;
    }

    public static SiaMetadata getSiaMetadata() {
        return siaMetadata;
    }

    public static CommunityDatabase getCommunityDatabase() {
        return communityDatabase;
    }

    public static FeaturedDatabase getFeaturedDatabase() {
        return featuredDatabase;
    }

    public static CommunityReviewsLoader getCommunityReviewsLoader() {
        return communityReviewsLoader;
    }

    public static BlacklistDao getBlacklistDao() {
        return blacklistDao;
    }

    public static BlacklistService getBlacklistService() {
        return blacklistService;
    }

    public static NumberInfoService getNumberInfoService() {
        return numberInfoService;
    }

    public static NumberInfoCache getNumberInfoCache() {
        return numberInfoCache;
    }

    public static NotificationService getNotificationService() {
        return notificationService;
    }

    public static PhoneStateHandler getPhoneStateHandler() {
        return phoneStateHandler;
    }

    public static NumberInfo getNumberInfo(String number, String countryCode) {
        return numberInfoService.getNumberInfo(number, countryCode, true);
    }

}
