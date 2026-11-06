package dev.oxpecker.il;

public enum KeyKind implements Repr.Valued {
    INDEX("index"),            // "0", "10": array element style keys
    LENGTH("length"),
    PROTO("proto"),            // "__proto__"
    WELL_KNOWN("well-known"),  // "constructor", "toString", ...
    ORDINARY("ordinary");      // "a", "x", "foo"

    public final String value;

    KeyKind(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
