package dummydomain.yetanothercallblocker.event;

import dummydomain.yetanothercallblocker.data.DbFilteringService;

public class DbFilteringFinishedEvent {

    public final DbFilteringService.Result result;

    public DbFilteringFinishedEvent(DbFilteringService.Result result) {
        this.result = result;
    }

}
