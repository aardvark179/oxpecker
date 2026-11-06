package dev.oxpecker.il;

import dev.oxpecker.il.Ops.Operation;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * An operation applied to variables: inputs, then outputs (visible in the enclosing scope), then
 * inner outputs (visible only inside the block this instruction opens). Provenance: {@code gen}
 * (generator name), {@code origin} (generator invocation), {@code step} (mutation step).
 */
public record Instruction(Operation op, int[] inouts, int index, String gen, int origin, int step, Lift lift) {
    public Instruction {
        int expected = op.inputs() + op.outputs() + op.innerOutputs();
        if (inouts.length != expected)
            throw new IllegalArgumentException(op.name() + ": expected " + expected + " inouts, got " + inouts.length);
        inouts = inouts.clone();
        if (gen == null) gen = "";
    }

    public Instruction(Operation op, int... inouts) { this(op, inouts, -1, "", 0, 0, null); }

    @Override public int[] inouts() { return inouts.clone(); }

    public int inout(int i) { return inouts[i]; }

    public int numInouts() { return inouts.length; }

    public int[] inputs() { return Arrays.copyOfRange(inouts, 0, op.inputs()); }

    public int[] outputs() { int n = op.inputs(); return Arrays.copyOfRange(inouts, n, n + op.outputs()); }

    public int[] innerOutputs() { return Arrays.copyOfRange(inouts, op.inputs() + op.outputs(), inouts.length); }

    public int[] allOutputs() { return Arrays.copyOfRange(inouts, op.inputs(), inouts.length); }

    public int output() {
        if (op.outputs() != 1) throw new IllegalStateException(op.name() + " has " + op.outputs() + " outputs");
        return inouts[op.inputs()];
    }

    /** Like Python's {@code inputs[i]}: negative indexes count from the end. */
    public int input(int i) {
        int n = op.inputs();
        if (i < 0) i += n;
        if (i < 0 || i >= n) throw new IndexOutOfBoundsException(i);
        return inouts[i];
    }

    /** Like Python's {@code inner_outputs[i]}: negative indexes count from the end. */
    public int innerOutput(int i) {
        int[] inner = innerOutputs();
        return inner[i < 0 ? i + inner.length : i];
    }

    public int innerOutput() { return innerOutput(0); }

    public int[] variadicInputs() {
        int fv = op.firstVariadic();
        return fv < 0 ? new int[0] : Arrays.copyOfRange(inouts, fv, op.inputs());
    }

    public boolean hasValue() { return op.outputs() == 1; }

    public Instruction withOp(Operation newOp) { return new Instruction(newOp, inouts, index, gen, origin, step, lift); }
    public Instruction withInouts(int[] newInouts) { return new Instruction(op, newInouts, index, gen, origin, step, lift); }
    public Instruction withIndex(int newIndex) { return new Instruction(op, inouts, newIndex, gen, origin, step, lift); }
    public Instruction withLift(Lift newLift) { return new Instruction(op, inouts, index, gen, origin, step, newLift); }
    public Instruction withStep(int newStep) { return new Instruction(op, inouts, index, gen, origin, newStep, lift); }
    public Instruction withProvenance(String newGen, int newOrigin, int newStep) {
        return new Instruction(op, inouts, index, newGen, newOrigin, newStep, lift);
    }

    @Override public boolean equals(Object o) {
        return o instanceof Instruction i && i.op.equals(op) && Arrays.equals(i.inouts, inouts) && i.index == index
                && i.gen.equals(gen) && i.origin == origin && i.step == step && i.lift == lift;
    }

    @Override public int hashCode() {
        return ((op.hashCode() * 31 + Arrays.hashCode(inouts)) * 31 + index) * 31 + (lift == null ? 0 : lift.hashCode());
    }

    private static String vars(int[] vs) {
        return IntStream.of(vs).mapToObj(v -> "v" + v).collect(Collectors.joining(", "));
    }

    @Override public String toString() {
        String outs = vars(outputs()), inner = vars(innerOutputs()), ins = vars(inputs());
        String head = outs.isEmpty() ? "" : outs + " <- ";
        String tail = inner.isEmpty() ? "" : " -> " + inner;
        return (head + op.repr() + " " + ins + tail).stripTrailing();
    }
}
