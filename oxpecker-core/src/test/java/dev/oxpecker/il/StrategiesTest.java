package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Strategies.GeneratorSpec;
import dev.oxpecker.il.Strategies.Generated;
import dev.oxpecker.il.Strategies.Pools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Strategy plumbing (tests/il/test_strategies.py): fixed pools, context-independent picks, programs() verify, lifting choices. */
class StrategiesTest {
    static final int EXAMPLES = 30;

    @Test void registryIsPopulatedAndSortedSimplestFirst() {
        Strategies.registered();
        assertTrue(Strategies.REGISTRY.size() > 60 && Strategies.TEMPLATES.size() >= 4);
        Pools p = Strategies.pools(GenOptions.DEFAULT);
        assertEquals("integer", p.statements().get(0).name());
        List<Integer> families = p.statements().stream().map(s -> Strategies.FAMILY_ORDER.indexOf(s.family())).toList();
        List<Integer> sorted = new ArrayList<>(families);
        sorted.sort(null);
        assertEquals(sorted, families);
        assertFalse(p.members().isEmpty());
        assertTrue(p.members().stream().allMatch(s -> s.family().equals("members")));
        assertTrue(p.values().stream().allMatch(s -> s.family().equals("values")) && p.values().size() >= 8);
        for (Type t : List.of(Type.INTEGER, Type.NUMBER, Type.STRING, Type.OBJECT, Type.FUNCTION, Type.ANY))
            assertTrue(Strategies.VALUE_MAKERS.containsKey(t), t.toString());
    }

    @Test void poolIsFixedAcrossContextsAndWeightsAreRepetition() {
        Pools p = Strategies.pools(GenOptions.DEFAULT);
        assertSame(p.statements(), p.forContext(Context.JAVASCRIPT));
        assertSame(p.statements(), p.forContext(Context.JAVASCRIPT | Context.SUBROUTINE | Context.LOOP));
        assertSame(p.members(), p.forContext(Context.OBJECT_LITERAL));
        assertTrue(p.forContext(Context.SWITCH_BLOCK) == p.cases() || p.cases().isEmpty());
        Map<String, Integer> counts = new HashMap<>();
        for (GeneratorSpec s : p.statements()) counts.merge(s.name(), 1, Integer::sum);
        assertEquals(Strategies.REGISTRY.get("binary").weight(), counts.get("binary"));
        assertEquals(1, counts.get("void"));
        Pools half = Strategies.pools(GenOptions.builder().weights(Map.of("binary", 0.5)).build());
        assertEquals(Math.rint(Strategies.REGISTRY.get("binary").weight() * 0.5),
                half.statements().stream().filter(s -> s.name().equals("binary")).count());
        Pools none = Strategies.pools(GenOptions.builder().weights(Map.of("operations", 0.0)).build());
        assertTrue(none.statements().stream().noneMatch(s -> s.family().equals("operations")));
        Pools only = Strategies.pools(GenOptions.builder().families(Set.of("values")).build());
        assertTrue(only.statements().stream().allMatch(s -> s.family().equals("values")));
    }

    @Test void fallbackIsTheFirstApplicableEntry() {
        Builder b = new Builder();
        Pools p = Strategies.pools(GenOptions.DEFAULT);
        GeneratorSpec first = Strategies.fallback(p.statements(), b);
        assertNotNull(first);
        assertEquals("values", first.family());
        assertFalse(Strategies.REGISTRY.get("return").applicable(b));
        assertFalse(Strategies.REGISTRY.get("loop_break").applicable(b));
        assertTrue(Strategies.REGISTRY.get("super_property_retrieval").applicable(b));   // scaffolds its own context
        b.depth = 0;
        assertFalse(Strategies.REGISTRY.get("plain_function").applicable(b));             // no nesting budget left
    }

    @Test void pickAndMaybe() {
        List<Integer> xs = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        for (int seed = 0; seed < EXAMPLES; seed++) {
            assertTrue(xs.contains(Strategies.draw(d -> Strategies.pick(d, xs), seed)));
            Strategies.draw(d -> d.maybe(0.5), seed);
        }
    }

    @Test void variableDrawsOrMakesAValueOfTheType() {
        for (int seed = 0; seed < EXAMPLES; seed++) {
            Strategies.draw(d -> {
                Builder b = new Builder();
                int v = Strategies.variable(d, b, Type.STRING);
                assertTrue(b.typeOf(v).is(Type.STRING));
                int w = Strategies.variable(d, b, Type.FUNCTION);
                assertTrue(b.typeOf(w).mayBe(Type.FUNCTION));
                b.finish();
                return null;
            }, seed);
        }
    }

    @Test void programsVerifyAndLift() {
        for (int seed = 0; seed < EXAMPLES; seed++) {
            Generated gen = Strategies.generate(seed, GenOptions.DEFAULT);
            Analysis.verify(gen.code());
            assertTrue(gen.code().stream().filter(i -> i.op().outputs() == 1 && !i.op().isBlock()).allMatch(i -> i.lift() != null));
            assertTrue(Strategies.TEMPLATES.containsKey(gen.template()));
            assertTrue(gen.source().contains("__probe("));
            assertFalse(gen.plainSource().contains("__probe("));
        }
    }

    @Test void strictProgramsHaveNoSloppyConstructs() {
        GenOptions o = GenOptions.builder().strict(true).minStatements(1).maxStatements(4).build();
        for (int seed = 0; seed < EXAMPLES; seed++) {
            Generated gen = Strategies.generate(seed, o);
            assertTrue(gen.code().stream().noneMatch(i -> i.op() instanceof Ops.BeginWith));
            assertTrue(gen.source().startsWith("\"use strict\";"));
        }
    }

    @Test void togglesRemoveGenerators() {
        GenOptions o = GenOptions.builder().minStatements(1).maxStatements(3).toggles(Set.of()).build();
        Set<String> banned = Set.of("generator_function", "for_of_loop", "destruct_array", "destruct_object", "with_statement", "eval");
        for (int seed = 0; seed < EXAMPLES; seed++)
            for (Instruction i : Strategies.generate(seed, o).code()) assertFalse(banned.contains(i.gen()), i.gen());
    }

    @Test void liftingChoicesFillEveryValue() {
        Builder b = new Builder();
        b.loadInt(1);
        b.emit(new Ops.LoadInteger(2), Lift.CONST);
        List<Instruction> code = b.finish();
        List<Instruction> filled = Strategies.draw(d -> Strategies.liftingChoices(d, code), 1);
        assertNotNull(filled.get(0).lift());
        assertEquals(Lift.CONST, filled.get(1).lift());
    }

    @Test void provenanceIsStamped() {
        Generated gen = Strategies.generate(5, GenOptions.builder().minStatements(2).maxStatements(4).build());
        assertTrue(gen.code().stream().anyMatch(i -> !i.gen().isEmpty()));
        assertTrue(gen.code().stream().filter(i -> i.index() >= 0).allMatch(i -> !i.gen().isEmpty() || i.op() instanceof Ops.Probe));
    }

    @Test void generationIsDeterministicPerSeedAndShrinksFromRecordedChoices() {
        Generated a = Strategies.generate(11), b = Strategies.generate(11);
        assertEquals(a.source(), b.source());
        var drawn = dev.oxpecker.gen.Engine.fresh(Strategies.programs(GenOptions.DEFAULT), 11);
        assertEquals(a.source(), drawn.value().source());
        assertEquals(a.source(), dev.oxpecker.gen.Engine.replay(Strategies.programs(GenOptions.DEFAULT), drawn.choices()).source());
    }
}
