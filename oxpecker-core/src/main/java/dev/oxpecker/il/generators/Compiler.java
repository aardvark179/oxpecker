package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.declarationKind;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.UNDEFINED;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.BinaryOperator;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.CompareOperator;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.FloatStyle;
import dev.oxpecker.il.Key;
import dev.oxpecker.il.Lift;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Ops.LoadFloat;
import dev.oxpecker.il.Ops.LoadInteger;
import dev.oxpecker.il.Parameters;
import dev.oxpecker.il.PropertyFlag;
import dev.oxpecker.il.PropertyType;
import dev.oxpecker.il.Radix;
import dev.oxpecker.il.Type;
import dev.oxpecker.il.UnaryOperator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compiler-targeted generators (compiler.py): the shapes Rhino's bytecode compiler treats
 * differently from the interpreter (docs/design-generation-layer.md §5.3 and §7.7). They are built
 * against the Rhino model: number variables (every assignment NumberType), activation triggers,
 * direct calls, call sites re-linked with new types, property sites changing shape, ConsStrings,
 * int32 boundaries, {@code super} in object literals, constant folding, negative zero.
 */
public final class Compiler {
    private Compiler() {}

    static final List<Long> INT32_BOUNDARIES = List.of((1L << 31) - 1, 1L << 31, (1L << 32) - 1, 1L << 30, 65536L, 0L);
    static final List<Long> SMALL_INTS = List.of(0L, 1L, 2L, 3L, 7L, 10L, 255L, 256L, 1000L, (1L << 31) - 1);
    static final List<Radix> RADIXES = List.of(Radix.values());

    @FunctionalInterface interface Step { void apply(Builder b, int t, int x, int y); }

    @FunctionalInterface interface Form2 { int apply(Builder b, int x, int y); }

    @FunctionalInterface interface Form1 { int apply(Builder b, int x); }

    @FunctionalInterface interface Step2 { void apply(Builder b, int x, int p); }

    private static Key key(String attr) { return Env.k(attr); }

    private static Method method(String attr) { return Env.method(attr); }

    /**
     * Every entry assigns the target an expression Rhino's optimizer types as NumberType when its
     * inputs are number variables or literals (Block.findExpressionType; design §5.3).
     */
    static final List<Step> NUMERIC_STEPS = List.of(
            (b, t, x, y) -> b.reassign(t, b.binary(x, y, BinaryOperator.ADD)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, y, BinaryOperator.MUL)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, y, BinaryOperator.SUB)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, y, BinaryOperator.DIV)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, y, BinaryOperator.MOD)),
            (b, t, x, y) -> b.unary(UnaryOperator.POST_INC, t),
            (b, t, x, y) -> b.update(t, x, BinaryOperator.ADD),
            (b, t, x, y) -> b.reassign(t, b.binary(x, b.loadInt(0), BinaryOperator.BIT_OR)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, b.loadInt(0), BinaryOperator.U_RSHIFT)),
            (b, t, x, y) -> b.reassign(t, b.unary(UnaryOperator.BITWISE_NOT, x)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, b.loadInt(1), BinaryOperator.LSHIFT)),
            (b, t, x, y) -> b.reassign(t, b.unary(UnaryOperator.MINUS, x)),
            (b, t, x, y) -> b.reassign(t, b.binary(x, b.loadInt(2), BinaryOperator.EXP)),
            (b, t, x, y) -> b.reassign(t, b.callMethod(b.builtin(Env.builtin("MATH")), method("MATH_IMUL"), List.of(x, y))),
            (b, t, x, y) -> b.reassign(t, b.ternary(b.compare(x, y, CompareOperator.LESS), x, y)));
    static final List<Type> PERTURBING_TYPES = List.of(STRING, OBJECT, BIGINT, UNDEFINED, FUNCTION);

    /**
     * Locals initialised from integer literals, then arithmetic steps over locals and parameters:
     * the optimizer types every local as a number variable.
     */
    static List<Integer> numericBody(Draw d, Builder b, List<Integer> params, int nLocals) {
        return d.draw(dd -> {
            List<Integer> locals = new ArrayList<>();
            for (int i = 0; i < nLocals; i++) {
                int v = b.loadInt(dd.sampled(SMALL_INTS));
                locals.add(b.declare(v, declarationKind(dd, b.options, true)));
            }
            List<Integer> pool = new ArrayList<>(locals);
            pool.addAll(params);
            for (Step step : dd.lists(1, 5, e -> e.sampled(NUMERIC_STEPS))) {
                int t = pick(dd, locals), x = pick(dd, pool), y = pick(dd, pool);
                step.apply(b, t, x, y);
            }
            return locals;
        });
    }

    /** A value whose Rhino type is AnyType: a string, object, BigInt, undefined, or a function. */
    static int perturbingValue(Draw d, Builder b) {
        return d.draw(dd -> variable(dd, b, dd.sampled(PERTURBING_TYPES)));
    }

    /**
     * {@code acc.push(f(args))} inside a top-level try/catch whose catch records the error name
     * (Rhino: a catch at script level does not touch any function's activation).
     */
    static void guardedCall(Builder b, int f, List<Integer> args, int acc) {
        try (var t = b.try_()) {
            b.callMethod(acc, method("ARRAY_PUSH"), List.of(b.callFunction(f, args)));
            try (var e = t.catch_()) {
                b.callMethod(acc, method("ARRAY_PUSH"), List.of(b.getProperty(e.var(), key("NAME"))));
            }
        }
    }

    private static void push(Builder b, int acc, int value) {
        b.callMethod(acc, method("ARRAY_PUSH"), List.of(value));
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> c) {
        List<Integer> out = new ArrayList<>(a);
        out.addAll(c);
        return out;
    }

    static final List<Consumer<Builder>> ACTIVATION_TRIGGERS = List.of(
            b -> b.declare(b.getProperty(b.arguments(), key("LENGTH"))),
            b -> b.declare(b.getElement(b.arguments(), 0)),
            Compiler::emptyArrow,
            Compiler::emptyTryCatch);

    private static void emptyArrow(Builder b) {
        try (var f = b.arrowFunction()) {
            // empty
        }
    }

    private static void emptyTryCatch(Builder b) {
        try (var t = b.try_()) {
            try (var c = t.catch_()) {
                // empty
            }
        }
    }

    static final List<Form2> RETURN_FORMS = List.of(
            (b, p, q) -> b.binary(p, q, BinaryOperator.ADD),
            (b, p, q) -> b.createArray(List.of(p, q)),
            (b, p, q) -> b.binary(b.binary(p, b.loadInt(2), BinaryOperator.MUL), b.binary(q, b.loadInt(0), BinaryOperator.BIT_OR),
                    BinaryOperator.ADD),
            (b, p, q) -> b.typeof(q),
            (b, p, q) -> b.ternary(b.compare(p, b.loadUndefined(), CompareOperator.STRICT_EQUAL),
                    b.unary(UnaryOperator.MINUS, b.loadInt(1)), p));

    static final List<Form2> SITE_FORMS = List.of(
            (b, x, y) -> b.binary(x, y, BinaryOperator.ADD),
            (b, x, y) -> b.binary(x, y, BinaryOperator.SUB),
            (b, x, y) -> b.binary(x, y, BinaryOperator.MUL),
            (b, x, y) -> b.compare(x, y, CompareOperator.LESS),
            (b, x, y) -> b.compare(x, y, CompareOperator.EQUAL),
            (b, x, y) -> b.getProperty(x, key("LENGTH")),
            (b, x, y) -> b.getElement(x, 0),
            (b, x, y) -> b.binary(x, b.loadString(""), BinaryOperator.ADD),
            (b, x, y) -> b.binary(x, y, BinaryOperator.BIT_OR),
            (b, x, y) -> b.binary(x, y, BinaryOperator.DIV),
            (b, x, y) -> b.binary(x, y, BinaryOperator.MOD),
            (b, x, y) -> b.unary(UnaryOperator.MINUS, x));

    static final List<Step2> BOUNDARY_STEPS = List.of(
            (b, x, p) -> b.reassign(x, b.binary(x, p, BinaryOperator.ADD)),
            (b, x, p) -> b.reassign(x, b.binary(x, p, BinaryOperator.MUL)),
            (b, x, p) -> b.unary(UnaryOperator.POST_INC, x),
            (b, x, p) -> b.reassign(x, b.binary(x, b.loadInt(1), BinaryOperator.LSHIFT)),
            (b, x, p) -> b.reassign(x, b.binary(x, p, BinaryOperator.SUB)),
            (b, x, p) -> b.reassign(x, b.binary(b.binary(x, b.loadInt(1), BinaryOperator.ADD), b.loadInt(0), BinaryOperator.BIT_OR)),
            (b, x, p) -> b.reassign(x, b.binary(x, x, BinaryOperator.MUL)));

    // compiler.py's SUPER_FORMS is defined but never used; it is not ported.

    static final List<Form1> CONST_FORMS = List.of(
            (b, x) -> b.binary(x, b.loadInt(1), BinaryOperator.ADD),
            (b, x) -> b.binary(x, b.loadInt(0), BinaryOperator.MUL),
            (b, x) -> b.binary(x, b.loadInt(0), BinaryOperator.DIV),
            (b, x) -> b.binary(x, b.loadInt(1), BinaryOperator.MOD),
            (b, x) -> b.unary(UnaryOperator.MINUS, x),
            (b, x) -> b.compare(x, x, CompareOperator.STRICT_EQUAL),
            (b, x) -> b.binary(x, b.loadInt(0), BinaryOperator.BIT_OR),
            (b, x) -> b.binary(x, b.loadInt(0), BinaryOperator.U_RSHIFT),
            (b, x) -> b.binary(b.loadString(""), x, BinaryOperator.ADD),
            (b, x) -> b.binary(x, b.loadFloat(0.5), BinaryOperator.EXP),
            (b, x) -> b.typeof(x));

    static void register() {
        generator("numeric_local_type_flip").family("compiler-targeted").weight(6).produces(FUNCTION).block()
                .doc("Number-var locals (every assignment NumberType to Rhino's optimizer), one of them flipped "
                        + "to a non-number late in the function; called with ints, doubles and a perturbing value.")
                .register((d, b, in) -> {
                    int f;
                    try (var fb = b.plainFunction(new Parameters(2), Builder.fn().named(d.maybe(0.5)).types(NUMBER, NUMBER))) {
                        f = fb.f();
                        List<Integer> params = fb.args();
                        int n = d.ints(1, 3);
                        List<Integer> locals = numericBody(d, b, params, n);
                        if (d.maybe(0.8)) {
                            int victim = pick(d, locals);
                            b.reassign(victim, perturbingValue(d, b));
                            int target = pick(d, locals);
                            int other = pick(d, concat(locals, params));
                            b.reassign(target, b.binary(victim, other, BinaryOperator.ADD));
                        }
                        b.doReturn(b.createArray(locals));
                    }
                    int acc = b.declare(b.createArray(List.of()));
                    int i1 = b.loadInt(d.sampled(SMALL_INTS));
                    int i2 = b.loadInt(d.sampled(SMALL_INTS));
                    guardedCall(b, f, List.of(i1, i2), acc);
                    int f1 = b.loadFloat(d.sampled(Env.INTERESTING_FLOATS));
                    int f2 = b.loadFloat(d.sampled(Env.INTERESTING_FLOATS));
                    guardedCall(b, f, List.of(f1, f2), acc);
                    int pv = perturbingValue(d, b);
                    int i3 = b.loadInt(d.sampled(SMALL_INTS));
                    guardedCall(b, f, List.of(pv, i3), acc);
                    return f;
                });

        List<Type> secondArgTypes = List.of(NUMBER, NUMBER, STRING, OBJECT);
        generator("activation_pair").family("compiler-targeted").weight(5).block()
                .doc("The same numeric body twice: once plain (the optimizer runs) and once with one activation "
                        + "trigger inserted (`arguments`, a nested arrow, a catch clause), so the compiled paths differ "
                        + "while the results must not.")
                .register((d, b, in) -> {
                    int fa, bodyStart, bodyEnd;
                    List<Integer> params;
                    try (var fb = b.plainFunction(new Parameters(2), Builder.fn().types(NUMBER, NUMBER))) {
                        fa = fb.f();
                        params = fb.args();
                        bodyStart = b.code.size();
                        List<Integer> locals = numericBody(d, b, params, 2);
                        b.doReturn(b.binary(locals.get(0), locals.get(1), BinaryOperator.ADD));
                        bodyEnd = b.code.size();
                    }
                    int fbv;
                    try (var fb2 = b.plainFunction(new Parameters(2), Builder.fn().types(NUMBER, NUMBER))) {
                        fbv = fb2.f();
                        List<Integer> params2 = fb2.args();
                        d.sampled(ACTIVATION_TRIGGERS).accept(b);
                        int[] map = new int[fbv + 1];
                        Arrays.fill(map, -1);
                        for (int i = 0; i < Math.min(params.size(), params2.size()); i++) map[params.get(i)] = params2.get(i);
                        b.replay(bodyStart, bodyEnd, map);
                    }
                    int x = b.loadInt(d.sampled(SMALL_INTS));
                    int y = variable(d, b, d.sampled(secondArgTypes));
                    b.declare(b.createArray(List.of(b.callFunction(fa, List.of(x, y)), b.callFunction(fbv, List.of(x, y)))));
                    return null;
                });

        generator("direct_call_arity").family("compiler-targeted").weight(5).block()
                .doc("A named top-level function called from inside another function with its exact arity (a "
                        + "Rhino direct call), then with fewer, more and no arguments, then possibly redefined.")
                .register((d, b, in) -> {
                    Form2 form = d.sampled(RETURN_FORMS);
                    int f;
                    try (var fb = b.plainFunction(new Parameters(2), Builder.fn().named(true))) {
                        f = fb.f();
                        b.doReturn(form.apply(b, fb.arg(0), fb.arg(1)));
                    }
                    int acc = b.declare(b.createArray(List.of()));
                    int one = b.loadInt(d.sampled(SMALL_INTS));
                    int two = b.loadInt(d.sampled(SMALL_INTS));
                    int caller;
                    try (var cb = b.plainFunction(new Parameters(0), Builder.fn().named(true))) {
                        caller = cb.f();
                        b.doReturn(b.callFunction(f, List.of(one, two)));
                    }
                    push(b, acc, b.callFunction(caller, List.of()));
                    push(b, acc, b.callFunction(f, List.of(b.loadFloat(d.sampled(Env.INTERESTING_FLOATS)))));
                    int pv = perturbingValue(d, b);
                    push(b, acc, b.callFunction(f, List.of(one, two, pv)));
                    push(b, acc, b.callFunction(f, List.of()));
                    if (d.maybe(0.5)) {
                        int g;
                        try (var gb = b.plainFunction(new Parameters(2))) {
                            g = gb.f();
                            b.doReturn(d.sampled(RETURN_FORMS).apply(b, gb.arg(0), gb.arg(1)));
                        }
                        b.reassign(f, g);
                        push(b, acc, b.callFunction(caller, List.of()));
                    }
                    return null;
                });

        List<Type> firstArgTypes = List.of(NUMBER, NUMBER, STRING, Type.array(), OBJECT);
        generator("callsite_relink").family("compiler-targeted").weight(6).block()
                .doc("One function with a single operation over its parameters, called in a loop with one type "
                        + "of argument, then with another: exercises the invokedynamic linkers' relinking.")
                .register((d, b, in) -> {
                    Form2 form = d.sampled(SITE_FORMS);
                    int f;
                    try (var fb = b.plainFunction(new Parameters(2))) {
                        f = fb.f();
                        b.doReturn(form.apply(b, fb.arg(0), fb.arg(1)));
                    }
                    int acc = b.declare(b.createArray(List.of()));
                    int first = variable(d, b, d.sampled(firstArgTypes));
                    try (var loop = b.repeat(d.ints(2, 6))) {
                        push(b, acc, b.callFunction(f, List.of(first, loop.counter())));
                    }
                    int second = perturbingValue(d, b);
                    push(b, acc, b.callFunction(f, List.of(second, b.loadInt(d.sampled(SMALL_INTS)))));
                    push(b, acc, b.callFunction(f, List.of(first, second)));
                    return null;
                });

        List<Key> siteKeys = List.of(key("X"), key("Y"), key("LENGTH"), key("A"));
        generator("property_site_shape_change").family("compiler-targeted").weight(4).block()
                .doc("A property read site warmed on one object shape; then the object loses the key, gains an "
                        + "accessor, changes prototype, or is replaced by an array.")
                .register((d, b, in) -> {
                    Key k = d.sampled(siteKeys);
                    int initial = b.loadInt(d.sampled(SMALL_INTS));
                    var lit = b.objectLiteral();
                    try (lit) {
                        lit.addProperty(k, initial);
                    }
                    int o = b.declare(lit.result(), declarationKind(d, b.options, true));
                    int f;
                    try (var fb = b.plainFunction(new Parameters(1), Builder.fn().types(OBJECT))) {
                        f = fb.f();
                        b.doReturn(b.binary(b.getProperty(fb.arg(0), k), b.loadInt(1), BinaryOperator.ADD));
                    }
                    int acc = b.declare(b.createArray(List.of()));
                    try (var loop = b.repeat(3)) {
                        push(b, acc, b.callFunction(f, List.of(o)));
                    }
                    int change = d.ints(0, 4);
                    if (change == 0) {
                        b.deleteProperty(o, k);
                    } else if (change == 1) {
                        int g;
                        try (var gb = b.arrowFunction()) {
                            g = gb.f();
                            b.doReturn(b.loadFloat(d.sampled(Env.INTERESTING_FLOATS)));
                        }
                        b.configureProperty(o, k, PropertyFlag.of(PropertyFlag.CONFIGURABLE), PropertyType.GETTER, g);
                    } else if (change == 2) {
                        int replacement = perturbingValue(d, b);
                        var proto = b.objectLiteral();
                        try (proto) {
                            proto.addProperty(k, replacement);
                        }
                        b.callMethod(b.builtin(Env.builtin("OBJECT")), method("OBJECT_CTOR_SET_PROTOTYPE_OF"), List.of(o, proto.result()));
                        b.deleteProperty(o, k);
                    } else if (change == 3) {
                        b.setProperty(o, k, perturbingValue(d, b));
                    } else {
                        b.reassign(o, b.createIntArray(List.of(d.sampled(SMALL_INTS))));
                    }
                    push(b, acc, b.callFunction(f, List.of(o)));
                    return null;
                });

        List<String> seeds = List.of("", "a", "0");
        generator("string_concat_chain").family("compiler-targeted").weight(4).produces(STRING).block()
                .doc("`s += x` in a bounded loop (a ConsString), then compared, indexed, converted.")
                .register((d, b, in) -> {
                    int init = b.loadString(d.sampled(seeds));
                    int s = b.declare(init, declarationKind(d, b.options, true));
                    try (var loop = b.repeat(d.ints(1, 8))) {
                        int i = loop.counter();
                        int piece = d.ints(0, 3);
                        int value = piece == 0 ? i : piece == 1 ? b.loadString("b") : piece == 2 ? b.getProperty(s, key("LENGTH")) : b.loadFloat(1.5);
                        b.update(s, value, BinaryOperator.ADD);
                    }
                    int use = d.ints(0, 4);
                    if (use == 0) return b.compare(s, b.getProperty(s, key("LENGTH")), CompareOperator.EQUAL);
                    if (use == 1) return b.binary(s, b.loadInt(1), BinaryOperator.ADD);
                    if (use == 2) return b.getElement(s, 2);
                    if (use == 3) return b.callMethod(s, method("STRING_CHAR_CODE_AT"), List.of(b.loadInt(0)));
                    return b.callFunction(b.builtin(Env.builtin("NUMBER")), List.of(s));
                });

        generator("int32_boundary_arith").family("compiler-targeted").weight(4).produces(NUMBER).block()
                .doc("A number-var local at an int32 boundary, one arithmetic step with a parameter, returned "
                        + "with `x | 0`, `x >>> 0` and `x === (x | 0)`.")
                .register((d, b, in) -> {
                    int f;
                    try (var fb = b.plainFunction(new Parameters(1), Builder.fn().types(NUMBER))) {
                        f = fb.f();
                        int p = fb.arg(0);
                        long value = d.sampled(INT32_BOUNDARIES);
                        int lit = b.loadInt(value, d.sampled(RADIXES));
                        int x = b.declare(lit, declarationKind(d, b.options, true));
                        d.sampled(BOUNDARY_STEPS).apply(b, x, p);
                        b.doReturn(b.createArray(List.of(x, b.binary(x, b.loadInt(0), BinaryOperator.BIT_OR),
                                b.binary(x, b.loadInt(0), BinaryOperator.U_RSHIFT),
                                b.compare(x, b.binary(x, b.loadInt(0), BinaryOperator.BIT_OR), CompareOperator.STRICT_EQUAL))));
                    }
                    int arg = d.ints(0, 3);
                    // the Python tuple emits all four literals and passes one
                    List<Integer> values = List.of(b.loadInt(1), b.loadFloat(0.5), b.loadInt((1L << 31) - 1), b.loadString("1"));
                    return b.declare(b.callFunction(f, List.of(values.get(arg))));
                });

        generator("super_in_object_literal").family("compiler-targeted").weight(4).block()
                .doc("`super.p`, `super[k] += 1`, `super.p = v` from an object-literal method whose prototype may "
                        + "be swapped by the key's `toString` (the interpreted-only test262 failure shape).")
                .register((d, b, in) -> {
                    Key a = key("A"), g = key("G"), m = key("M");
                    int initial = b.loadInt(d.sampled(SMALL_INTS));
                    var proto = b.objectLiteral();
                    try (proto) {
                        proto.addProperty(a, initial);
                        try (var gb = proto.getter(g)) {
                            b.doReturn(b.binary(b.getProperty(gb.this_(), a), b.loadInt(2), BinaryOperator.MUL));
                        }
                    }
                    int other = b.loadFloat(d.sampled(Env.INTERESTING_FLOATS));
                    var proto2 = b.objectLiteral();
                    try (proto2) {
                        proto2.addProperty(a, other);
                    }
                    int form = d.ints(0, 5);
                    var lit = b.objectLiteral();
                    try (lit) {
                        lit.setPrototype(proto.result());
                        try (var mb = lit.method(m, new Parameters(1))) {
                            int self = mb.this_(), k = mb.arg(0);
                            switch (form) {
                                case 0 -> b.doReturn(b.getSuperProperty(a));
                                case 1 -> {
                                    b.updateSuperProperty(a, b.loadInt(1), BinaryOperator.ADD);
                                    b.doReturn(b.getSuperProperty(a));
                                }
                                case 2 -> b.doReturn(b.getComputedSuperProperty(k));
                                case 3 -> {
                                    b.setComputedSuperProperty(k, b.binary(b.getComputedSuperProperty(k), b.loadInt(1), BinaryOperator.ADD));
                                    b.doReturn(b.getProperty(self, a));
                                }
                                case 4 -> {
                                    b.setSuperProperty(a, b.loadInt(5));
                                    b.doReturn(b.getProperty(self, a));
                                }
                                default -> b.doReturn(b.getSuperProperty(g));
                            }
                        }
                    }
                    int o = b.declare(lit.result());
                    int keyVar;
                    if (d.maybe(0.5)) {
                        var keyobj = b.objectLiteral();
                        try (keyobj) {
                            try (var ts = keyobj.method(key("TO_STRING"))) {
                                b.callMethod(b.builtin(Env.builtin("OBJECT")), method("OBJECT_CTOR_SET_PROTOTYPE_OF"), List.of(o, proto2.result()));
                                b.doReturn(b.loadString(a.name()));
                            }
                        }
                        keyVar = keyobj.result();
                    } else {
                        keyVar = b.loadString(a.name());
                    }
                    b.declare(b.callComputedMethod(o, b.loadString(m.name()), List.of(keyVar)));
                    b.declare(b.createArray(List.of(b.getProperty(o, a), b.getProperty(proto.result(), a), b.getProperty(proto2.result(), a))));
                    return null;
                });

        generator("const_site").family("compiler-targeted").weight(2)
                .doc("An operation on literal constants only, which the compiler may fold or link as constants.")
                .register((d, b, in) -> {
                    int x;
                    if (d.maybe(0.3)) {
                        x = b.emit(new LoadFloat(d.sampled(Env.INTERESTING_FLOATS), FloatStyle.PLAIN), Lift.INLINE).output();
                    } else {
                        long v = d.sampled(Env.INTERESTING_INTEGERS);
                        x = b.emit(new LoadInteger(v, d.sampled(RADIXES)), Lift.INLINE).output();
                    }
                    return b.declare(d.sampled(CONST_FORMS).apply(b, x));
                });

        List<Long> smallSeeds = List.of(0L, 1L, 2L, 7L);
        generator("negative_zero").family("compiler-targeted").weight(3).block()
                .doc("`n * 0`, `~n`, `-n` on a top-level and a local number variable, observed with `Object.is(v, -0)`.")
                .register((d, b, in) -> {
                    int nLit = b.loadInt(d.sampled(smallSeeds));
                    int n = b.declare(nLit, declarationKind(d, b.options, true));
                    if (d.maybe(0.5)) b.reassign(n, b.unary(UnaryOperator.BITWISE_NOT, n));
                    int neg0 = b.unary(UnaryOperator.MINUS, b.loadInt(0));
                    int obj = b.builtin(Env.builtin("OBJECT"));
                    int top = b.binary(n, b.loadInt(0), BinaryOperator.MUL);
                    b.declare(b.callMethod(obj, method("OBJECT_CTOR_IS"), List.of(top, neg0)));
                    int f;
                    try (var fb = b.plainFunction(new Parameters(1), Builder.fn().types(NUMBER))) {
                        f = fb.f();
                        int p = fb.arg(0);
                        int mLit = b.loadInt(d.sampled(smallSeeds));
                        int m = b.declare(mLit, declarationKind(d, b.options, true));
                        b.reassign(m, b.binary(m, p, BinaryOperator.MUL));
                        b.doReturn(b.callMethod(b.builtin(Env.builtin("OBJECT")), method("OBJECT_CTOR_IS"),
                                List.of(b.binary(m, b.loadInt(0), BinaryOperator.MUL), b.unary(UnaryOperator.MINUS, b.loadInt(0)))));
                    }
                    b.declare(b.callFunction(f, List.of(b.unary(UnaryOperator.MINUS, b.loadInt(1)))));
                    return null;
                });
    }
}
