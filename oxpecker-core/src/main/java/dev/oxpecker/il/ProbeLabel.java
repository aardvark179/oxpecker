package dev.oxpecker.il;

public enum ProbeLabel implements Repr.Valued {
    STEP("step"),
    END("end"),
    WARM("warm"),
    CHANGED("changed"),
    CATCH("catch"),
    PAUSE("pause"),
    JAVA("java");

    public final String value;

    ProbeLabel(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
