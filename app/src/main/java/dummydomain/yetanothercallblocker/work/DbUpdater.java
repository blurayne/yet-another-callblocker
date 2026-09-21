package dummydomain.yetanothercallblocker.work;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.PhoneBlockHelper;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.DbFilteringService;
import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;
import static dummydomain.yetanothercallblocker.EventUtils.postStickyEvent;
import static dummydomain.yetanothercallblocker.EventUtils.removeStickyEvent;

public class DbUpdater {

    public void update() {
        App app = App.getInstance();
        Settings settings = App.getSettings();

        boolean updated = false;

        SecondaryDbUpdatingEvent sticky = new SecondaryDbUpdatingEvent();

        postStickyEvent(sticky);
        try {
            DbManager.UpdateResult updateResult = YacbHolder.getDbManager().updateSecondaryDb();
            if (updateResult.isUpdated()) {
                settings.setLastUpdateTime(System.currentTimeMillis());
                updated = true;

                /*
                 * The update is merged where the other sources were merged into, so it can
                 * bury what they added - and bring back what they took out. They go on top
                 * again, from what was fetched last time, without asking them again.
                 */
                if (app != null) new DbCompileService(app, settings).reapplyLayers();

                // the update brings unfiltered entries with it
                if (app != null) new DbFilteringService(app, settings).updateFilter(false);
            } // TODO: handle other results
            settings.setLastUpdateCheckTime(System.currentTimeMillis());
        } finally {
            removeStickyEvent(sticky);
            postEvent(new SecondaryDbUpdateFinished(updated));
        }

        // the community list has its own pace, which it keeps track of itself
        PhoneBlockService phoneBlockService = new PhoneBlockService(settings,
                YacbHolder.getPhoneBlockList(), YacbHolder.getPhoneBlockPersonalLists());
        phoneBlockService.update(false);
        phoneBlockService.updatePersonalLists(false);

        // the token is checked here because this is what runs daily
        if (app != null) PhoneBlockHelper.checkTokenIfDue(app, settings);
    }

}
