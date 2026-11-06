package dev.oxpecker.il;

public enum Radix implements Repr.Valued {
    DEC(10), HEX(16), OCT(8), BIN(2);

    public final int value;

    Radix(int value) { this.value = value; }

    @Override public Object value() { return value; }
}
