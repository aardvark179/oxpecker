package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.generators.Control;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The generation half of tests/il/test_shell_api.py and the cross-cutting checks of
 * tests/il/test_generators.py: seeded generation, a generator run alone, the registry listing,
 * typed operations only, loop labels.
 */
class GenerationShellTest {
    @Test void generateIsDeterministicFromASeed() {
        GenOptions o = GenOptions.builder().maxStatements(5).build();
        var a = Strategies.generate(7, o);
        var b = Strategies.generate(7, o);
        assertEquals(a.code(), b.code());
        assertEquals(a.source(), b.source());
        assertEquals(a.source(), a.toString());
        assertEquals(Program.dump(a.code()), a.program().toString());
    }

    @Test void runGeneratorAlone() {
        var s = Strategies.runGenerator("while_loop", 1);
        assertFalse(s.emitted().isEmpty());
        assertTrue(s.emitted().stream().anyMatch(i -> i.op().name().equals("BeginWhileLoopBody")));
        assertTrue(s.js().contains("while ("));
        assertEquals(s.js(), s.toString());
        var bin = Strategies.runGenerator("binary", 2).emitted();
        assertEquals("BinaryOperation", bin.get(bin.size() - 1).op().name());
    }

    @Test void registryListing() {
        assertTrue(Strategies.generators("operations").contains("binary"));
        assertTrue(Strategies.templates().contains("plain"));
        assertTrue(new HashSet<>(Strategies.generators("members")).containsAll(Set.of("literal_property", "literal_method")));
    }

    @Test void everyGeneratorHasADocAndAFamily() {
        Strategies.registered();
        for (var s : Strategies.REGISTRY.values()) {
            assertFalse(s.doc().isEmpty(), s.name());
            assertFalse(s.family().isEmpty(), s.name());
        }
    }

    @Test void optionsFromAShellAreForgiving() {
        assertTrue(Strategies.generate(1, GenOptions.builder().maxStatements(2).build()).code().size() > 0);
        assertEquals(2, GenOptions.builder().maxStatements(2).build().minStatements());
        assertEquals(3, GenOptions.DEFAULT.minStatements());
        var e = assertThrows(IllegalArgumentException.class, () -> GenOptions.builder().minStatements(3).maxStatements(2).build());
        assertTrue(e.getMessage().contains("max_statements=2 is below min_statements=3"));
    }

    @Test void generatedProgramsContainOnlyTypedOperations() {
        Set<Class<?>> allowed = new HashSet<>(Ops.ALL);
        for (int seed = 0; seed < 5; seed++)
            for (Instruction i : Strategies.generate(seed, GenOptions.builder().minStatements(2).maxStatements(6).build()).code())
                assertTrue(allowed.contains(i.op().getClass()));
    }

    /** A {@code break label} inside a loop may only name a loop (not a switch's or an if's label). */
    @Test void loopLabelsExcludeSwitchAndIfLabels() {
        Builder b = new Builder(GenOptions.builder().maxDepth(3).build());
        try (var r = b.repeat(3)) {
            int i = r.counter(), loopLabel = r.label();
            int cond = b.compare(i, b.loadInt(1), CompareOperator.EQUAL);
            try (var iff = b.if_(cond)) {
                assertEquals(List.of(loopLabel), Control.loopLabels(b));     // not the if's label
                int caseValue = b.loadBool(true);                           // loaded before the switch opens
                try (var sw = b.switch_(cond)) {
                    try (var c = sw.case_(caseValue)) {
                        try (var inner = b.repeat(2)) {
                            // outer labels are hidden inside a switch; the switch's own label is not a loop label
                            assertEquals(List.of(inner.label()), Control.loopLabels(b));
                            b.break_(inner.label());
                        }
                    }
                }
            }
        }
        Analysis.verify(b.finish());
    }
}
