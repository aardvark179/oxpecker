package dev.oxpecker.il;

public enum DeclarationKind implements Repr.Valued {
    VAR("var"),
    LET("let"),
    CONST("const");

    public final String value;

    DeclarationKind(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
