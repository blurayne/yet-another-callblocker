package net.evolution515.callblocker.data;

import android.text.TextUtils;

/**
 * One entry of the whitelist: a number or pattern, and the name the user gave it.
 *
 * <p>The pattern is kept the way it is written ({@code *} for any digits, {@code #} for one),
 * the way the blacklist screens show theirs.
 */
public class WhitelistItem {

    private final String name;
    private final String notes;
    private final String pattern;

    public WhitelistItem(String name, String pattern) {
        this(name, pattern, null);
    }

    public WhitelistItem(String name, String pattern, String notes) {
        this.name = name != null ? name.trim() : "";
        this.notes = notes != null ? notes.trim() : "";
        this.pattern = Whitelist.normalize(pattern);
    }

    public String getName() {
        return name;
    }

    /** What the user wants to remember about the number; not used for anything else. */
    public String getNotes() {
        return notes;
    }

    public String getPattern() {
        return pattern;
    }

    public boolean isValid() {
        return !pattern.isEmpty()
                && BlacklistUtils.isValidPattern(BlacklistUtils.patternFromHumanReadable(pattern));
    }

    /** Whether the entry is the number itself rather than a pattern covering it. */
    public boolean isExactly(String number) {
        return !pattern.isEmpty() && pattern.equals(Whitelist.normalize(number));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WhitelistItem)) return false;

        WhitelistItem other = (WhitelistItem) o;
        return pattern.equals(other.pattern) && name.equals(other.name)
                && notes.equals(other.notes);
    }

    @Override
    public int hashCode() {
        return (pattern.hashCode() * 31 + name.hashCode()) * 31 + notes.hashCode();
    }

    @Override
    public String toString() {
        return !TextUtils.isEmpty(name) ? name + " (" + pattern + ')' : pattern;
    }

}
