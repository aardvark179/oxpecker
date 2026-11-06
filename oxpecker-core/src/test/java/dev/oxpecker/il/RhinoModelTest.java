package dev.oxpecker.il;

import static dev.oxpecker.il.Builder.fn;
import static dev.oxpecker.il.Type.NUMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * tests/il/test_rhino_model.py, the model half: for each body shape, the function the model
 * predicts optimizer-typed and its activation-forced copy, and what number_var and expr_type
 * say. The Rhino half ({@code test_rhino_agrees_with_the_model}) needs the harness client of
 * oxpecker-fuzz and is not ported here.
 */
class RhinoModelTest {
    record Shape(BiFunction<Builder, Integer, Integer> make, boolean numberVar, String observed) {}

    static final BinaryOperator BIT_OR = BinaryOperator.BIT_OR, ADD = BinaryOperator.ADD, MUL = BinaryOperator.MUL;

    static final Map<String, Shape> SHAPES = new TreeMap<>(Map.of(
            "literal", new Shape((b, p) -> b.loadInt(1), true, "double"),
            "bit_or", new Shape((b, p) -> b.binary(p, b.loadInt(0), BIT_OR), true, "double"),
            "bit_or_plus", new Shape((b, p) -> b.binary(b.binary(p, b.loadInt(0), BIT_OR), b.loadInt(1), ADD), true, "double"),
            "plus", new Shape((b, p) -> b.binary(p, b.loadInt(1), ADD), false, "mixed"),       // AnyType: p is a parameter
            "times", new Shape((b, p) -> b.binary(p, b.loadInt(2), MUL), true, "double"),       // MUL is NumberType whatever the operands
            "length", new Shape((b, p) -> b.getProperty(b.createIntArray(List.of(1L, 2L)), Env.k("LENGTH")), false, "same"),
            "length_or", new Shape((b, p) -> b.binary(b.getProperty(b.createIntArray(List.of(1L, 2L)), Env.k("LENGTH")), b.loadInt(0), BIT_OR),
                    true, "double"),
            "param", new Shape((b, p) -> p, false, "same")));

    static List<String> shapes() { return List.copyOf(SHAPES.keySet()); }

    record Pair(List<Instruction> code, int f, int g) {}

    /**
     * f: {@code var x = <shape>(p); __probe(..., x, ...); return x}; g: the same body behind an
     * {@code arguments} access, which forces an activation frame and keeps the optimizer off.
     */
    static Pair pair(String shape) {
        var make = SHAPES.get(shape).make();
        Builder b = new Builder();
        int f, g;
        try (var fb = b.plainFunction(new Parameters(1), fn().named(true).types(NUMBER))) {
            f = fb.f();
            int x = b.declare(make.apply(b, fb.arg(0)));
            b.probe(ProbeLabel.STEP, 1);
            b.doReturn(x);
        }
        try (var gb = b.plainFunction(new Parameters(1), fn().named(true).types(NUMBER))) {
            g = gb.f();
            b.declare(b.getProperty(b.arguments(), Env.k("LENGTH")));
            int y = b.declare(make.apply(b, gb.arg(0)));
            b.probe(ProbeLabel.STEP, 2);
            b.doReturn(y);
        }
        int one = b.loadInt(1);
        b.callFunction(f, List.of(one));
        b.callFunction(g, List.of(one));
        return new Pair(b.finish(), f, g);
    }

    static final Map<RhinoType, String> PREDICTION = Map.of(RhinoType.NUMBER, "double", RhinoType.ANY, "same", RhinoType.MIXED, "mixed");

    /** The {@code x} local of f: the Declare in f's body. */
    static int localX(Analysis an, int fIndex) {
        return new TreeMap<>(an.defs()).entrySet().stream()
                .filter(e -> an.functionOf().get(e.getValue()) == fIndex && e.getValue() > fIndex
                        && an.code().get(e.getValue()).op() instanceof Ops.Declare)
                .sorted(Map.Entry.comparingByValue()).findFirst().orElseThrow().getKey();
    }

    @ParameterizedTest @MethodSource("shapes")
    void modelPredictionsOnThePair(String shape) {
        Pair p = pair(shape);
        Analysis an = Analysis.analyse(p.code());
        int fIndex = an.defs().get(p.f()), gIndex = an.defs().get(p.g());
        assertTrue(an.rhino().optimizerRuns(fIndex));
        assertFalse(an.rhino().optimizerRuns(gIndex));
        assertEquals(List.of(ActivationReason.ARGUMENTS), an.rhino().activation().get(gIndex));
        int x = localX(an, fIndex);
        Shape s = SHAPES.get(shape);
        assertEquals(s.numberVar(), an.rhino().numberVar().contains(x));
        assertEquals(s.observed(), PREDICTION.get(an.rhino().exprType().get(an.defs().get(x))));
    }
}
