package dev.oxpecker.il;

import static dev.oxpecker.il.Builder.fn;
import static dev.oxpecker.il.Type.NUMBER;

import dev.oxpecker.il.Patterns.ArrayElement;
import dev.oxpecker.il.Patterns.ArrayPattern;
import dev.oxpecker.il.Patterns.Binding;
import dev.oxpecker.il.Patterns.ComputedTarget;
import dev.oxpecker.il.Patterns.ElementTarget;
import dev.oxpecker.il.Patterns.ObjectPattern;
import dev.oxpecker.il.Patterns.ObjectProperty;
import dev.oxpecker.il.Patterns.PropertyTarget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The builder sequences of the Python port's scripts/parity/export_programs.py {@code scenarios()} (those of
 * tests/il/test_builder.py and test_lift.py, plus the operations the generator never emits),
 * written against the Java Builder line for line.
 */
final class Scenarios {
    private Scenarios() {}

    static Key k(String attr) { return Env.k(attr); }

    static final Map<String, Consumer<Builder>> ALL = new LinkedHashMap<>();
    static {
        ALL.put("all_blocks", Scenarios::allBlocks);
        ALL.put("replayed", Scenarios::replayed);
        ALL.put("functions", Scenarios::functions);
        ALL.put("objects", Scenarios::objects);
        ALL.put("control", Scenarios::control);
        ALL.put("destructuring", Scenarios::destructuring);
        ALL.put("probes", Scenarios::probes);
        ALL.put("code_strings", Scenarios::codeStrings);
        ALL.put("configure", Scenarios::configure);
        ALL.put("spellings", Scenarios::spellings);
        ALL.put("rare", Scenarios::rare);
    }

    static void allBlocks(Builder b) {
        int one = b.loadInt(1);
        try (var fb = b.plainFunction(new Parameters(2), fn().named(true).types(NUMBER, NUMBER))) {
            int p = fb.arg(0), q = fb.arg(1);
            int s = b.binary(p, q, BinaryOperator.ADD);
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
        int k = b.loadString("k"), cm = b.loadString("cm"), cg = b.loadString("cg"), cs = b.loadString("cs");
        int spreadSrc = b.createArray();
        int proto = b.loadNull();
        var o = b.objectLiteral();
        try (o) {
            o.addProperty(k("A"), one);
            o.addElement(0, one);
            o.addComputed(k, one);
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
            try {
                b.loadInt(3);
            } catch (BuilderError e) {
                // no statements inside an object literal
            }
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
            try (var f = t.finally_()) {
                // empty
            }
        }
        try (var t = b.try_()) {
            try (var f = t.finally_()) {
                // empty
            }
        }
        int two = b.loadInt(2);
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
        int result;
        try (var code = b.codeString()) {
            b.loadInt(7);
            result = code.result();
        }
        b.callFunction(b.builtin(Env.builtin("EVAL")), List.of(result));
        b.probe();
    }

    static void replayed(Builder b) {
        int z = b.loadInt(2);
        int start = b.code.size();
        try (var g = b.plainFunction(new Parameters(1))) {
            b.doReturn(b.binary(g.arg(0), z, BinaryOperator.MUL));
        }
        int stop = b.code.size();
        b.replay(start, stop);
    }

    static void functions(Builder b) {
        int f;
        try (var fb = b.plainFunction(new Parameters(2, true), fn().named(true))) {
            f = fb.f();
            b.doReturn(b.getProperty(fb.arg(1), k("LENGTH")));
        }
        int seven = b.loadInt(7);
        try (var g = b.plainFunction(new Parameters(1, false, List.of(0), List.of()), fn().defaults(seven))) {
            try (var h = b.plainFunction(fn().named(true))) {
                b.doReturn();
            }
            try (var br = b.if_(b.loadBool(true))) {
                try (var kk = b.plainFunction(fn().named(true))) {
                    // empty
                }
            }
        }
        try (var a = b.arrowFunction(new Parameters(1))) {
            b.doReturn(a.arg(0));
        }
        try (var gen = b.generatorFunction(fn().named(true).strict(true))) {
            b.yield_(b.loadInt(1));
            b.yieldEach(b.createArray());
        }
        b.callFunction(f, List.of(b.loadInt(1), b.loadInt(2)));
    }

    static void objects(Builder b) {
        int one = b.loadInt(1);
        var o = b.objectLiteral();
        try (o) {
            o.addProperty(k("A"), one);
            o.addElement(2, one);
        }
        b.typeof(o.result());
        int key = b.loadString("k");
        int proto = b.loadNull();
        var o2 = b.objectLiteral();
        try (o2) {
            o2.addComputed(key, one);
            o2.setPrototype(proto);
            try (var m = o2.method(k("M"), new Parameters(1))) {
                b.doReturn(b.binary(b.getSuperProperty(k("A")), m.arg(0), BinaryOperator.ADD));
            }
            try (var m = o2.getter(k("G"))) {
                b.doReturn(m.this_());
            }
            try (var m = o2.setter(k("G"))) {
                b.setProperty(m.this_(), k("B"), m.value());
            }
        }
        b.callMethod(o2.result(), Env.method("OBJECT_HAS_OWN_PROPERTY"), List.of(b.loadString("a")));
        var o3 = b.objectLiteral();
        try (o3) {
            // empty
        }
        b.typeof(o3.result());
    }

    static void control(Builder b) {
        int n = b.declare(b.loadInt(0));
        try (var loop = b.whileLoop()) {
            int c = b.compare(n, b.loadInt(3), CompareOperator.LESS);
            try (var body = loop.body(c)) {
                b.unary(UnaryOperator.POST_INC, n);
                b.break_(body.label());
            }
        }
        try (var loop = b.doWhileLoop()) {
            b.continue_();
            try (var h = loop.header()) {
                h.done(b.loadBool(false));
            }
        }
        try (var loop = b.forLoop()) {
            int cond;
            try (var c = loop.condition(List.of(b.loadInt(0)))) {
                cond = b.compare(c.var(0), b.loadInt(2), CompareOperator.LESS);
            }
            try (var a = loop.afterthought(cond)) {
                b.unary(UnaryOperator.POST_INC, a.var(0));
            }
            try (var body = loop.body()) {
                b.update(n, body.var(0), BinaryOperator.ADD);
            }
        }
        try (var fe = b.forEach(ForEachKind.OF, b.createArray(List.of(n)), DeclarationKind.CONST)) {
            b.typeof(fe.var(0));
        }
        try (var fe = b.forEach(ForEachKind.IN, b.builtin(Env.builtin("MATH")))) {
            b.blockBreak(fe.label());
        }
        try (var r = b.repeat(5)) {
            try (var br = b.if_(b.compare(r.counter(), b.loadInt(2), CompareOperator.EQUAL), true)) {
                b.continue_(r.label());
                try (var e = br.else_()) {
                    b.break_();
                }
            }
        }
        try (var t = b.try_()) {
            b.throw_(b.loadString("x"));
            try (var e = t.catch_()) {
                b.typeof(e.var());
            }
            try (var f = t.finally_()) {
                b.typeof(n);
            }
        }
        int one = b.loadInt(1), two = b.loadInt(2);
        try (var sw = b.switch_(n)) {
            try (var c = sw.case_(one)) {
                b.switchBreak();
            }
            try (var c = sw.case_(two, true)) {
                b.typeof(n);
            }
            try (var c = sw.default_()) {
                b.typeof(n);
            }
        }
        try (var blk = b.block()) {
            b.blockBreak(blk.label());
        }
        try (var w = b.with_(b.builtin(Env.builtin("MATH")))) {
            b.typeof(n);
        }
    }

    static void destructuring(Builder b) {
        int arr = b.createIntArray(List.of(1L, 2L, 3L));
        List<Integer> ar = b.destruct(arr, new ArrayPattern(List.of(new ArrayElement(new Binding()), new ArrayElement(null)), new Binding()));
        int rest = ar.get(1);
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        var pat = new ObjectPattern(List.of(new ObjectProperty(k("PI"), new Binding()), new ObjectProperty(null, new Binding(), true)), true);
        b.destruct(o, pat, DeclarationKind.CONST, List.of(b.loadString("E"), b.loadInt(0)));
        b.destructAndReassign(arr, new ArrayPattern(List.of(new ArrayElement(new Binding()))), List.of(rest));
    }

    static void probes(Builder b) {
        b.currentGen = "int";
        b.declare(b.loadInt(1));
        b.probe();
        b.currentGen = "string";
        b.emit(new Ops.LoadString("x"), Lift.VAR);
        b.loadString("inlined");
        b.probe(ProbeLabel.END);
        b.probe();
    }

    static void codeStrings(Builder b) {
        int n = b.declare(b.loadInt(1));
        int result;
        try (var cs = b.codeString()) {
            b.reassign(n, b.loadString("a\"b"));
            result = cs.result();
        }
        b.callFunction(b.builtin(Env.builtin("EVAL")), List.of(result));
    }

    static void configure(Builder b) {
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        int g;
        try (var a = b.arrowFunction()) {
            g = a.f();
            b.doReturn(b.loadInt(1));
        }
        b.configureProperty(o, k("X"), PropertyFlag.of(PropertyFlag.CONFIGURABLE | PropertyFlag.ENUMERABLE), PropertyType.GETTER, g);
        b.configureElement(o, 0, PropertyFlag.of(PropertyFlag.WRITABLE), PropertyType.VALUE, b.loadInt(2));
        b.typeof(b.createTemplate(List.of("a", "b`${c", ""), List.of(b.loadInt(1), b.loadInt(2))));
        b.typeof(b.loadRegExp("a+", RegExpFlag.of(RegExpFlag.GLOBAL | RegExpFlag.IGNORE_CASE)));
    }

    static void spellings(Builder b) {
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int three = b.loadInt(3);
        int x = b.binary(three, b.loadInt(4), BinaryOperator.ADD);
        b.callFunction(f, List.of(x));
        b.callFunction(f, List.of(x));
        b.typeof(b.unary(UnaryOperator.MINUS, b.loadInt(0)));
        b.typeof(b.unary(UnaryOperator.MINUS, b.unary(UnaryOperator.MINUS, b.loadInt(1))));
        b.typeof(b.binary(b.unary(UnaryOperator.MINUS, b.loadInt(2)), b.loadInt(2), BinaryOperator.EXP));
        b.typeof(b.loadBigInt(5));
        int s = b.binary(b.binary(three, x, BinaryOperator.ADD), three, BinaryOperator.MUL);
        b.typeof(s);
        b.callMethod(b.loadInt(3), Env.method("NUMBER_TO_STRING"), List.of());
        b.typeof(b.ternary(b.compare(three, x, CompareOperator.LESS), three, x));
        b.typeof(b.unary(UnaryOperator.LOGICAL_NOT, b.compare(three, x, CompareOperator.EQUAL)));
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        b.typeof(b.getProperty(o, k("PI")));
        b.typeof(b.getProperty(o, new Key("a-b")));
        b.typeof(b.getProperty(o, new Key("3", KeyKind.INDEX)));
        b.typeof(b.getProperty(o, k("A"), true));
        b.typeof(b.getElement(o, -1));
        b.setComputed(o, b.loadString("k"), b.loadInt(1));
        b.typeof(b.deleteProperty(o, k("X")));
        int m = b.construct(b.builtin(Env.builtin("MAP")));
        b.callMethod(m, Env.method("MAP_SET"), List.of(b.loadInt(1), b.loadInt(2)));
    }

    static void rare(Builder b) {
        int one = b.loadInt(1);
        int f;
        try (var fb = b.plainFunction(new Parameters(1), fn().named(true))) {
            f = fb.f();
            b.directiveStrict();
            int t = b.newTarget();
            b.typeof(t);
            b.doReturn(fb.arg(0));
        }
        int g = b.namedVariable(new Key("gx"), NamedVariableMode.GLOBAL, one);
        b.namedVariable(new Key("vx"), NamedVariableMode.VAR, g);
        b.namedVariable(new Key("lx"), NamedVariableMode.LET, one);
        b.namedVariable(new Key("cx"), NamedVariableMode.CONST, one);
        int arr = b.createArray(List.of(one, one));
        int sp = b.createArraySpread(List.of(arr, one), List.of(true, false));
        b.callFunctionSpread(f, List.of(arr), List.of(true));
        b.callFunctionSpread(f, List.of(arr, one), List.of(true, false), true);
        int ctor = b.builtin(Env.builtin("ARRAY"));
        b.constructSpread(ctor, List.of(sp), List.of(true));
        b.callMethodSpread(sp, Env.method("ARRAY_PUSH"), List.of(arr), List.of(true));
        b.callMethodSpread(sp, Env.method("ARRAY_PUSH"), List.of(arr, one), List.of(false, true), true, true);
        int key = b.loadString("push");
        b.callComputedMethodSpread(sp, key, List.of(arr), List.of(true));
        b.callComputedMethod(sp, key, List.of(one), true, true);
        b.callFunction(f, List.of(one), true);
        b.callMethod(sp, Env.method("ARRAY_PUSH"), List.of(one), true, true);
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        int getter, setter;
        try (var a = b.arrowFunction()) {
            getter = a.f();
            b.doReturn(one);
        }
        try (var a = b.arrowFunction(new Parameters(1))) {
            setter = a.f();
        }
        b.configureElement(o, 3, PropertyFlag.of(PropertyFlag.CONFIGURABLE | PropertyFlag.ENUMERABLE), PropertyType.GETTER_SETTER, getter, setter);
        b.configureElement(o, 4, PropertyFlag.of(PropertyFlag.WRITABLE | PropertyFlag.CONFIGURABLE), PropertyType.SETTER, setter);
        b.configureComputed(o, key, PropertyFlag.of(PropertyFlag.WRITABLE), PropertyType.VALUE, one);
        b.configureComputed(o, key, PropertyFlag.NONE, PropertyType.GETTER_SETTER, getter, setter);
        b.configureProperty(o, new Key("a b"), PropertyFlag.of(PropertyFlag.WRITABLE | PropertyFlag.ENUMERABLE), PropertyType.VALUE, one);
        b.updateProperty(o, k("X"), one, BinaryOperator.ADD);
        b.updateElement(o, 2, one, BinaryOperator.MUL);
        b.updateComputed(o, key, one, BinaryOperator.SUB);
        b.typeof(b.deleteElement(o, 1, true));
        b.typeof(b.deleteComputed(o, key, true));
        b.typeof(b.getComputed(o, key, true));
        b.typeof(b.getElement(o, 7, true));
        b.setElement(o, 5, one);
        b.typeof(b.testIn(key, o));
        b.typeof(b.testInstanceOf(o, ctor));
        b.typeof(b.void_(one));
        b.typeof(b.loadFloat(1500.0, FloatStyle.EXPONENT));
        b.typeof(b.loadFloat(0.1, FloatStyle.EXPONENT));
        b.typeof(b.loadFloat(1e300, FloatStyle.EXPONENT));
        b.typeof(b.loadFloat(5e-324));
        b.typeof(b.loadFloat(123456789.125));
        b.typeof(b.loadInt(255, Radix.HEX));
        b.typeof(b.loadInt(8, Radix.OCT));
        b.typeof(b.loadInt(5, Radix.BIN));
        b.typeof(b.loadInt(1L << 53));
        b.typeof(b.loadBigInt(BigInteger.TWO.pow(70)));
        b.typeof(b.createFloatArray(Arrays.asList(1.5, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -2.25, 0.0)));
        b.typeof(b.loadRegExp("[a-z]+", RegExpFlag.of(RegExpFlag.STICKY | RegExpFlag.UNICODE | RegExpFlag.DOT_ALL | RegExpFlag.MULTILINE)));
        b.typeof(b.loadString("tab\tnul\u0000 sep  surrogate\uD800 astral😀 quote' dq\" bs\\"));
        b.typeof(b.createTemplate(List.of("$", "${", "`\\", "\uD800"), List.of(one, one, one)));
        b.typeof(b.this_());
        var lit = b.objectLiteral();
        try (lit) {
            lit.addProperty(new Key("not ident"), one);
            lit.addProperty(new Key("7", KeyKind.INDEX), one);
            try (var m = lit.method(k("M"), new Parameters(2, false, List.of(1), List.of()), fn().generator(true).defaults(one).types(NUMBER))) {
                int x = m.arg(0), y = m.arg(1);
                b.setSuperProperty(k("A"), x);
                b.updateSuperProperty(k("A"), y, BinaryOperator.ADD);
                b.setComputedSuperProperty(key, x);
                b.typeof(b.getComputedSuperProperty(key));
                b.typeof(b.callSuperMethod(k("TO_STRING"), List.of(x)));
                b.typeof(b.callSuperMethod(new Key("a-b"), List.of()));
                b.typeof(b.getSuperProperty(new Key("a-b")));
                b.yield_();
            }
            try (var m = lit.computedMethod(key, new Parameters(1), fn().generator(true))) {
                b.yieldEach(arr);
            }
        }
        b.typeof(lit.result());
        try (var gen = b.generatorFunction(new Parameters(1, true))) {
            int y = b.yield_(gen.arg(0));
            b.typeof(y);
        }
        var pat = new ArrayPattern(List.of(new ArrayElement(new Binding(), true), new ArrayElement(new PropertyTarget(k("X"))),
                new ArrayElement(new ElementTarget(0)), new ArrayElement(new ComputedTarget()),
                new ArrayElement(new ObjectPattern(List.of(new ObjectProperty(k("A"), new Binding()))))),
                new ArrayPattern(List.of(new ArrayElement(new Binding()))));
        b.destruct(arr, pat, DeclarationKind.LET, List.of(one, o, o, o, key));
        int v = b.declare(one, DeclarationKind.LET);
        int w = b.declare(one, DeclarationKind.VAR);
        b.destructAndReassign(arr, new ObjectPattern(List.of(new ObjectProperty(k("A"), new Binding()), new ObjectProperty(null, new Binding()))),
                List.of(v, w), List.of(key));
        try (var fe = b.forEach(ForEachKind.OF, arr, new ArrayPattern(List.of(new ArrayElement(new Binding()), new ArrayElement(new Binding()))),
                List.of(), DeclarationKind.LET)) {
            b.typeof(fe.var(0));
            b.continue_(fe.label());
        }
        try (var r = b.repeat(2, false, DeclarationKind.VAR)) {
            b.break_(r.label());
        }
        var destructured = new Parameters(2, false, List.of(),
                List.of(Map.entry(0, new ObjectPattern(List.of(new ObjectProperty(k("A"), new Binding())), true))));
        try (var h = b.plainFunction(destructured)) {
            b.doReturn(h.arg(0));
        }
        try (var loop = b.forLoop()) {
            int c;
            try (var cond = loop.condition(List.of(), DeclarationKind.VAR)) {
                c = b.loadBool(true);
            }
            try (var a = loop.afterthought(c)) {
                // empty
            }
            try (var body = loop.body()) {
                b.break_();
            }
        }
        try (var loop = b.forLoop()) {
            int x0 = b.callFunction(f, List.of(one));
            int c;
            try (var cond = loop.condition(List.of(x0, one), DeclarationKind.LET)) {
                b.callFunction(f, List.of(cond.var(0)));
                c = b.compare(cond.var(0), cond.var(1), CompareOperator.LESS);
            }
            try (var a = loop.afterthought(c)) {
                b.callFunction(f, List.of(a.var(0)));
                b.unary(UnaryOperator.PRE_INC, a.var(1));
            }
            try (var body = loop.body()) {
                b.typeof(body.var(1));
            }
        }
        try (var loop = b.whileLoop()) {
            int q = b.callFunction(f, List.of(one));
            try (var body = loop.body(b.compare(q, one, CompareOperator.LESS))) {
                // empty
            }
        }
        try (var loop = b.doWhileLoop()) {
            try (var hh = loop.header()) {
                hh.done(b.callFunction(f, List.of(one)));
            }
        }
        try (var tr = b.try_()) {
            try (var err = tr.catch_()) {
                b.throw_(err.var());
            }
        }
        b.emit(new Ops.LoadInteger(9), Lift.LET);
        b.emit(new Ops.LoadInteger(10), Lift.INLINE);
        b.emit(new Ops.BinaryOperation(BinaryOperator.NULL_COALESCE), List.of(one, one), Lift.CONST);
        for (BinaryOperator op : BinaryOperator.values()) b.typeof(b.binary(one, one, op));
        for (CompareOperator op : CompareOperator.values()) b.typeof(b.compare(one, one, op));
        int n = b.declare(one);
        for (UnaryOperator op : UnaryOperator.values()) b.typeof(b.unary(op, n));
        b.probe(ProbeLabel.WARM, 3);
    }

    /** A scenario built with the given options, finished. */
    static List<Instruction> build(String name, GenOptions options) {
        Builder b = new Builder(options);
        ALL.get(name).accept(b);
        return new ArrayList<>(b.finish());
    }
}
