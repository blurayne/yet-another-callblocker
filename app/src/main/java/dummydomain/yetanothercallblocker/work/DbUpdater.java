package dummydomain.yetanothercallblocker.work;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.PhoneBlockHelper;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.DbCompileService;
import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;

import static dummydomain.yetanothercallblocker.EventUtils.postEvent;
import static dummydomain.yetanothercallblocker.EventUtils.postStickyEvent;
import static dummydomain.yetanothercallblocker.EventUtils.removeStickyEvent;

public class DbUpdater {

    /**
     * @return what went wrong, one line each, or nothing when nothing did. The caller is
     *         the one with a notification to put it in; this only knows what happened.
     */
    public List<String> update() {
        App app = App.getInstance();
        Settings settings = App.getSettings();

        boolean updated = false;

        List<String> problems = new ArrayList<>();

        SecondaryDbUpdatingEvent sticky = new SecondaryDbUpdatingEvent();

        postStickyEvent(sticky);
        try {
            DbManager.UpdateResult updateResult = YacbHolder.getDbManager().updateSecondaryDb();
            if (updateResult.isUpdated()) {
                settings.setLastUpdateTime(System.currentTimeMillis());
                updated = true;
            } // TODO: handle other results
            settings.setLastUpdateCheckTime(System.currentTimeMillis());

            /*
             * And then what the user's own sources say. A source set to be fetched daily is
             * fetched daily by this and by nothing else - without it, the schedule on the
             * source would be a setting that never came round.
             *
             * The whole database is built when one of them is due, because that is what a
             * fetched source means: the table is every source read in order, so one of them
             * changing is the table changing. When none is due there is nothing to build,
             * and what the library fetched for itself goes into the table on its own.
             */
            if (app != null) {
                DbCompileService compileService = new DbCompileService(app, settings);

                if (isAnySourceDue()) {
                    DbCompileService.Result result
                            = compileService.compile(DbCompileService.Trigger.SCHEDULED, null);

                    // a build that went wrong, or went on without someone, is worth a word
                    if (!result.isOk() && result.reason != null) problems.add(result.reason);
                    problems.addAll(result.failures);
                } else if (updated) {
                    /*
                     * A number is looked up in the table the sources were built into, so an
                     * update that only reaches the library's own files would not be seen
                     * until the next build. It goes into the table now, as the source it
                     * belongs to.
                     */
                    compileService.mergeSecondaryUpdate();
                }
            }
        } finally {
            removeStickyEvent(sticky);
            postEvent(new SecondaryDbUpdateFinished(updated));
        }

        // the community list has its own pace, which it keeps track of itself
        PhoneBlockService phoneBlockService = new PhoneBlockService(settings,
                YacbHolder.getPhoneBlockList(), YacbHolder.getPhoneBlockPersonalLists());

        // not configured is not a problem; a configured account that won't answer is
        if (phoneBlockService.update(false).status == PhoneBlockService.Status.FAILED
                && app != null) {
            problems.add(app.getString(R.string.phone_block_update_failed));
        }

        phoneBlockService.updatePersonalLists(false);

        // the token is checked here because this is what runs daily
        if (app != null) PhoneBlockHelper.checkTokenIfDue(app, settings);

        return problems;
    }

    /**
     * Whether any source's own schedule has come round.
     *
     * <p>Asked before anything is built, because building is minutes and most days the
     * answer is no: the sources that are fetched by hand are not asked by the clock, and the
     * ones that are have days or weeks between their turns.
     */
    private static boolean isAnySourceDue() {
        SourceService sourceService = YacbHolder.getSourceService();
        if (sourceService == null) return false;

        long now = System.currentTimeMillis();

        for (NumberSource source : sourceService.getEnabledSources(
                NumberSource.Type.DATABASE)) {
            if (source.getUpdates().isScheduled() && source.isDue(now)) return true;
        }

        return false;
    }

}
