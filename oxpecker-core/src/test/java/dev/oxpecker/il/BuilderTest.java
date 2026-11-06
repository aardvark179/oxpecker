package dev.oxpecker.il;

import static dev.oxpecker.il.Builder.fn;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NUMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Ops.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** tests/il/test_builder.py: every block helper emits a balanced, checkable group; hiding; pools; at(); replay. */
class BuilderTest {
    static Key k(String attr) { return Env.k(attr); }

    @Test void simpleEmitsAndFinish() {
        Builder b = new Builder();
        int x = b.loadInt(1);
        int y = b.loadFloat(2.5);
        int z = b.binary(x, y, BinaryOperator.ADD);
        int n = b.declare(z);
        b.reassign(n, x);
        var code = b.finish();
        Analysis.verify(code);
        assertEquals(List.of("LoadInteger", "LoadFloat", "BinaryOperation", "Declare", "Reassign"), code.stream().map(i -> i.op().name()).toList());
        assertEquals(NUMBER, b.typeOf(z));
        assertEquals(INTEGER, b.typeOf(n));
        assertSame(Storage.TOP_VAR, b.storage.get(n));
        assertTrue(b.isAssignable(n));
        assertFalse(b.isAssignable(z));
    }

    @Test void emitChecksInputsAndContext() {
        Builder b = new Builder();
        int x = b.loadInt(1);
        assertThrows(BuilderError.class, () -> b.emit(new BinaryOperation(BinaryOperator.ADD), List.of(x)));
        assertThrows(BuilderError.class, () -> b.doReturn(x));                // not in a subroutine
        assertThrows(BuilderError.class, () -> b.emit(new TypeOf(), List.of(99)));
        assertThrows(BuilderError.class, () -> b.emit(new EndIf()));
    }

    @Test void allBlockHelpersProduceValidPrograms() {
        Builder b = new Builder();
        int one = b.loadInt(1);
        int f;
        try (var fb = b.plainFunction(new Parameters(2), fn().named(true).types(NUMBER, NUMBER))) {
            f = fb.f();
            int s = b.binary(fb.arg(0), fb.arg(1), BinaryOperator.ADD);
            try (var br = b.if_(b.compare(s, one, CompareOperator.GREATER))) {
                b.doReturn(s);
                try (var e = br.else_()) {
                    b.doReturn(one);
                }
            }
        }
        try (var g = b.arrowFunction(new Parameters(1))) {
            b.doReturn(g.arg(0));
        }
        try (var gen = b.generatorFunction()) {
            b.yield_(one);
            b.yieldEach(b.createArray(List.of(one)));
        }
        int key = b.loadString("k"), cm = b.loadString("cm"), cg = b.loadString("cg"), cs = b.loadString("cs");
        int spreadSrc = b.createArray();
        int proto = b.loadNull();
        var o = b.objectLiteral();                   // members only inside; keys are loaded before (FuzzIL's rule)
        try (o) {
            o.addProperty(k("A"), one);
            o.addElement(0, one);
            o.addComputed(key, one);
            try (var m = o.method(k("M"), new Parameters(1))) {
                b.doReturn(b.getSuperProperty(k("A")));
            }
            try (var m = o.getter(k("G"))) {
                b.doReturn(m.this_());
            }
            try (var m = o.setter(k("G"))) {
                b.setProperty(m.this_(), k("B"), m.value());
            }
            try (var m = o.computedMethod(cm)) {
                b.doReturn(m.this_());
            }
            try (var m = o.computedGetter(cg)) {
                b.doReturn(m.this_());
            }
            try (var m = o.computedSetter(cs)) {
                // empty
            }
            o.copyProperties(spreadSrc);
            o.setPrototype(proto);
            assertThrows(BuilderError.class, () -> b.loadInt(3));       // no statements inside an object literal
        }
        int obj = o.result();
        try (var loop = b.whileLoop()) {
            int c = b.compare(one, one, CompareOperator.LESS);
            try (var body = loop.body(c)) {
                b.break_(body.label());
                b.continue_();
            }
        }
        try (var loop = b.doWhileLoop()) {
            b.continue_(loop.label());
            try (var h = loop.header()) {
                h.done(b.loadBool(false));
            }
        }
        try (var loop = b.forLoop()) {
            int i0 = b.loadInt(0);
            int cond;
            try (var c = loop.condition(List.of(i0))) {
                cond = b.compare(c.var(0), one, CompareOperator.LESS);
            }
            try (var a = loop.afterthought(cond)) {
                b.unary(UnaryOperator.POST_INC, a.var(0));
            }
            try (var body = loop.body()) {
                b.update(body.var(0), one, BinaryOperator.ADD);
            }
        }
        int arr = b.createArray(List.of(one, one));
        try (var fe = b.forEach(ForEachKind.OF, arr)) {
            b.typeof(fe.var(0));
        }
        try (var fe = b.forEach(ForEachKind.IN, obj)) {
            b.getComputed(obj, fe.var(0));
        }
        try (var r = b.repeat(3)) {
            b.break_();
        }
        try (var t = b.try_()) {
            b.throw_(one);
            try (var e = t.catch_()) {
                b.typeof(e.var());
            }
            try (var fin = t.finally_()) {
                // empty
            }
        }
        try (var t = b.try_()) {
            try (var fin = t.finally_()) {
                // empty
            }
        }
        int two = b.loadInt(2);                       // case values are computed before the switch (FuzzIL's rule)
        try (var sw = b.switch_(one)) {
            try (var c = sw.case_(one)) {
                b.switchBreak();
            }
            try (var c = sw.case_(two, true)) {
                // empty
            }
            try (var c = sw.default_()) {
                // empty
            }
        }
        try (var blk = b.block()) {
            b.blockBreak(blk.label());
        }
        try (var w = b.with_(obj)) {
            b.getProperty(obj, k("A"));
        }
        var str = b.codeString();
        try (str) {
            b.loadInt(7);
        }
        b.callFunction(b.builtin(Env.builtin("EVAL")), List.of(str.result()));
        b.probe();
        var code = b.finish();
        Analysis an = Analysis.verify(code);
        assertTrue(an.types().get(f).is(FUNCTION));
        assertTrue(an.returns().get(f).is(NUMBER));
        assertEquals(0, b.context() & Context.METHOD);
        assertEquals(Context.JAVASCRIPT, b.context());
        assertSame(Storage.TEMP, an.storage().get(obj));
        assertTrue(an.types().get(obj).group().startsWith("_Object"));
    }

    @Test void framesEnforceTheirProtocol() {
        Builder b1 = new Builder();
        assertThrows(BuilderError.class, () -> { try (var w = b1.whileLoop()) { /* no body */ } });
        Builder b2 = new Builder();
        assertThrows(BuilderError.class, () -> { try (var t = b2.try_()) { /* no catch */ } });
        Builder b3 = new Builder();
        assertThrows(BuilderError.class, () -> {
            try (var loop = b3.forLoop()) {
                try (var body = loop.body()) { /* out of order */ }
            }
        });
        Builder b4 = new Builder();
        assertThrows(BuilderError.class, () -> {
            try (var o = b4.objectLiteral()) {
                o.setPrototype(b4.loadNull());
                o.setPrototype(b4.loadNull());
            }
        });
        Builder b5 = new Builder();
        assertThrows(BuilderError.class, () -> {
            try (var sw = b5.switch_(b5.loadInt(1))) {
                try (var d = sw.default_()) { /* first */ }
                try (var d = sw.default_()) { /* second */ }
            }
        });
    }

    @Test void functionVariableIsHiddenInItsBodyAndLabelsAreHiddenInSubroutines() {
        Builder b = new Builder();
        int label, f;
        try (var r = b.repeat(2)) {
            int i = r.counter();
            label = r.label();
            assertTrue(b.visible().contains(i));
            assertFalse(b.visible().contains(label));
            try (var fb = b.plainFunction()) {
                f = fb.f();
                assertFalse(b.visible().contains(f));            // no trivial recursion
                assertTrue(b.isHidden(label));                   // labels never cross a function boundary
                int l = label;
                assertThrows(BuilderError.class, () -> b.break_(l));   // and the context forbids it anyway
            }
            assertTrue(b.visible().contains(f));
            b.break_(label);
        }
        assertFalse(b.isHidden(label));
        assertFalse(b.inScope(label));
        try (var g = b.plainFunction(fn().recursive(true))) {
            assertTrue(b.visible().contains(g.f()));
        }
        b.finish();
    }

    @Test void poolsAndVisibilityOrder() {
        Builder b = new Builder();
        int s = b.loadString("a");
        int n = b.loadInt(1);
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int d = b.declare(n);
        assertEquals(List.of(d, f, n, s), b.visible());
        assertEquals(List.of(d), b.visible(true, null));
        assertEquals(List.of(d), b.visible(null, true));
        Builder.Pool p = b.pool(NUMBER);
        assertEquals(List.of(d, n), p.exact());
        assertEquals(List.of(d, f, n, s), p.all());
        assertEquals(List.of(f), b.pool(FUNCTION).exact());
        int u = b.callFunction(f, List.of(s));
        assertTrue(b.pool(NUMBER).exact().contains(u));          // parseInt returns a number
        try (var r = b.repeat(1)) {
            assertFalse(b.visible().contains(r.label()));
            assertTrue(b.visible().contains(r.counter()));
            assertFalse(b.isAssignable(r.counter()));
        }
        b.hide(s);
        assertFalse(b.visible().contains(s));
        b.unhide(s);
        assertTrue(b.visible().contains(s));
    }

    @Test void rhinoModelOnOpenCode() {
        Builder b = new Builder();
        int f;
        try (var fb = b.plainFunction(new Parameters(1), fn().named(true))) {
            f = fb.f();
            int n = b.declare(b.loadInt(1));
            b.reassign(n, b.binary(n, b.loadInt(2), BinaryOperator.MUL));
            assertTrue(b.rhino().numberVar().contains(n));       // computed while the function is still open
            b.reassign(n, b.loadString("s"));
            assertFalse(b.rhino().numberVar().contains(n));
        }
        try (var g = b.plainFunction()) {
            b.callFunction(f, List.of(b.loadInt(1)));
        }
        var code = b.finish();
        assertTrue(Analysis.analyse(code).rhino().directCallTarget().contains(f));
    }

    @Test void atAndReplay() {
        Builder b = new Builder();
        int x = b.loadInt(1);
        try (var fb = b.plainFunction(new Parameters(1))) {
            int y = b.binary(fb.arg(0), x, BinaryOperator.ADD);
            b.doReturn(y);
        }
        var code = b.finish();
        // insert a declaration before the function
        Builder b2 = Builder.at(code, 1);
        assertEquals(List.of(x), b2.visible());
        b2.declare(x);
        var nw = b2.newCode();
        int top = code.stream().flatMapToInt(i -> java.util.Arrays.stream(i.inouts())).max().orElse(-1);
        assertEquals("Declare", nw.get(0).op().name());
        assertEquals(List.of(x), Typer.ints(nw.get(0).inputs()));
        assertTrue(nw.get(0).output() >= top + 1);
        List<Instruction> spliced = new ArrayList<>(code.subList(0, 1));
        spliced.addAll(nw);
        spliced.addAll(code.subList(1, code.size()));
        Analysis.verify(Program.renumber(spliced));
        // replay the function twice
        Builder b3 = new Builder();
        int z = b3.loadInt(2);
        int start = b3.code.size();
        int g;
        try (var gb = b3.plainFunction(new Parameters(1))) {
            g = gb.f();
            b3.doReturn(b3.binary(gb.arg(0), z, BinaryOperator.MUL));
        }
        int stop = b3.code.size();
        Map<Integer, Integer> mapping = b3.replay(start, stop);
        var code3 = b3.finish();
        Analysis.verify(code3);
        assertNotEquals(g, mapping.get(g));
        assertEquals("EndPlainFunction", code3.get(code3.size() - 1).op().name());
        assertEquals(code3.get(start).op(), code3.get(stop).op());
        assertEquals(code3.get(start).gen(), code3.get(stop).gen());
    }

    @Test void strictnessIsTrackedPerFrame() {
        assertTrue(new Builder(GenOptions.builder().strict(true).build()).strict());
        Builder b = new Builder();
        assertFalse(b.strict());
        try (var f = b.plainFunction(fn().strict(true))) {
            assertTrue(b.strict());
            try (var a = b.arrowFunction()) {
                assertTrue(b.strict());
            }
        }
        assertFalse(b.strict());
    }

    @Test void liftIsRecordedOnTheInstruction() {
        Builder b = new Builder();
        Instruction i = b.emit(new LoadInteger(1), Lift.CONST);
        assertSame(Lift.CONST, i.lift());
        assertSame(Lift.CONST, b.code.get(0).lift());
    }

    /** Port-specific: a block closes once; the handle's close is idempotent, as leaving a with block is. */
    @Test void blocksCloseOnce() {
        Builder b = new Builder();
        var blk = b.block();
        blk.close();
        blk.close();
        assertEquals(2, b.finish().size());
    }
}
