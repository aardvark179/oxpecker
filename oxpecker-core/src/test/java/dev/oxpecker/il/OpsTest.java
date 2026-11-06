package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Ops.*;
import dev.oxpecker.il.Patterns.ArrayElement;
import dev.oxpecker.il.Patterns.ArrayPattern;
import dev.oxpecker.il.Patterns.Binding;
import dev.oxpecker.il.Patterns.ComputedTarget;
import dev.oxpecker.il.Patterns.ElementTarget;
import dev.oxpecker.il.Patterns.ObjectPattern;
import dev.oxpecker.il.Patterns.ObjectProperty;
import dev.oxpecker.il.Patterns.PropertyTarget;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** tests/il/test_ops.py: attributes, arities, block matching, and guard test 1 (no text-carrying operation). */
class OpsTest {
    /** Guard test 1 (design §10 item 1): the only string-typed fields in the operation set. */
    static final Set<String> ALLOWED_TEXT_FIELDS = Set.of("LoadString.value", "LoadRegExp.pattern", "CreateTemplateString.parts");

    static boolean isStringType(RecordComponent c) {
        if (c.getType() == String.class) return true;
        return c.getGenericType() instanceof ParameterizedType pt && List.class.isAssignableFrom(c.getType())
                && pt.getActualTypeArguments()[0] == String.class;
    }

    @Test void noTextCarryingOperationExists() {
        Set<String> found = new HashSet<>();
        for (Class<?> cls : Ops.ALL)
            for (RecordComponent c : cls.getRecordComponents()) if (isStringType(c)) found.add(cls.getSimpleName() + "." + c.getName());
        assertEquals(ALLOWED_TEXT_FIELDS, found);
    }

    @Test void noEvalOperation() {
        Set<String> names = new HashSet<>();
        for (Class<?> c : Ops.ALL) names.add(c.getSimpleName());
        assertFalse(names.contains("Eval") || names.contains("Print") || names.contains("Fixup"));
    }

    @Test void everyOperationIsARecordWithConsistentCounts() throws Exception {
        for (Class<? extends Operation> cls : Ops.ALL) {
            assertTrue(cls.isRecord(), cls.getSimpleName());
            Spec s = (Spec) cls.getDeclaredField("SPEC").get(null);
            if (s.firstVariadic() >= 0) assertTrue((s.attrs() & Attr.VARIADIC) != 0, cls.getSimpleName());
            if ((s.attrs() & Attr.VARIADIC) != 0) assertTrue(s.firstVariadic() >= 0, cls.getSimpleName());
            if (s.opened() != 0) assertTrue((s.attrs() & Attr.BLOCK_START) != 0, cls.getSimpleName());
            if ((s.attrs() & (Attr.PROPAGATES_CONTEXT | Attr.RESUMES_CONTEXT)) != 0) assertTrue((s.attrs() & Attr.BLOCK_START) != 0, cls.getSimpleName());
        }
    }

    @Test void everyBlockStartHasAnEndAndViceVersa() throws Exception {
        Set<Class<?>> starts = new HashSet<>(), ends = new HashSet<>();
        for (Class<? extends Operation> cls : Ops.ALL) {
            Spec s = (Spec) cls.getDeclaredField("SPEC").get(null);
            if ((s.attrs() & Attr.BLOCK_START) != 0) starts.add(cls);
            if ((s.attrs() & Attr.BLOCK_END) != 0) ends.add(cls);
        }
        assertEquals(starts, new HashSet<>(Ops.END_OF.keySet()));
        Set<Class<?>> closedBy = new HashSet<>();
        Ops.END_OF.values().forEach(closedBy::addAll);
        assertEquals(ends, closedBy);
        assertTrue(Ops.matchesEnd(new BeginIf(), new BeginElse()) && Ops.matchesEnd(new BeginIf(), new EndIf()));
        assertFalse(Ops.matchesEnd(new BeginIf(), new EndWhileLoop()));
    }

    @Test void literalsHoldValuesNotSpellings() {
        assertEquals(5, new LoadInteger(5).value());
        assertThrows(IllegalArgumentException.class, () -> new LoadInteger(-1));
        assertThrows(IllegalArgumentException.class, () -> new LoadFloat(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new LoadFloat(-0.0));
        assertThrows(IllegalArgumentException.class, () -> new LoadInteger(1L << 54));
        assertEquals(0.0, new LoadFloat(0.0).value());
    }

    @Test void parametersAndFunctionArities() {
        Parameters p = new Parameters(3, true, List.of(0), List.of());
        BeginPlainFunction f = new BeginPlainFunction(p);
        assertEquals(1, f.inputs());
        assertEquals(1, f.outputs());
        assertEquals(3, f.innerOutputs());
        assertThrows(IllegalArgumentException.class, () -> new Parameters(2, true, List.of(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Parameters(2, false, List.of(1, 0), List.of()));
        BeginObjectLiteralMethod m = new BeginObjectLiteralMethod(new Key("m"), new Parameters(2), List.of(), true);
        assertEquals(3, m.innerOutputs());                       // this + 2 params
        assertTrue((m.opened() & Context.GENERATOR_FUNCTION) != 0 && (m.opened() & Context.METHOD) != 0);
        assertEquals(0, new BeginObjectLiteralMethod(new Key("m")).opened() & Context.GENERATOR_FUNCTION);
    }

    @Test void variadicArities() {
        CallMethod c = new CallMethod(null, 3);
        assertEquals(4, c.inputs());
        assertEquals(1, c.firstVariadic());
        assertEquals(2, new CreateTemplateString(List.of("a", "b", "c")).inputs());
        assertThrows(IllegalArgumentException.class, () -> new CreateTemplateString(List.of()));
        assertEquals(3, new ConfigureProperty(new Key("k"), PropertyFlag.of(PropertyFlag.WRITABLE), PropertyType.GETTER_SETTER).inputs());
        assertEquals(2, new ConfigureProperty(new Key("k"), PropertyFlag.NONE, PropertyType.VALUE).inputs());
    }

    @Test void destructuringCounts() {
        var pat = new ObjectPattern(List.of(
                new ObjectProperty(new Key("a"), new Binding()),
                new ObjectProperty(null, new Binding(), true),                          // computed key + default: 2 extra inputs
                new ObjectProperty(new Key("b"), new PropertyTarget(new Key("x"))),       // member target: 1 extra input, 0 bindings
                new ObjectProperty(new Key("c"), new ArrayPattern(List.of(new ArrayElement(new Binding()), new ArrayElement(null),
                        new ArrayElement(new ComputedTarget()))))), true);
        assertEquals(1 + 1 + 0 + 1 + 1, Patterns.numBindings(pat));
        assertEquals(2 + 1 + 2, Patterns.numExtraInputs(pat));
        Destruct d = new Destruct(pat);
        assertEquals(1 + 5, d.inputs());
        assertEquals(4, d.outputs());
        assertEquals(1 + 4 + 5, new DestructAndReassign(pat).inputs());
        var arr = new ArrayPattern(List.of(new ArrayElement(new Binding(), true), new ArrayElement(new ElementTarget(0))), new Binding());
        assertEquals(2, Patterns.numBindings(arr));
        assertEquals(2, Patterns.numExtraInputs(arr));
    }

    @Test void forEachHeader() {
        BeginForEachLoop plain = new BeginForEachLoop(ForEachKind.OF);
        assertEquals(1, plain.inputs());
        assertEquals(2, plain.innerOutputs());                   // variable + label
        var pat = new ArrayPattern(List.of(new ArrayElement(new Binding()), new ArrayElement(new Binding())));
        assertEquals(3, new BeginForEachLoop(ForEachKind.OF, pat).innerOutputs());
        assertThrows(IllegalArgumentException.class, () -> new BeginForEachLoop(ForEachKind.IN, pat));
    }

    @Test void unaryOperatorGroups() {
        assertTrue(UnaryOperator.POST_INC.reassignsInput() && UnaryOperator.POST_INC.isPostfix());
        assertFalse(UnaryOperator.MINUS.reassignsInput());
        Set<BinaryOperator> all = EnumSet.noneOf(BinaryOperator.class);
        for (Set<BinaryOperator> g : BinaryOperator.OPERATOR_GROUPS) {
            for (BinaryOperator op : g) assertTrue(all.add(op), "groups overlap at " + op);
        }
        assertEquals(EnumSet.allOf(BinaryOperator.class), all);
        Set<CompareOperator> cmp = EnumSet.copyOf(CompareOperator.EQUALITY);
        cmp.addAll(CompareOperator.RELATIONAL);
        assertEquals(EnumSet.allOf(CompareOperator.class), cmp);
    }

    @Test void operationClassesAreManyAndNamedAfterFuzzil() {
        Set<String> names = new HashSet<>();
        for (Class<?> c : Ops.ALL) names.add(c.getSimpleName());
        for (String expected : List.of("LoadInteger", "BeginObjectLiteral", "EndObjectLiteral", "BeginPlainFunction", "CallMethod",
                "BeginForLoopCondition", "BeginSwitch", "Destruct", "BeginCodeString", "Probe", "Declare", "LoadBuiltin"))
            assertTrue(names.contains(expected), expected);
        assertTrue(names.size() > 100);
    }

    @Test void operationsAreHashableAndComparable() {
        assertEquals(new LoadInteger(1), new LoadInteger(1));
        assertEquals(new LoadInteger(1).hashCode(), new LoadInteger(1).hashCode());
        assertNotEquals(new LoadInteger(1), new LoadInteger(2));
    }
}
