package dev.oxpecker.il;

/** JsOperations.swift:1150-1169. */
public final class PropertyFlag {
    private PropertyFlag() {}
    public static final int WRITABLE = 1, CONFIGURABLE = 2, ENUMERABLE = 4;
    private static final String[] NAMES = {"NONE", "WRITABLE", "CONFIGURABLE", "ENUMERABLE"};
    public static final Flags NONE = of(0);

    public static Flags of(int bits) { return new Flags("PropertyFlag", NAMES, bits); }
}
