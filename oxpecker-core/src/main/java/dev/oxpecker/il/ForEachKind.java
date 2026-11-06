package dev.oxpecker.il;

public enum ForEachKind implements Repr.Valued {
    OF("of"),
    IN("in");

    public final String value;

    ForEachKind(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
