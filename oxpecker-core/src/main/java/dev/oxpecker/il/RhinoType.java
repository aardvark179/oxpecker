package dev.oxpecker.il;

/** Optimizer.java:15-17, plus what Codegen does with a mix (G32). */
public enum RhinoType implements Repr.Valued {
    /** NumberType: a number variable lives in a Java double. */
    NUMBER("number"),
    /** AnyType: computed and boxed like unoptimised code. */
    ANY("any"),
    /**
     * AnyType to the optimizer, but an ADD with one NumberType operand: Codegen calls
     * ScriptRuntime.add(Object, double), a Double at a boundary.
     */
    MIXED("mixed");

    public final String value;

    RhinoType(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
