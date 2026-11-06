package dev.oxpecker.il;

public enum DirectiveKind implements Repr.Valued {
    USE_STRICT("use strict");

    public final String value;

    DirectiveKind(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
