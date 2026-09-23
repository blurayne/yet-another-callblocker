package net.evolution515.callblocker.event;

/**
 * How far the build has got, for whoever is looking at a screen about the database.
 *
 * <p>The same thing the notification says, said inside the app: a build takes minutes, and a
 * screen that reads "nothing built yet" for all of them is indistinguishable from one where
 * nothing is happening.
 */
public class DbCompileProgressEvent {

    /** What is being done right now. */
    public final int titleResId;

    /** How far along it is, or -1 when this step has nothing to count. */
    public final int current;
    public final int total;

    public DbCompileProgressEvent(int titleResId, int current, int total) {
        this.titleResId = titleResId;
        this.current = current;
        this.total = total;
    }

}
