package dev.oxpecker.il;

/** The possible kinds of a value; a {@link Type} holds a set of them as a bit mask (types.py Kind). */
public enum Kind {
    UNDEFINED, NULL, BOOLEAN, INTEGER, FLOAT, BIGINT, STRING, SYMBOL, OBJECT, FUNCTION;

    public final int bit = 1 << ordinal();

    public static final int ALL = (1 << values().length) - 1;

    public static int of(Kind... kinds) {
        int m = 0;
        for (Kind k : kinds) m |= k.bit;
        return m;
    }
}
