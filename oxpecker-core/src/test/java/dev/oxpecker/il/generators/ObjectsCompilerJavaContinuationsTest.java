package dev.oxpecker.il.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.oxpecker.il.Context;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Instruction;
import dev.oxpecker.il.Lifter;
import dev.oxpecker.il.LiftOptions;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Strategies;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The per-generator structure test of tests/il/test_generators.py for the objects, members,
 * properties, compiler-targeted, java-interop and continuations families: each generator run
 * alone after a small prefix verifies, lifts and emits the operations it is for.
 */
class ObjectsCompilerJavaContinuationsTest {
    static final int SEEDS = 30;
    static final GenOptions OPTIONS = GenOptions.builder().maxDepth(2).build();
    static final Map<String, Integer> FAMILY_SIZES = Map.of("objects", 11, "members", 6, "properties", 20,
            "compiler-targeted", 10, "java-interop", 8, "continuations", 5);

    record Must(List<Class<?>> all, List<Class<?>> some) {}

    static Must must(List<Class<?>> all, List<Class<?>> some) { return new Must(all, some); }

    /** MUST_EMIT of test_generators.py, the entries for these families. */
    static final Map<String, Must> MUST_EMIT = Map.ofEntries(
            Map.entry("object_literal", must(List.of(Ops.BeginObjectLiteral.class, Ops.EndObjectLiteral.class),
                    List.of(Ops.ObjectLiteralAddProperty.class))),
            Map.entry("property_assignment", must(List.of(Ops.SetProperty.class), List.of())),
            Map.entry("property_configuration", must(List.of(), List.of(Ops.ConfigureProperty.class))),
            Map.entry("destruct_array", must(List.of(Ops.Destruct.class), List.of())),
            Map.entry("proxy", must(List.of(Ops.Construct.class, Ops.BeginObjectLiteral.class), List.of())),
            Map.entry("super_property_retrieval", must(List.of(Ops.GetSuperProperty.class, Ops.BeginObjectLiteralMethod.class), List.of())),
            Map.entry("super_method_call", must(List.of(Ops.CallSuperMethod.class), List.of())),
            Map.entry("computed_property_update", must(List.of(Ops.UpdateComputedProperty.class), List.of())),
            Map.entry("prototype_overwrite", must(List.of(), List.of(Ops.SetProperty.class, Ops.CallMethod.class))),
            // compiler-targeted (design §7.7)
            Map.entry("numeric_local_type_flip", must(List.of(Ops.BeginPlainFunction.class, Ops.Declare.class, Ops.Return.class,
                    Ops.CallFunction.class, Ops.BeginTry.class), List.of(Ops.Reassign.class, Ops.Update.class, Ops.UnaryOperation.class))),
            Map.entry("activation_pair", must(List.of(Ops.BeginPlainFunction.class, Ops.EndPlainFunction.class),
                    List.of(Ops.LoadArguments.class, Ops.BeginArrowFunction.class, Ops.BeginCatch.class))),
            Map.entry("direct_call_arity", must(List.of(Ops.BeginPlainFunction.class, Ops.CallFunction.class), List.of(Ops.Reassign.class))),
            Map.entry("callsite_relink", must(List.of(Ops.BeginRepeatLoop.class, Ops.CallFunction.class), List.of())),
            Map.entry("property_site_shape_change", must(List.of(Ops.GetProperty.class, Ops.BeginRepeatLoop.class),
                    List.of(Ops.DeleteProperty.class, Ops.Reassign.class))),
            Map.entry("string_concat_chain", must(List.of(Ops.Update.class, Ops.BeginRepeatLoop.class), List.of(Ops.CallMethod.class))),
            Map.entry("int32_boundary_arith", must(List.of(Ops.Declare.class, Ops.BinaryOperation.class, Ops.Compare.class), List.of())),
            Map.entry("super_in_object_literal", must(List.of(Ops.ObjectLiteralSetPrototype.class, Ops.BeginObjectLiteralMethod.class),
                    List.of(Ops.GetSuperProperty.class, Ops.GetComputedSuperProperty.class))),
            Map.entry("const_site", must(List.of(Ops.Declare.class), List.of(Ops.BinaryOperation.class))),
            Map.entry("negative_zero", must(List.of(Ops.UnaryOperation.class, Ops.CallMethod.class), List.of())),
            // java-interop
            Map.entry("java_typed_call", must(List.of(Ops.CallMethod.class, Ops.BeginTry.class, Ops.LoadBuiltin.class), List.of())),
            Map.entry("java_echo", must(List.of(Ops.CallMethod.class), List.of())),
            Map.entry("java_echo_arith", must(List.of(Ops.CallMethod.class), List.of(Ops.BinaryOperation.class, Ops.UnaryOperation.class))),
            Map.entry("java_returned_value", must(List.of(Ops.CallMethod.class, Ops.BeginTry.class), List.of())),
            Map.entry("java_numeric_pipeline", must(List.of(Ops.BeginPlainFunction.class, Ops.CallMethod.class, Ops.UnaryOperation.class), List.of())),
            // continuations
            Map.entry("pause_in_function", must(List.of(Ops.BeginPlainFunction.class, Ops.CallFunction.class), List.of())),
            Map.entry("pause_in_loop", must(List.of(Ops.BeginRepeatLoop.class, Ops.CallFunction.class), List.of())),
            Map.entry("native_continuation", must(List.of(Ops.Construct.class), List.of())));

    static Stream<String> names() {
        return FAMILY_SIZES.keySet().stream().sorted().flatMap(f -> Strategies.generators(f).stream());
    }

    @Test void everyFamilyIsFullyRegistered() {
        for (var e : FAMILY_SIZES.entrySet())
            assertEquals(e.getValue(), Strategies.generators(e.getKey()).size(), e.getKey());
        for (String name : names().toList()) {
            var spec = Strategies.REGISTRY.get(name);
            assertFalse(spec.doc().isEmpty(), name);
        }
    }

    /** The members family runs only inside an object literal: across seeds, object_literal draws every member kind. */
    @Test void objectLiteralDrawsEveryMemberKind() {
        Set<Class<?>> seen = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            for (Instruction i : Strategies.runGenerator("object_literal", seed, 3, OPTIONS).emitted()) seen.add(i.op().getClass());
        }
        for (Class<?> c : List.of(Ops.ObjectLiteralAddProperty.class, Ops.ObjectLiteralAddElement.class,
                Ops.BeginObjectLiteralMethod.class, Ops.BeginObjectLiteralGetter.class, Ops.BeginObjectLiteralSetter.class,
                Ops.ObjectLiteralSetPrototype.class))
            assertTrue(seen.contains(c), "object_literal never emitted " + c.getSimpleName());
    }

    @ParameterizedTest @MethodSource("names")
    void generatorAloneVerifiesLiftsAndEmitsItsOperations(String name) {
        Must must = MUST_EMIT.getOrDefault(name, must(List.of(), List.of()));
        Set<Class<?>> seen = new HashSet<>();
        int draws = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Strategies.Snippet s;
            try {
                s = Strategies.runGenerator(name, seed, 3, OPTIONS);    // runGenerator verifies
            } catch (IllegalStateException e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("not applicable at top level") || msg.contains("the draw rejected")) continue;
                throw e;
            }
            List<Instruction> emitted = s.emitted();
            if (emitted.isEmpty()) continue;                // the generator declined its inputs
            draws++;
            Set<Class<?>> classes = new HashSet<>();
            for (Instruction i : emitted) classes.add(i.op().getClass());
            for (Class<?> c : must.all())
                assertTrue(classes.contains(c), name + " did not emit " + c.getSimpleName() + " (seed " + seed + "):\n" + s.js());
            seen.addAll(classes);
            String js = Lifter.lift(s.code(), new LiftOptions(false, true, false, 6));
            assertFalse(js.isEmpty(), name);
        }
        int context = Strategies.REGISTRY.get(name).context();
        assumeTrue(draws > 0 || context == Context.JAVASCRIPT, name + " needs a context the standalone harness does not open");
        assertTrue(draws > 0, name + " was never applicable");
        for (Class<?> c : must.some())
            assertTrue(seen.contains(c), name + " never emitted " + c.getSimpleName() + " in " + draws + " draws");
    }
}
