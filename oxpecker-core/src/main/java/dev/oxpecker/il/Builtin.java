package dev.oxpecker.il;

/** A global from the registry. {@code name} is spelled by the lifter only. */
public record Builtin(String name, Type type, Type constructs, Type calls, Signature signature, String group) {
    public Builtin(String name, Type type) { this(name, type, null, null, null, null); }

    @Override public String toString() { return "Builtin(" + Repr.str(name) + ")"; }
}
