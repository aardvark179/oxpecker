package dev.oxpecker.il;

public class InvalidProgram extends RuntimeException {
    public final String kind;
    public final Integer index;

    public InvalidProgram(String kind, String message, Integer index) {
        super(kind + ": " + message + (index != null ? " (at " + index + ")" : ""));
        this.kind = kind;
        this.index = index;
    }

    public InvalidProgram(String kind, String message) { this(kind, message, null); }
}
