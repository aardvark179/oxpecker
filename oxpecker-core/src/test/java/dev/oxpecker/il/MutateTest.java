package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.gen.Engine;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.il.Mutate.Mutated;
import dev.oxpecker.il.Mutate.Rejected;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Mutators (tests/il/test_mutate.py; design §10 item 8): children differ from parents and verify,
 * fewer than 35 % of mutations are rejected, mutations are local (median share of the parent kept
 * >= 90 %), lineages shrink to the needed step; and what each oxpecker mutator does to the Rhino model.
 */
class MutateTest {
    static final GenOptions SMALL = GenOptions.builder().maxStatements(6).maxDepth(2).build();
    static final int SEEDS = 12;

    static Program parent(int seed) { return Strategies.generate(seed, SMALL).program(); }

    static List<String> mutatorNames() { return Mutate.mutators(); }

    /** difflib.SequenceMatcher(None, a, b).ratio() * (len(a)+len(b)) / (2 len(a)), with an LCS for the matches. */
    static double kept(Program parent, Program child) {
        List<String> a = parent.code().stream().map(i -> i.op().name()).toList();
        List<String> b = child.code().stream().map(i -> i.op().name()).toList();
        int[][] l = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--)
            for (int j = b.size() - 1; j >= 0; j--)
                l[i][j] = a.get(i).equals(b.get(j)) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
        return (double) l[0][0] / a.size();
    }

    @ParameterizedTest
    @MethodSource("mutatorNames")
    void eachMutatorChangesTheProgramAndTheChildVerifies(String name) {
        int changed = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Program p = parent(seed);
            Mutated m;
            try {
                m = Mutate.mutate(p, seed, name, 1);
            } catch (Rejected e) {
                continue;
            }
            assertEquals(name, m.name());
            assertNotEquals(Program.signature(p.code()), Program.signature(m.code()), name + " returned the parent unchanged");
            Analysis.verify(m.code(), m.program().options().strict());
            Lifter.lift(m.code(), LiftOptions.DEFAULT);
            for (int k = 0; k < m.code().size(); k++) assertEquals(k, m.code().get(k).index());
            assertTrue(m.code().stream().anyMatch(i -> i.step() == 1) || name.equals("activation"), "the changed instructions carry step=1");
            changed++;
        }
        assertTrue(changed > 0, name + " was never applicable on " + SEEDS + " programs");
    }

    @Test void rejectionRateIsBelow35Percent() {
        int total = 0, rejected = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Program p = parent(seed);
            for (String name : Mutate.mutators()) {
                total++;
                try {
                    Mutate.mutate(p, seed, name, 1);
                } catch (Rejected e) {
                    rejected++;
                }
            }
        }
        assertTrue((double) rejected / total < 0.35, rejected + "/" + total + " rejected");
    }

    @Test void mutationsAreLocal() {
        List<Double> kept = new ArrayList<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            Program p = parent(seed);
            for (String name : Mutate.mutators()) {
                if (name.equals("combine")) continue;           // appends a whole program by design
                try {
                    kept.add(kept(p, Mutate.mutate(p, seed, name, 1).program()));
                } catch (Rejected e) {
                    // not applicable here
                }
            }
        }
        kept.sort(null);
        int n = kept.size();
        double median = n % 2 == 1 ? kept.get(n / 2) : (kept.get(n / 2 - 1) + kept.get(n / 2)) / 2;
        assertTrue(median >= 0.9, "median " + median);
    }

    @Test void mutateIsDeterministicAndDiffShowsTheChange() {
        Program p = parent(2);
        int seed = 5;                                   // the first seed from 5 whose drawn mutator applies
        while (true) {
            try {
                Mutate.mutate(p, seed);
                break;
            } catch (Rejected e) {
                seed++;
            }
        }
        Mutated a = Mutate.mutate(p, seed), b = Mutate.mutate(p, seed);
        assertEquals(a.name(), b.name());
        assertEquals(Program.signature(a.code()), Program.signature(b.code()));
        assertTrue(a.diff().contains("parent") && (a.diff().contains("+") || a.diff().contains("-")));
    }

    @Test void declarationKindNeverMakesAnAssignedVariableConst() {
        Set<DeclarationKind> seen = new HashSet<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            Mutated m;
            try {
                m = Mutate.mutate(parent(seed), seed, "declaration_kind", 1);
            } catch (Rejected e) {
                continue;
            }
            Analysis an = Analysis.analyse(m.code());
            for (Instruction instr : m.code())
                if (instr.op() instanceof Ops.Declare dcl) {
                    seen.add(dcl.kind());
                    if (dcl.kind() == DeclarationKind.CONST)
                        assertTrue(an.assignments().getOrDefault(instr.output(), List.of()).size() <= 1);
                }
        }
        assertTrue(!seen.isEmpty(), "no Declare site was ever flipped");
    }

    static Map<List<ActivationReason>, Integer> histogram(RhinoModel r) {
        Map<List<ActivationReason>, Integer> h = new HashMap<>();
        for (List<ActivationReason> v : r.activation().values()) h.merge(v, 1, Integer::sum);
        return h;
    }

    @Test void activationFlipsTheModel() {
        int flipped = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Program p = parent(seed);
            Mutated m;
            try {
                m = Mutate.mutate(p, seed, "activation", 1);
            } catch (Rejected e) {
                continue;
            }
            assertNotEquals(histogram(Analysis.analyse(p.code()).rhino()), histogram(Analysis.analyse(m.code()).rhino()));
            flipped++;
        }
        assertTrue(flipped > 0);
    }

    @Test void numberFlipTurnsANumberVariableIntoAny() {
        int flipped = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Program p = parent(seed);
            RhinoModel before = Analysis.analyse(p.code()).rhino();
            if (before.numberVar().isEmpty()) continue;
            Mutated m;
            try {
                m = Mutate.mutate(p, seed, "number_flip", 1);
            } catch (Rejected e) {
                continue;
            }
            RhinoModel after = Analysis.analyse(m.code()).rhino();
            assertTrue(after.numberVar().size() < before.numberVar().size()
                    || m.code().stream().anyMatch(i -> i.gen().equals("number_flip")));
            flipped++;
        }
        assertTrue(flipped > 0);
    }

    @Test void insertionPointsSkipDeadCodeAndLiterals() {
        Builder b = new Builder();
        try (var f = b.plainFunction(new Parameters(0))) {
            b.doReturn(b.loadInt(1));
        }
        int v = b.loadInt(2);
        var lit = b.objectLiteral();
        try (lit) {
            lit.addProperty(Env.k("A"), v);
        }
        List<Instruction> code = b.finish();
        List<Integer> points = Mutate.insertionPoints(code, Analysis.analyse(code));
        int ret = -1, inside = -1;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).op() instanceof Ops.Return && ret < 0) ret = i;
            if (code.get(i).op() instanceof Ops.ObjectLiteralAddProperty && inside < 0) inside = i;
        }
        assertTrue(!points.contains(ret + 1));          // after the return: dead
        assertTrue(!points.contains(inside));           // inside the literal: no statements
        assertTrue(points.contains(0) && points.contains(code.size()));
    }

    @Test void lineagesVerifyAndLift() {
        for (int seed = 0; seed < 15; seed++) {
            var d = Engine.fresh(Mutate.evolved(SMALL, 3), seed);
            if (d == null) continue;
            Lineage l = d.value();
            for (Mutate.Step s : l.steps) {
                Analysis.verify(s.program().code(), l.options().strict());
                Lifter.lift(s.program().code(), LiftOptions.DEFAULT);
            }
            assertTrue(!l.toString().isEmpty());
        }
    }

    @Test void lineageShellApiIsDeterministic() {
        Lineage a = Mutate.lineage(4, 3, null, SMALL), b = Mutate.lineage(4, 3, null, SMALL);
        assertEquals(a.stepNames(), b.stepNames());
        assertEquals(Program.signature(a.code()), Program.signature(b.code()));
        assertTrue(a.describe().startsWith("<Lineage "));
    }

    /**
     * A property that needs one particular mutator shrinks to a lineage with that single step,
     * starting from the recorded choices the way the fuzz loop's shrinker does.
     */
    @Test void lineagesShrinkToTheNeededStep() {
        GenOptions o = GenOptions.builder().maxStatements(3).maxDepth(1).build();
        var strategy = Mutate.evolved(o, 3);
        Engine.Drawn<Lineage> found = null;
        for (int seed = 0; seed < 300 && found == null; seed++) {
            var d = Engine.fresh(strategy, seed);
            if (d != null && d.value().stepNames().contains("reassign")) found = d;
        }
        if (found == null) fail("no lineage with a reassign step in 300 seeds");
        Choices choices = found.choices();
        var s = Engine.shrink(strategy, choices, l -> l.stepNames().contains("reassign"), 400, l -> l.steps.size() * 10000 + l.code().size());
        assertNotNull(s);
        assertEquals(List.of("reassign"), s.value().stepNames());
        assertTrue(s.value().steps.size() <= found.value().steps.size());
    }

    @Test void mutationStrategyRejectsInsteadOfRaising() {
        for (int seed = 0; seed < 10; seed++) {
            var d = Engine.fresh(dd -> Mutate.mutation(dd, dd.draw(Strategies.programs(SMALL)).program(), null, 1), seed);
            if (d != null) Analysis.verify(d.value().code());
        }
    }

    @Test void mutatorWeightsLowerCombineAndCodegenAndLineagesCountPerMutator() {
        Map<String, Integer> w = Mutate.mutatorWeights();
        assertEquals(new HashSet<>(Mutate.mutators()), w.keySet());
        assertTrue(w.get("combine") < w.get("codegen") && w.get("codegen") < w.get("operation"));
        Map<String, Integer> attempted = new HashMap<>();
        GenOptions o = GenOptions.builder().maxStatements(3).maxDepth(1).build();
        for (int seed = 0; seed < 120; seed++) {
            Lineage l = Mutate.lineage(seed, 3, null, o);
            int att = l.attemptedBy.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(l.depth(), att);
            assertEquals(l.steps.size() + l.rejected, l.depth());
            assertEquals(l.rejected, l.rejectedBy.values().stream().mapToInt(Integer::intValue).sum());
            for (var e : l.rejectedBy.entrySet()) assertTrue(e.getValue() <= l.attemptedBy.getOrDefault(e.getKey(), 0));
            l.attemptedBy.forEach((k, n) -> attempted.merge(k, n, Integer::sum));
        }
        int total = attempted.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total >= 60, "total " + total);
        double share = (double) w.get("combine") / w.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue((double) attempted.getOrDefault("combine", 0) / total < 2.5 * share + 0.02, attempted.toString());
    }
}
