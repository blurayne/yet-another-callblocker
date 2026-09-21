package dummydomain.yetanothercallblocker.work;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.PhoneBlockHelper;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.DbCompileService;
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
                 * A number is looked up in the table the sources were built into, so an
                 * update that only reaches the library's own files would not be seen until
                 * the next build. It goes into the table now, as the source it belongs to.
                 */
                if (app != null) new DbCompileService(app, settings).mergeSecondaryUpdate();
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
