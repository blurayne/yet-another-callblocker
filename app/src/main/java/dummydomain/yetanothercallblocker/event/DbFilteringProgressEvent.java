package dummydomain.yetanothercallblocker.event;

public class DbFilteringProgressEvent {

    public final int current;
    public final int total;

    public DbFilteringProgressEvent(int current, int total) {
        this.current = current;
        this.total = total;
    }

}
