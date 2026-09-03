package dummydomain.yetanothercallblocker.event;

import dummydomain.yetanothercallblocker.data.PhoneBlockService;

public class PhoneBlockUpdateFinishedEvent {

    public final PhoneBlockService.Result result;

    public PhoneBlockUpdateFinishedEvent(PhoneBlockService.Result result) {
        this.result = result;
    }

}
