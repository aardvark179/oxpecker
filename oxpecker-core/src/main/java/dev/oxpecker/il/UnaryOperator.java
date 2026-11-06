package dev.oxpecker.il;

public enum UnaryOperator implements Repr.Valued {
    PRE_INC("pre-inc"),
    PRE_DEC("pre-dec"),
    POST_INC("post-inc"),
    POST_DEC("post-dec"),
    LOGICAL_NOT("logical-not"),
    BITWISE_NOT("bitwise-not"),
    PLUS("plus"),
    MINUS("minus");

    public final String value;

    UnaryOperator(String value) { this.value = value; }

    @Override public Object value() { return value; }

    public boolean reassignsInput() { return this == PRE_INC || this == PRE_DEC || this == POST_INC || this == POST_DEC; }

    public boolean isPostfix() { return this == POST_INC || this == POST_DEC; }
}
