package dummydomain.yetanothercallblocker.event;

public class MainDbDownloadFinishedEvent {

    /** Nothing was fetched because no source says where the database comes from. */
    public final boolean noSources;

    public MainDbDownloadFinishedEvent() {
        this(false);
    }

    public MainDbDownloadFinishedEvent(boolean noSources) {
        this.noSources = noSources;
    }

}
