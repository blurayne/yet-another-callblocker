package net.evolution515.callblocker.data;

import android.annotation.SuppressLint;

import net.evolution515.callblocker.NotificationService;
import net.evolution515.callblocker.PhoneStateHandler;
import net.evolution515.callblocker.data.db.BlacklistDao;
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
    private static WhitelistService whitelistService;

    private static CallDecisionLog callDecisionLog;
    private static net.evolution515.callblocker.data.source.SourceService sourceService;
    private static net.evolution515.callblocker.data.provider.ProviderService providerService;

    private static NumberInfoService numberInfoService;
    private static net.evolution515.callblocker.data.numbers.NumbersLookup numbersLookup;
    private static NumberInfoCache numberInfoCache;
    private static PhoneBlockList phoneBlockList;
    private static PhoneBlockPersonalLists phoneBlockPersonalLists;

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

    static void setWhitelistService(WhitelistService whitelistService) {
        YacbHolder.whitelistService = whitelistService;
    }

    static void setSourceService(
            net.evolution515.callblocker.data.source.SourceService sourceService) {
        YacbHolder.sourceService = sourceService;
    }

    public static net.evolution515.callblocker.data.source.SourceService getSourceService() {
        return sourceService;
    }

    static void setProviderService(
            net.evolution515.callblocker.data.provider.ProviderService providerService) {
        YacbHolder.providerService = providerService;
    }

    public static net.evolution515.callblocker.data.provider.ProviderService
            getProviderService() {
        return providerService;
    }

    static void setCallDecisionLog(CallDecisionLog callDecisionLog) {
        YacbHolder.callDecisionLog = callDecisionLog;
    }

    public static CallDecisionLog getCallDecisionLog() {
        return callDecisionLog;
    }

    static void setBlacklistService(BlacklistService blacklistService) {
        YacbHolder.blacklistService = blacklistService;
    }

    static void setNumbersLookup(
            net.evolution515.callblocker.data.numbers.NumbersLookup numbersLookup) {
        YacbHolder.numbersLookup = numbersLookup;
    }

    /** Where a number is looked up: the table every source was built into. */
    public static net.evolution515.callblocker.data.numbers.NumbersLookup getNumbersLookup() {
        return numbersLookup;
    }

    private static GeoLookup geoLookup;

    static void setGeoLookup(GeoLookup geoLookup) {
        YacbHolder.geoLookup = geoLookup;
    }

    /** Where a number is from, out of the table in the assets. */
    public static GeoLookup getGeoLookup() {
        return geoLookup;
    }

    static void setNumberInfoService(NumberInfoService numberInfoService) {
        YacbHolder.numberInfoService = numberInfoService;
    }

    static void setPhoneBlockList(PhoneBlockList phoneBlockList) {
        YacbHolder.phoneBlockList = phoneBlockList;
    }

    static void setPhoneBlockPersonalLists(PhoneBlockPersonalLists phoneBlockPersonalLists) {
        YacbHolder.phoneBlockPersonalLists = phoneBlockPersonalLists;
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

    /** The whitelist and the changes to it. */
    public static WhitelistService getWhitelistService() {
        return whitelistService;
    }

    public static BlacklistService getBlacklistService() {
        return blacklistService;
    }

    public static NumberInfoService getNumberInfoService() {
        return numberInfoService;
    }

    /** The PhoneBlock community list kept on the device. */
    public static PhoneBlockList getPhoneBlockList() {
        return phoneBlockList;
    }

    /** The lists of the user's own PhoneBlock account. */
    public static PhoneBlockPersonalLists getPhoneBlockPersonalLists() {
        return phoneBlockPersonalLists;
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
