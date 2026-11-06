package dev.oxpecker.il;

/** Why Rhino b95f75d gives a function an activation frame (docs §5.3); the value cites the source. */
public enum ActivationReason implements Repr.Valued {
    NESTED_FUNCTION("nested function (IRFactory.java:1586)"),
    CATCH("catch clause (IRFactory.java:2035)"),
    WITH("with statement (IRFactory.java:2077)"),
    EVAL("direct eval (Parser.java:4852)"),
    ARGUMENTS("arguments (Parser.java:4836)"),
    YIELD("yield (Parser.java:2432)"),
    SUPER("super (IRFactory.java:205)");

    public final String value;

    ActivationReason(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
