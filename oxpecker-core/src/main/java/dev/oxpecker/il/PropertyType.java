package dev.oxpecker.il;

public enum PropertyType implements Repr.Valued {
    VALUE("value"),
    GETTER("getter"),
    SETTER("setter"),
    GETTER_SETTER("getter-setter");

    public final String value;

    PropertyType(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
