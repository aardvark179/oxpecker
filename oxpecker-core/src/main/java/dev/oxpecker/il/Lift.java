package dev.oxpecker.il;

public enum Lift implements Repr.Valued {
    INLINE("inline"),
    VAR("var"),
    LET("let"),
    CONST("const");

    public final String value;

    Lift(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
