package net.evolution515.callblocker.data;

public class ContactItem {

    public long id;
    public String displayName;

    /** The contact's own thumbnail photo, as a content URI, or null when it has none. */
    public String photoUri;

    public ContactItem(long id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return "ContactItem{" +
                "id=" + id +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
