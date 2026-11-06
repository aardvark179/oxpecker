package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.declarationKind;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.STRING;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.BinaryOperator;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.Builtin;
import dev.oxpecker.il.CompareOperator;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.ForEachKind;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Parameters;
import dev.oxpecker.il.Type;
import dev.oxpecker.il.UnaryOperator;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Java interop generators (java.py): values crossing the JS/Java boundary through the harness's
 * {@code J} object (harness-java/.../JavaProbe.java, registered as {@code Builtins.J} with
 * {@code Methods.J_*}). Every {@code J.*} call records the Java runtime class and value it
 * received, so Integer-vs-Double and conversion differences between the modes surface as
 * {@code java-types} findings.
 */
public final class Java {
    private Java() {}

    record TypedParameter(Method method, Type type) {}

    @FunctionalInterface interface Form2 { int apply(Builder b, int x, int y); }

    @FunctionalInterface interface Form1 { int apply(Builder b, int r); }

    private static Method m(String attr) { return Env.method(attr); }

    private static TypedParameter tp(String attr, Type t) { return new TypedParameter(m(attr), t); }

    static final List<TypedParameter> TYPED_PARAMETERS = List.of(
            tp("J_TAKES_INT", NUMBER), tp("J_TAKES_LONG", NUMBER), tp("J_TAKES_SHORT", NUMBER),
            tp("J_TAKES_BYTE", NUMBER), tp("J_TAKES_CHAR", STRING), tp("J_TAKES_FLOAT", NUMBER),
            tp("J_TAKES_DOUBLE", NUMBER), tp("J_TAKES_BOOLEAN", BOOLEAN), tp("J_TAKES_STRING", STRING),
            tp("J_TAKES_INTEGER", NUMBER), tp("J_TAKES_NUMBER", NUMBER), tp("J_TAKES_BIG_INTEGER", BIGINT),
            tp("J_TAKES_CHAR_SEQUENCE", STRING));
    static final List<Method> RETURNING = List.of(m("J_AN_INT"), m("J_A_LONG"), m("J_MAX_LONG"), m("J_A_FLOAT"),
            m("J_A_DOUBLE"), m("J_NEG_ZERO"), m("J_NAN"), m("J_A_SHORT"), m("J_A_BYTE"), m("J_A_CHAR"), m("J_BOXED_INT"),
            m("J_BOXED_LONG"), m("J_BIG_INTEGER"), m("J_A_STRING"), m("J_NOTHING"), m("J_BUMP"));
    static final List<Long> EDGE_INTEGERS = List.of(0L, 1L, 127L, 128L, 255L, 256L, 65536L, (1L << 31) - 1, 1L << 31,
            (1L << 32) - 1, 1L << 53, (1L << 53) - 1);
    static final List<Double> EDGE_FLOATS = List.of(0.0, 0.1, 1.5, 1e21, 0.5);

    /** An integer or float at a representation boundary, negative sometimes; NaN or Infinity sometimes. */
    static int edgeNumber(Draw d, Builder b) {
        return d.draw(dd -> {
            int kind = dd.ints(0, 3);
            int v;
            if (kind == 0) {
                v = b.loadInt(dd.sampled(EDGE_INTEGERS));
            } else if (kind == 1) {
                v = b.loadFloat(dd.sampled(EDGE_FLOATS));
            } else if (kind == 2) {
                v = b.builtin(dd.sampled(List.of(Env.builtin("NAN"), Env.builtin("INFINITY"))));
            } else {
                v = b.loadInt(dd.sampled(EDGE_INTEGERS));
                return b.unary(UnaryOperator.MINUS, v);
            }
            if (dd.maybe(0.3)) v = b.unary(UnaryOperator.MINUS, v);
            return v;
        });
    }

    /** {@code var r = <J call>} inside a top-level try/catch whose catch records the conversion error. */
    static void recorded(Builder b, IntSupplier make) {
        try (var t = b.try_()) {
            b.declare(make.getAsInt());
            try (var e = t.catch_()) {
                b.declare(b.getProperty(e.var(), Env.k("NAME")));
            }
        }
    }

    static final List<Form2> ARITH_FORMS = List.of(
            (b, x, y) -> b.binary(x, y, BinaryOperator.ADD),
            (b, x, y) -> b.binary(x, y, BinaryOperator.MUL),
            (b, x, y) -> b.binary(x, y, BinaryOperator.SUB),
            (b, x, y) -> b.binary(x, y, BinaryOperator.DIV),
            (b, x, y) -> b.binary(x, b.loadInt(0), BinaryOperator.BIT_OR),
            (b, x, y) -> b.binary(x, b.loadInt(0), BinaryOperator.U_RSHIFT),
            (b, x, y) -> b.unary(UnaryOperator.MINUS, x),
            (b, x, y) -> b.binary(x, y, BinaryOperator.MOD),
            (b, x, y) -> b.binary(x, b.loadInt(2), BinaryOperator.EXP),
            (b, x, y) -> b.callMethod(b.builtin(Env.builtin("MATH")), m("MATH_FLOOR"), List.of(x)),
            (b, x, y) -> b.binary(b.binary(x, y, BinaryOperator.ADD), b.loadInt(0), BinaryOperator.BIT_OR),
            (b, x, y) -> b.binary(x, b.loadInt(1), BinaryOperator.LSHIFT),
            (b, x, y) -> b.unary(UnaryOperator.BITWISE_NOT, x));

    static final List<Form1> USE_FORMS = List.of(
            (b, r) -> b.binary(r, b.loadInt(1), BinaryOperator.ADD),
            (b, r) -> b.binary(r, b.loadInt(2), BinaryOperator.MUL),
            (b, r) -> b.compare(r, r, CompareOperator.STRICT_EQUAL),
            (b, r) -> b.typeof(r),
            (b, r) -> b.binary(r, b.loadString(""), BinaryOperator.ADD),
            (b, r) -> b.binary(r, b.loadInt(0), BinaryOperator.BIT_OR),
            (b, r) -> b.compare(r, b.loadInt(7), CompareOperator.EQUAL),
            (b, r) -> b.callFunction(b.builtin(Env.builtin("NUMBER")), List.of(r)),
            (b, r) -> b.compare(r, b.loadInt((1L << 31) - 1), CompareOperator.GREATER),
            (b, r) -> b.getProperty(b.callFunction(b.builtin(Env.builtin("STRING")), List.of(r)), Env.k("LENGTH")),
            (b, r) -> b.binary(r, b.loadInt(3), BinaryOperator.DIV),
            (b, r) -> b.compare(r, b.loadNull(), CompareOperator.STRICT_EQUAL),
            (b, r) -> b.callMethod(b.builtin(Env.builtin("JSON")), m("JSON_STRINGIFY"), List.of(r)),
            (b, r) -> b.callMethod(b.builtin(Env.builtin("OBJECT")), m("OBJECT_CTOR_IS"),
                    List.of(r, b.unary(UnaryOperator.MINUS, b.loadInt(0)))));

    private static int storeThenRead(Builder b, int a, long index, int v) {
        b.setElement(a, index, v);
        return b.getElement(a, 0);
    }

    private static Builtin j() { return Env.builtin("J"); }

    static void register() {
        generator("java_typed_call").family("java-interop").weight(6).block()
                .doc("Pass a value to a Java method with a primitive or boxed parameter type; Rhino converts it.")
                .register((d, b, in) -> {
                    TypedParameter mt = d.sampled(TYPED_PARAMETERS);
                    int arg;
                    if (mt.type().equals(NUMBER) && d.maybe(0.5)) {
                        arg = edgeNumber(d, b);
                    } else {
                        Type t = d.maybe(0.7) ? mt.type() : ANY;
                        arg = variable(d, b, t);
                    }
                    int jv = b.builtin(j());
                    recorded(b, () -> b.callMethod(jv, mt.method(), List.of(arg)));
                    return null;
                });

        generator("java_echo").family("java-interop").weight(6).inputs(ANY)
                .doc("`J.echo(x)`, `J.typeOf(x)`, `J.echo(x) === x`: which Java class the value arrives as.")
                .register((d, b, in) -> {
                    int v = in[0];
                    int jv = b.builtin(j());
                    int kind = d.ints(0, 2);
                    if (kind == 0) return b.declare(b.callMethod(jv, m("J_ECHO"), List.of(v)));
                    if (kind == 1) return b.declare(b.callMethod(jv, m("J_TYPE_OF"), List.of(v)));
                    return b.declare(b.compare(b.callMethod(jv, m("J_ECHO"), List.of(v)), v, CompareOperator.STRICT_EQUAL));
                });

        generator("java_echo_arith").family("java-interop").weight(5)
                .doc("The result of an arithmetic expression (where the compiler may keep an int) goes to Java.")
                .register((d, b, in) -> {
                    int x = edgeNumber(d, b), y = edgeNumber(d, b);
                    int value = d.sampled(ARITH_FORMS).apply(b, x, y);
                    int jv = b.builtin(j());
                    int r = b.declare(b.callMethod(jv, m("J_TYPE_OF"), List.of(value)));
                    if (d.maybe(0.5)) b.declare(b.callMethod(jv, m("J_ECHO"), List.of(value)));
                    return r;
                });

        generator("java_overload").family("java-interop").weight(5).block()
                .doc("`J.over(x)` is overloaded on int/long/double/String/boolean/Object: which one Rhino picks.")
                .register((d, b, in) -> {
                    int arg = d.maybe(0.5) ? edgeNumber(d, b) : variable(d, b);
                    int jv = b.builtin(j());
                    recorded(b, () -> b.callMethod(jv, m("J_OVER"), List.of(arg)));
                    return null;
                });

        generator("java_returned_value").family("java-interop").weight(5).block()
                .doc("A Java primitive or boxed value (a long beyond 2^53, a float, a byte, a char, a BigInteger, "
                        + "null) comes back into JavaScript and is used in arithmetic, comparison or concatenation.")
                .register((d, b, in) -> {
                    int jv = b.builtin(j());
                    int r = b.declare(b.callMethod(jv, d.sampled(RETURNING), List.of()));
                    Form1 form = d.sampled(USE_FORMS);
                    recorded(b, () -> form.apply(b, r));
                    return null;
                });

        generator("java_array").family("java-interop").weight(4).block()
                .doc("Java arrays (int[], double[], Object[], String[]) read and written from JavaScript, and a "
                        + "JavaScript array passed to a Java array parameter.")
                .register((d, b, in) -> {
                    int jv = b.builtin(j());
                    int kind = d.ints(0, 3);
                    int n = b.loadInt(d.ints(0, 5));
                    if (kind == 0) {
                        int a = b.declare(b.callMethod(jv, m("J_INT_ARRAY"), List.of(n)));
                        int v = edgeNumber(d, b);
                        int index = d.ints(0, 2);
                        recorded(b, () -> storeThenRead(b, a, index, v));
                        b.declare(b.createArray(List.of(b.getProperty(a, Env.k("LENGTH")),
                                b.callMethod(jv, m("J_TYPE_OF"), List.of(b.getElement(a, 0))),
                                b.callMethod(jv, m("J_SUM"), List.of(a)))));
                    } else if (kind == 1) {
                        int a = b.declare(b.callMethod(jv, m("J_DOUBLE_ARRAY"), List.of(n)));
                        int v = variable(d, b, NUMBER);
                        recorded(b, () -> b.binary(storeThenRead(b, a, 0, v), b.loadInt(1), BinaryOperator.ADD));
                    } else if (kind == 2) {
                        int a = b.declare(b.callMethod(jv, m("J_OBJECT_ARRAY"), List.of(n)));
                        int v = variable(d, b);
                        recorded(b, () -> b.callMethod(jv, m("J_TYPE_OF"), List.of(storeThenRead(b, a, 0, v))));
                    } else {
                        int arr = variable(d, b, ARRAY);
                        recorded(b, () -> b.callMethod(jv, m("J_SUM"), List.of(arr)));
                        b.declare(b.getProperty(b.callMethod(jv, m("J_STRING_ARRAY"), List.of()), Env.k("LENGTH")));
                    }
                    return null;
                });

        List<String> mapKeys = List.of("k", "d", "missing");
        generator("java_collections").family("java-interop").weight(3).block()
                .doc("java.util.List / Map seen from JavaScript: indexing, size, get, iteration with for-in.")
                .register((d, b, in) -> {
                    int jv = b.builtin(j());
                    int kind = d.ints(0, 2);
                    if (kind == 0) {
                        int lst = b.declare(b.callMethod(jv, m("J_LIST"), List.of()));
                        int form = d.ints(0, 2);
                        if (form == 0) {
                            b.declare(b.createArray(List.of(b.callMethod(jv, m("J_SIZE"), List.of(lst)),
                                    b.callMethod(jv, m("J_TYPE_OF"), List.of(b.getElement(lst, 0))))));
                        } else if (form == 1) {
                            b.declare(b.binary(b.getElement(lst, 0), b.getElement(lst, 1), BinaryOperator.ADD));
                        } else {
                            b.declare(b.callFunction(b.builtin(Env.builtin("STRING")), List.of(b.getElement(lst, 0))));
                        }
                    } else if (kind == 1) {
                        int mv = b.declare(b.callMethod(jv, m("J_MAP"), List.of()));
                        int k = b.loadString(d.sampled(mapKeys));
                        b.declare(b.callMethod(jv, m("J_TYPE_OF"), List.of(b.callMethod(jv, m("J_GET"), List.of(mv, k)))));
                    } else {
                        int acc = b.declare(b.createArray(List.of()));
                        try (var loop = b.forEach(ForEachKind.IN, b.callMethod(jv, m("J_LIST"), List.of()))) {
                            b.callMethod(acc, m("ARRAY_PUSH"), List.of(loop.var(0)));
                        }
                    }
                    return null;
                });

        generator("java_numeric_pipeline").family("java-interop").weight(5).produces(FUNCTION).block()
                .doc("A number-var function whose intermediate values are handed to `J.typeOf` at several points: "
                        + "the int or double representation the compiler chose becomes visible.")
                .register((d, b, in) -> {
                    int jv = b.builtin(j());
                    int f;
                    try (var fb = b.plainFunction(new Parameters(2), Builder.fn().types(NUMBER, NUMBER))) {
                        f = fb.f();
                        int p = fb.arg(0), q = fb.arg(1);
                        int sum = b.binary(p, q, BinaryOperator.ADD);
                        int x = b.declare(sum, declarationKind(d, b.options, true));
                        int t1 = b.declare(b.callMethod(jv, m("J_TYPE_OF"), List.of(x)));
                        b.reassign(x, d.sampled(ARITH_FORMS).apply(b, x, p));
                        int t2 = b.declare(b.callMethod(jv, m("J_TYPE_OF"), List.of(x)));
                        b.unary(UnaryOperator.POST_INC, x);
                        b.doReturn(b.createArray(List.of(t1, t2, b.callMethod(jv, m("J_TYPE_OF"), List.of(x)),
                                b.callMethod(jv, m("J_ECHO"), List.of(x)), b.callMethod(jv, m("J_OVER"), List.of(x)))));
                    }
                    int e1 = edgeNumber(d, b), e2 = edgeNumber(d, b);
                    b.declare(b.callFunction(f, List.of(e1, e2)));
                    int i1 = b.loadInt(d.ints(1, 3));
                    int i2 = b.loadInt(d.ints(1, 3));
                    b.declare(b.callFunction(f, List.of(i1, i2)));
                    int fl = b.loadFloat(d.sampled(EDGE_FLOATS));
                    b.declare(b.callFunction(f, List.of(fl, variable(d, b))));
                    return f;
                });
    }
}
