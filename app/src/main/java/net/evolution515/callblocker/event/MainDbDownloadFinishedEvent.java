package net.evolution515.callblocker.event;

public class MainDbDownloadFinishedEvent {

    /** Nothing was fetched because no source says where the database comes from. */
    public final boolean noSources;

    /** The build stopped because someone asked it to. */
    public final boolean cancelled;

    public MainDbDownloadFinishedEvent() {
        this(false, false);
    }

    public MainDbDownloadFinishedEvent(boolean noSources) {
        this(noSources, false);
    }

    public MainDbDownloadFinishedEvent(boolean noSources, boolean cancelled) {
        this.noSources = noSources;
        this.cancelled = cancelled;
    }

}
