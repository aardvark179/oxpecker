package dev.oxpecker.il;

/** Operation.swift:99-152. A bit mask. */
public final class Attr {
    private Attr() {}
    public static final int NONE = 0;
    public static final int MUTABLE = 1;
    public static final int CALL = 1 << 1;
    public static final int BLOCK_START = 1 << 2;
    public static final int BLOCK_END = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int VARIADIC = 1 << 5;
    public static final int SINGULAR = 1 << 6;
    public static final int PROPAGATES_CONTEXT = 1 << 7;
    public static final int RESUMES_CONTEXT = 1 << 8;
}
