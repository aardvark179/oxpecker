package dev.oxpecker.il.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.oxpecker.il.Context;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Instruction;
import dev.oxpecker.il.LiftOptions;
import dev.oxpecker.il.Lifter;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Ops.Operation;
import dev.oxpecker.il.Strategies;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * tests/il/test_generators.py for the operations, control and calls families: each generator drawn
 * alone after a small prefix verifies, lifts, and emits the operations it is for (MUST_EMIT).
 */
class OpsControlCallsTest {
    static final int SEEDS = 30;
    static final GenOptions OPTIONS = GenOptions.builder().maxDepth(2).build();

    record Must(List<Class<? extends Operation>> all, List<Class<? extends Operation>> some) {}

    static Must must(List<Class<? extends Operation>> all, List<Class<? extends Operation>> some) { return new Must(all, some); }

    /** MUST_EMIT of test_generators.py, the entries for these three families. */
    static final Map<String, Must> MUST_EMIT = Map.ofEntries(
            Map.entry("unary", must(List.of(Ops.UnaryOperation.class), List.of())),
            Map.entry("binary", must(List.of(Ops.BinaryOperation.class), List.of())),
            Map.entry("compare", must(List.of(Ops.Compare.class), List.of())),
            Map.entry("update", must(List.of(Ops.Update.class), List.of())),
            Map.entry("reassignment", must(List.of(), List.of(Ops.Reassign.class))),
            Map.entry("type_test", must(List.of(Ops.TypeOf.class, Ops.Compare.class, Ops.LoadString.class), List.of())),
            Map.entry("number_computation", must(List.of(Ops.LoadBuiltin.class), List.of(Ops.BinaryOperation.class, Ops.CallMethod.class))),
            Map.entry("int32_coerce", must(List.of(), List.of(Ops.BinaryOperation.class))),
            Map.entry("if_else", must(List.of(Ops.BeginIf.class, Ops.EndIf.class), List.of(Ops.BeginElse.class))),
            Map.entry("while_loop", must(List.of(Ops.BeginWhileLoopHeader.class, Ops.BeginWhileLoopBody.class,
                    Ops.EndWhileLoop.class, Ops.Declare.class), List.of())),
            Map.entry("do_while_loop", must(List.of(Ops.BeginDoWhileLoopBody.class, Ops.EndDoWhileLoop.class), List.of())),
            Map.entry("simple_for_loop", must(List.of(Ops.BeginForLoopInitializer.class, Ops.BeginForLoopCondition.class,
                    Ops.BeginForLoopAfterthought.class, Ops.BeginForLoopBody.class, Ops.EndForLoop.class), List.of())),
            Map.entry("for_in_loop", must(List.of(Ops.BeginForEachLoop.class), List.of())),
            Map.entry("repeat_loop", must(List.of(Ops.BeginRepeatLoop.class, Ops.EndRepeatLoop.class), List.of())),
            Map.entry("try_catch", must(List.of(Ops.BeginTry.class, Ops.BeginCatch.class, Ops.TypeOf.class), List.of(Ops.ThrowException.class))),
            Map.entry("try_finally", must(List.of(Ops.BeginTry.class, Ops.BeginFinally.class), List.of())),
            Map.entry("switch", must(List.of(Ops.BeginSwitch.class, Ops.EndSwitch.class),
                    List.of(Ops.BeginSwitchCase.class, Ops.BeginSwitchDefaultCase.class))),
            Map.entry("eval", must(List.of(Ops.BeginCodeString.class, Ops.EndCodeString.class, Ops.CallFunction.class), List.of())),
            Map.entry("with_statement", must(List.of(Ops.BeginWith.class), List.of())),
            Map.entry("plain_function", must(List.of(Ops.BeginPlainFunction.class, Ops.CallFunction.class), List.of(Ops.Return.class))),
            Map.entry("generator_function", must(List.of(Ops.BeginGeneratorFunction.class), List.of(Ops.Yield.class, Ops.YieldEach.class))),
            Map.entry("function_call", must(List.of(Ops.CallFunction.class), List.of())),
            Map.entry("constructor_call", must(List.of(Ops.Construct.class), List.of())),
            // not in the Python table: what each of these is for
            Map.entry("ternary", must(List.of(Ops.Compare.class, Ops.TernaryOperation.class), List.of())),
            // alone after a prefix of temporaries only one assignable variable exists (it is declared by
            // the first draw and picked by every later one), so no Reassign: the same in the Python
            Map.entry("chained_reassignments", must(List.of(), List.of(Ops.Declare.class))),
            Map.entry("void", must(List.of(Ops.Void.class), List.of())),
            Map.entry("instance_of", must(List.of(Ops.TestInstanceOf.class), List.of())),
            Map.entry("in", must(List.of(Ops.TestIn.class), List.of())),
            Map.entry("template_string", must(List.of(Ops.CreateTemplateString.class), List.of())),
            Map.entry("concatenated_string", must(List.of(Ops.TernaryOperation.class, Ops.BinaryOperation.class), List.of())),
            Map.entry("number_conversion", must(List.of(), List.of(Ops.UnaryOperation.class, Ops.CallFunction.class))),
            Map.entry("number_to_string", must(List.of(), List.of(Ops.CallFunction.class, Ops.CallMethod.class))),
            Map.entry("compare_with_if_else", must(List.of(Ops.Compare.class, Ops.BeginIf.class, Ops.BeginElse.class, Ops.EndIf.class), List.of())),
            Map.entry("complex_for_loop", must(List.of(Ops.BeginForLoopCondition.class, Ops.BeginForLoopBody.class, Ops.EndForLoop.class), List.of())),
            Map.entry("for_of_loop", must(List.of(Ops.BeginForEachLoop.class, Ops.EndForEachLoop.class), List.of())),
            Map.entry("try_catch_finally", must(List.of(Ops.BeginTry.class, Ops.BeginCatch.class, Ops.BeginFinally.class), List.of())),
            Map.entry("throw", must(List.of(Ops.ThrowException.class), List.of())),
            Map.entry("conditional_throw", must(List.of(Ops.BeginIf.class, Ops.ThrowException.class), List.of())),
            Map.entry("block_statement", must(List.of(Ops.BeginBlockStatement.class, Ops.EndBlockStatement.class), List.of(Ops.BlockBreak.class))),
            Map.entry("strict_function", must(List.of(Ops.BeginPlainFunction.class, Ops.CallFunction.class), List.of())),
            Map.entry("arrow_function", must(List.of(Ops.BeginArrowFunction.class, Ops.EndArrowFunction.class), List.of())),
            Map.entry("method_call", must(List.of(Ops.CallMethod.class), List.of())),
            Map.entry("computed_method_call", must(List.of(Ops.CallComputedMethod.class), List.of())),
            Map.entry("this", must(List.of(Ops.LoadThis.class), List.of())),
            Map.entry("function_with_arguments_access", must(List.of(Ops.BeginPlainFunction.class, Ops.LoadArguments.class,
                    Ops.Return.class, Ops.CallFunction.class), List.of())),
            Map.entry("unbound_function_call", must(List.of(Ops.CallMethod.class), List.of())),
            Map.entry("api_method_call", must(List.of(Ops.CallMethod.class), List.of())),
            Map.entry("api_function_call", must(List.of(Ops.CallFunction.class), List.of())));

    static final Set<String> FAMILIES = Set.of("operations", "control", "calls", "api");

    static Stream<String> names() {
        Strategies.registered();
        return Strategies.REGISTRY.values().stream()
                .filter(s -> FAMILIES.contains(s.family()))
                .filter(s -> !s.family().equals("api") || s.name().startsWith("api_"))
                .map(Strategies.GeneratorSpec::name).sorted();
    }

    static boolean notApplicable(Throwable t) {
        for (; t != null; t = t.getCause())
            if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("not applicable at top level")) return true;
        return false;
    }

    @Test void everyGeneratorOfTheThreeFamiliesIsRegistered() {
        Set<String> expected = Set.of("unary", "binary", "ternary", "compare", "update", "reassignment",
                "chained_reassignments", "type_test", "void", "instance_of", "in", "number_computation", "int32_coerce",
                "template_string", "concatenated_string", "number_conversion", "number_to_string",
                "if_else", "compare_with_if_else", "while_loop", "do_while_loop", "simple_for_loop", "complex_for_loop",
                "for_in_loop", "for_of_loop", "repeat_loop", "loop_break", "loop_continue", "try_catch_finally",
                "try_catch", "try_finally", "throw", "conditional_throw", "block_statement", "switch", "switch_break",
                "with_statement", "eval",
                "plain_function", "strict_function", "arrow_function", "generator_function", "function_call",
                "constructor_call", "method_call", "computed_method_call", "return", "yield", "yield_each", "this",
                "arguments_access", "function_with_arguments_access", "new_target", "unbound_function_call",
                "api_method_call", "api_function_call");
        assertEquals(expected, names().collect(Collectors.toSet()));
        for (String n : expected) {
            var spec = Strategies.REGISTRY.get(n);
            assertFalse(spec.doc().isEmpty(), n);
        }
    }

    @ParameterizedTest @MethodSource("names")
    void generatorAloneVerifiesLiftsAndEmitsItsOperations(String name) {
        Must must = MUST_EMIT.getOrDefault(name, must(List.of(), List.of()));
        Set<Class<?>> seen = new HashSet<>();
        int draws = 0, inapplicable = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Strategies.Snippet s;
            try {
                // runGenerator verifies the program; the prefix is 2..4 values as in the Python harness
                s = Strategies.runGenerator(name, seed, 2 + (int) (seed % 3), OPTIONS);
            } catch (RuntimeException e) {
                if (notApplicable(e)) { inapplicable++; continue; }
                throw e;
            }
            List<Instruction> emitted = s.emitted();
            if (emitted.isEmpty()) continue;        // the generator declined its inputs
            draws++;
            Set<Class<?>> classes = emitted.stream().map(i -> i.op().getClass()).collect(Collectors.toSet());
            for (var cls : must.all())
                assertTrue(classes.contains(cls), () -> name + " did not emit " + cls.getSimpleName() + ":\n" + s.js());
            seen.addAll(classes);
            Lifter.lift(s.code(), LiftOptions.PLAIN);
        }
        var spec = Strategies.REGISTRY.get(name);
        if (draws == 0 && spec.context() != Context.JAVASCRIPT) {
            assertEquals(SEEDS, inapplicable, name);
            assumeTrue(false, name + " needs a context the standalone harness does not open");
        }
        assertTrue(draws > 0, name + " was never applicable");
        for (var cls : must.some())
            assertTrue(seen.contains(cls), name + " never emitted " + cls.getSimpleName() + " in " + draws + " draws");
    }

    @Test void loopBreakAndContinueAreReachedInsideLoops() {
        // the context generators run inside the blocks of the others: draw whole programs and count them
        // (only these families, so the other families' weights do not dilute them)
        GenOptions only = GenOptions.builder().families(Set.of("values", "operations", "control", "calls")).build();
        Set<String> gens = new HashSet<>();
        for (long seed = 0; seed < 300; seed++)
            for (Instruction i : Strategies.generate(seed, only).code()) gens.add(i.gen());
        for (String n : List.of("loop_break", "loop_continue", "return"))
            assertTrue(gens.contains(n), n + " never ran in 300 programs");
    }
}
