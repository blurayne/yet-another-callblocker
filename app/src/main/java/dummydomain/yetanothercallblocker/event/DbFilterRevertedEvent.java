package dummydomain.yetanothercallblocker.event;

public class DbFilterRevertedEvent {

    public final boolean reverted;

    public DbFilterRevertedEvent(boolean reverted) {
        this.reverted = reverted;
    }

}
