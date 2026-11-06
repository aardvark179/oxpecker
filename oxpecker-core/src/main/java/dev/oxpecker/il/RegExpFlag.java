package dev.oxpecker.il;

/** JsOperations.swift:281-365, minus unicodeSets and hasIndices (no `v`, no `d` in Rhino). */
public final class RegExpFlag {
    private RegExpFlag() {}
    public static final int IGNORE_CASE = 1, GLOBAL = 2, MULTILINE = 4, DOT_ALL = 8, UNICODE = 16, STICKY = 32;
    private static final String[] NAMES = {"NONE", "IGNORE_CASE", "GLOBAL", "MULTILINE", "DOT_ALL", "UNICODE", "STICKY"};
    public static final int[] ALL = {IGNORE_CASE, GLOBAL, MULTILINE, DOT_ALL, UNICODE, STICKY};
    public static final Flags NONE = of(0);

    public static Flags of(int bits) { return new Flags("RegExpFlag", NAMES, bits); }
}
