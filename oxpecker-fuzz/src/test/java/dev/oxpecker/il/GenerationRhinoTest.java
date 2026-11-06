package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.gen.Engine;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.il.Mutate.Lineage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the generation layer produces, run through Rhino (tests/il/test_lift_rhino.py,
 * test_completion_rhino.py and test_rhino_model.py's agreement test). Needs a built harness.
 *
 * <pre>
 * OXPECKER_HARNESS=rhino1.9 ./gradlew :oxpecker-fuzz:test -Pslow --tests 'dev.oxpecker.il.GenerationRhinoTest'
 * OXPECKER_EXAMPLES=500 ...      more programs per property
 * </pre>
 */
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenerationRhinoTest {
    static final int EXAMPLES = Integer.parseInt(System.getenv().getOrDefault("OXPECKER_EXAMPLES", "100"));
    static final int COMPLETION_EXAMPLES = Integer.parseInt(System.getenv().getOrDefault("OXPECKER_EXAMPLES", "40"));
    Ensemble harness;

    @BeforeAll void start() {
        Assumptions.assumeTrue(java.nio.file.Files.exists(dev.oxpecker.Oxpecker.harnessBin()), "no harness launcher");
        harness = Ensemble.fromModes(false, true).start();
    }

    @AfterAll void stop() { if (harness != null) harness.stop(); }

    Outcome interpreted(String source, boolean strict) {
        RunResult run = harness.run(source, RunOptions.DEFAULT.withModes("interpreted").withStrict(strict).withSeed(1).withWallMs(3000));
        return run.results().values().iterator().next();
    }

    // ------------------------------------------------------------------- everything lifted parses
    void assertParses(String source, boolean strict) {
        Outcome o = interpreted(source, strict);
        if ("syntax".equals(o.status)) throw new AssertionError("Rhino rejects the lifted program: " + o.thrown + "\n" + source);
        if ("crash".equals(o.status) && String.valueOf(o.engineException).contains("Unexpected node type"))
            throw new AssertionError("Rhino's compiler crashed on:\n" + source);
    }

    @Test void liftedProgramsParse() {
        for (int seed = 0; seed < EXAMPLES; seed++) {
            var gen = Strategies.generate(seed, GenOptions.DEFAULT);
            assertParses(gen.source(), false);             // with probes and comments
            assertParses(gen.plainSource(), false);        // the plain reproducer
        }
    }

    @Test void strictProgramsParseAsStrictScripts() {
        GenOptions o = GenOptions.builder().strict(true).build();
        for (int seed = 0; seed < EXAMPLES; seed++) assertParses(Strategies.generate(seed, o).source(), true);
    }

    @Test void deeplyNestedProgramsParse() {
        GenOptions o = GenOptions.builder().maxDepth(4).maxStatements(16).build();
        for (int seed = 0; seed < EXAMPLES; seed++) assertParses(Strategies.generate(seed, o).plainSource(), false);
    }

    @Test void mutatedProgramsParse() {
        GenOptions o = GenOptions.builder().maxStatements(8).build();
        for (int seed = 0; seed < EXAMPLES; seed++) {
            var d = Engine.fresh(Mutate.evolved(o, 4), seed);
            if (d == null) continue;
            Lineage l = d.value();
            assertParses(l.source(), false);
            assertParses(l.plainSource(), false);
        }
    }

    @Test void theCheckRejectsInvalidJavascript() {
        for (String bad : List.of("var x = ;", "{}.m();", "var 1x = 2;"))
            assertTrue(assertThrows(AssertionError.class, () -> assertParses(bad, false)).getMessage().contains("Rhino rejects"));
        assertParses("({}).m;", false);
    }

    // ------------------------------------------------------------------- completion rate per family
    /** Floors from the Python measurements (test_completion_rhino.py); a drop below is a regression. */
    static final Map<String, Double> FLOORS = Map.of("all", 0.50, "values", 0.90, "operations", 0.75, "calls", 0.60,
            "control", 0.75, "compiler-targeted", 0.80, "java-interop", 0.85, "continuations", 0.70, "objects", 0.85);

    List<String> families() {
        Strategies.registered();
        Set<String> present = new java.util.HashSet<>();
        for (var s : Strategies.REGISTRY.values())
            if (s.context() == Context.JAVASCRIPT && s.inputs().isEmpty() && s.required().isEmpty()) present.add(s.family());
        return Strategies.FAMILY_ORDER.stream().filter(f -> present.contains(f) && !f.equals("members") && !f.equals("cases")).toList();
    }

    double rate(GenOptions options, int n, int seed0, List<String> bugs, Map<String, Integer> statuses, Map<String, Integer> thrown) {
        for (int k = 0; k < n; k++) {
            GenOptions o = options.toBuilder().probeEvery(0).build();
            var gen = Strategies.generate(seed0 + k, o);
            Outcome out = interpreted(gen.plainSource(), false);
            statuses.merge(out.status, 1, Integer::sum);
            if ("throw".equals(out.status) && out.thrown != null) {
                String m = String.valueOf(out.thrown.get("message"));
                thrown.merge(out.thrown.get("name") + ": " + m.substring(0, Math.min(70, m.length())), 1, Integer::sum);
            }
            String where = "generate(" + (seed0 + k) + ", families=" + o.families() + ", max_statements=" + o.maxStatements() + ")";
            if ("syntax".equals(out.status)) bugs.add(where + ": syntax " + out.thrown + "\n" + gen.plainSource());
            else if ("crash".equals(out.status) && !"jvm-died".equals(out.reason))
                System.out.println("\n  engine crash (a Rhino finding): " + where + ": " + out.engineException);
        }
        return (double) statuses.getOrDefault("ok", 0) / n;
    }

    @Test void completionRatePerFamily() {
        Map<String, Double> rates = new LinkedHashMap<>();
        List<String> bugs = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        for (String family : families()) {
            Map<String, Integer> st = new TreeMap<>(), th = new LinkedHashMap<>();
            GenOptions o = GenOptions.builder().maxStatements(8).families(new java.util.TreeSet<>(List.of(family, "values"))).build();
            double r = rate(o, COMPLETION_EXAMPLES, 1000, bugs, st, th);
            rates.put(family, r);
            table.append(String.format("  %-20s ok %4.0f%%   %s%n", family, r * 100, st));
        }
        Map<String, Integer> st = new TreeMap<>(), th = new LinkedHashMap<>();
        double all = rate(GenOptions.builder().maxStatements(10).build(), COMPLETION_EXAMPLES, 2000, bugs, st, th);
        rates.put("all", all);
        table.append(String.format("  %-20s ok %4.0f%%   %s%n", "all", all * 100, st));
        System.out.println("\n" + table);
        assertTrue(bugs.isEmpty(), "syntax errors (generator/lifter bugs):\n\n" + String.join("\n\n", bugs));
        List<String> low = new ArrayList<>();
        rates.forEach((f, r) -> { if (r < FLOORS.getOrDefault(f, 0.0)) low.add(f + " " + Math.round(r * 100) + "% < " + Math.round(FLOORS.get(f) * 100) + "%"); });
        assertTrue(low.isEmpty(), "completion rate below the floor: " + low + "\n" + table);
    }

    // ------------------------------------------------------------------- the Rhino model agrees with Rhino
    record Shape(BiFunction<Builder, Integer, Integer> make, boolean numberVar, String observed) {}

    static final Map<String, Shape> SHAPES = new TreeMap<>(Map.of(
            "literal", new Shape((b, p) -> b.loadInt(1), true, "double"),
            "bit_or", new Shape((b, p) -> b.binary(p, b.loadInt(0), BinaryOperator.BIT_OR), true, "double"),
            "bit_or_plus", new Shape((b, p) -> b.binary(b.binary(p, b.loadInt(0), BinaryOperator.BIT_OR), b.loadInt(1), BinaryOperator.ADD), true, "double"),
            "plus", new Shape((b, p) -> b.binary(p, b.loadInt(1), BinaryOperator.ADD), false, "mixed"),
            "times", new Shape((b, p) -> b.binary(p, b.loadInt(2), BinaryOperator.MUL), true, "double"),
            "length", new Shape((b, p) -> b.getProperty(b.createIntArray(List.of(1L, 2L)), Env.k("LENGTH")), false, "same"),
            "length_or", new Shape((b, p) -> b.binary(b.getProperty(b.createIntArray(List.of(1L, 2L)), Env.k("LENGTH")), b.loadInt(0),
                    BinaryOperator.BIT_OR), true, "double"),
            "param", new Shape((b, p) -> p, false, "same")));

    static List<String> shapes() { return List.copyOf(SHAPES.keySet()); }

    /** f: {@code var x = <shape>(p); probe; return x}; g: the same behind an {@code arguments} access (an activation frame). */
    static List<Instruction> pair(String shape) {
        var make = SHAPES.get(shape).make();
        Builder b = new Builder();
        int f, g;
        try (var fb = b.plainFunction(new Parameters(1), Builder.fn().named(true).types(Type.NUMBER))) {
            f = fb.f();
            int x = b.declare(make.apply(b, fb.arg(0)));
            b.probe(ProbeLabel.STEP, 1);
            b.doReturn(x);
        }
        try (var gb = b.plainFunction(new Parameters(1), Builder.fn().named(true).types(Type.NUMBER))) {
            g = gb.f();
            b.declare(b.getProperty(b.arguments(), Env.k("LENGTH")));
            int y = b.declare(make.apply(b, gb.arg(0)));
            b.probe(ProbeLabel.STEP, 2);
            b.doReturn(y);
        }
        int one = b.loadInt(1);
        b.callFunction(f, List.of(one));
        b.callFunction(g, List.of(one));
        return b.finish();
    }

    /** The Java class of x in f's probe and of y in g's probe (arg1 of each probe's type list). */
    static String[] probedTypes(Outcome o) {
        assertEquals(2, o.traceJavaTypes.size(), o.trace + " " + o.traceJavaTypes);
        return new String[]{o.traceJavaTypes.get(0).split(" \\| ")[1], o.traceJavaTypes.get(1).split(" \\| ")[1]};
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void rhinoAgreesWithTheModel(String shape) {
        String source = Lifter.js(pair(shape), true, false, false, 6);
        RunResult run = harness.run(source, RunOptions.DEFAULT.withModes("interpreted", "compiled").withWallMs(3000));
        Outcome interp = null, comp = null;
        for (Outcome o : run.results().values()) {
            if ("interpreted".equals(o.mode)) interp = o;
            if ("compiled".equals(o.mode)) comp = o;
        }
        assertEquals("ok", interp.status, String.valueOf(interp.thrown));
        assertEquals("ok", comp.status, String.valueOf(comp.thrown));
        assertTrue(Boolean.TRUE.equals(comp.compiledActually));
        String[] fi = probedTypes(interp), fc = probedTypes(comp);
        System.out.printf("%n  %-12s interpreted f=%s g=%s   compiled f=%s g=%s%n", shape, fi[0], fi[1], fc[0], fc[1]);
        assertEquals(fi[0], fi[1]);
        Shape s = SHAPES.get(shape);
        if (s.numberVar()) {
            assertEquals("double", s.observed());
            assertEquals("Double", fc[0]);
        } else if (s.observed().equals("same")) {
            assertEquals(fc[0], fc[1]);
        } else {
            assertEquals("Double", fc[0]);
            assertEquals("Integer", fc[1]);
            assertNotEquals(fc[0], fc[1]);
        }
    }
}
