package dev.oxpecker.il;

public enum NamedVariableMode implements Repr.Valued {
    GLOBAL("global"),
    VAR("var"),
    LET("let"),
    CONST("const");

    public final String value;

    NamedVariableMode(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
