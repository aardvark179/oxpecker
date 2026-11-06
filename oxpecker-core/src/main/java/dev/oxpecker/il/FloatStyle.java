package dev.oxpecker.il;

public enum FloatStyle implements Repr.Valued {
    PLAIN("plain"),
    EXPONENT("exponent");

    public final String value;

    FloatStyle(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
