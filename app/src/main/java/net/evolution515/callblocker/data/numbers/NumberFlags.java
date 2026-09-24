package net.evolution515.callblocker.data.numbers;

/**
 * What is known about a number, packed into one integer.
 *
 * <p>Two bits say what the community thinks of it, seven hold the category it was put in, and
 * the rest are single facts: that the number was taken out again, and that it was the user
 * who said so rather than a stranger.
 *
 * <p>Bits rather than columns because there are millions of rows and none of these is ever
 * searched for on its own - they are read with the row and looked at in Java.
 */
public class NumberFlags {

    /** Nothing is known about the number. */
    public static final int RATING_UNKNOWN = 0;
    public static final int RATING_NEGATIVE = 1;
    public static final int RATING_NEUTRAL = 2;
    public static final int RATING_POSITIVE = 3;

    private static final int RATING_MASK = 0b11;

    private static final int CATEGORY_SHIFT = 2;
    private static final int CATEGORY_MASK = 0b111_1111 << CATEGORY_SHIFT;

    /** The number was taken out by a later source; what is underneath doesn't count. */
    public static final int FLAG_DELETED = 1 << 9;

    /** The user said this, not the community: it wins over what any source says. */
    public static final int FLAG_PERSONAL = 1 << 10;

    private NumberFlags() {
    }

    public static int of(int rating, int category, int flags) {
        return (rating & RATING_MASK)
                | ((category << CATEGORY_SHIFT) & CATEGORY_MASK)
                | (flags & ~(RATING_MASK | CATEGORY_MASK));
    }

    public static int getRating(int value) {
        return value & RATING_MASK;
    }

    public static int getCategory(int value) {
        return (value & CATEGORY_MASK) >>> CATEGORY_SHIFT;
    }

    public static boolean isDeleted(int value) {
        return (value & FLAG_DELETED) != 0;
    }

    public static boolean isPersonal(int value) {
        return (value & FLAG_PERSONAL) != 0;
    }

    public static int withRating(int value, int rating) {
        return (value & ~RATING_MASK) | (rating & RATING_MASK);
    }

    public static int withCategory(int value, int category) {
        return (value & ~CATEGORY_MASK) | ((category << CATEGORY_SHIFT) & CATEGORY_MASK);
    }

    public static int withFlag(int value, int flag, boolean set) {
        return set ? value | flag : value & ~flag;
    }

    /**
     * What the counts add up to, the way the app has always read them: more against than for
     * and indifferent together is bad, more for than the rest together is good.
     */
    public static int ratingOf(int positive, int negative, int neutral) {
        if (positive == 0 && negative == 0 && neutral == 0) return RATING_UNKNOWN;

        if (negative > positive + neutral) return RATING_NEGATIVE;
        if (positive > negative + neutral) return RATING_POSITIVE;

        return RATING_NEUTRAL;
    }

    /**
     * How strongly the community feels, in one byte: against minus for.
     *
     * <p>The counts themselves stop at 255 and are of no use beyond the rating, but the
     * difference says whether a number is an isolated complaint or a campaign.
     */
    public static int scoreOf(int positive, int negative) {
        int score = negative - positive;

        if (score > 127) return 127;
        if (score < -128) return -128;

        return score;
    }

}
