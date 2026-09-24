package net.evolution515.callblocker.event;

import net.evolution515.callblocker.data.PhoneBlockService;

public class PhoneBlockUpdateFinishedEvent {

    public final PhoneBlockService.Result result;

    public PhoneBlockUpdateFinishedEvent(PhoneBlockService.Result result) {
        this.result = result;
    }

}
