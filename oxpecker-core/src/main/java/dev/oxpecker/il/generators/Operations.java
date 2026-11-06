// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/CodeGen/CodeGenerators.swift at commit be2811d00282320381fc8007d64873745772011d
// (operators 2193-2487, ChainedReassignments 3001, NumberComputationGenerator 3355,
// TemplateStringGenerator 1615, ConcatenatedStringGenerator 297). Weights from CodeGeneratorWeights.swift.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.assignable;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.operand;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.poisonsArithmetic;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.SYMBOL;

import dev.oxpecker.il.BinaryOperator;
import dev.oxpecker.il.Builtin;
import dev.oxpecker.il.CompareOperator;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Radix;
import dev.oxpecker.il.UnaryOperator;
import java.util.ArrayList;
import java.util.List;

/** Operators, comparisons, assignments, conversions (operations.py). */
public final class Operations {
    private Operations() {}

    public static final List<UnaryOperator> UNARY_OPERATORS = List.of(UnaryOperator.values());
    public static final List<BinaryOperator> BINARY_OPERATORS = List.of(BinaryOperator.values());
    /** Python's {@code Comparator} enum, in definition order. */
    public static final List<CompareOperator> COMPARATORS = List.of(CompareOperator.values());
    public static final List<CompareOperator> RELATIONAL = List.of(CompareOperator.LESS, CompareOperator.LESS_EQUAL,
            CompareOperator.GREATER, CompareOperator.GREATER_EQUAL);
    public static final List<Method> MATH_UNARY = List.of(Env.method("MATH_ABS"), Env.method("MATH_FLOOR"),
            Env.method("MATH_SQRT"), Env.method("MATH_ROUND"), Env.method("MATH_TRUNC"));
    public static final List<Method> MATH_BINARY = List.of(Env.method("MATH_IMUL"), Env.method("MATH_MAX"),
            Env.method("MATH_MIN"), Env.method("MATH_POW"));

    /** One entry of {@link #INT32_COERCIONS}: {@code x op k} with {@code k} in {@code radix}. */
    public record Int32Coercion(BinaryOperator op, long k, Radix radix) {}

    /** {@code x | 0, x >>> 0, x >> 0, x & 0xffffffff}. */
    public static final List<Int32Coercion> INT32_COERCIONS = List.of(
            new Int32Coercion(BinaryOperator.BIT_OR, 0, Radix.DEC), new Int32Coercion(BinaryOperator.U_RSHIFT, 0, Radix.DEC),
            new Int32Coercion(BinaryOperator.RSHIFT, 0, Radix.DEC), new Int32Coercion(BinaryOperator.BIT_AND, 0xFFFFFFFFL, Radix.HEX));

    static final List<UnaryOperator> NUMBER_COMPUTATION_UNARY = List.of(UnaryOperator.MINUS, UnaryOperator.PLUS,
            UnaryOperator.BITWISE_NOT, UnaryOperator.LOGICAL_NOT);
    static final List<Long> TO_STRING_RADIXES = List.of(10L, 2L, 16L, 36L, 8L);

    static void register() {
        generator("unary").family("operations").weight(10).inputs(ANY).source("CodeGenerators.swift:2463")
                .doc("A unary operator on a value; increments need an assignable variable; `-`, `+`, `~` get an "
                        + "arithmetic operand (a Symbol or BigInt there throws).")
                .register((d, b, in) -> {
                    int x = in[0];
                    UnaryOperator op = d.sampled(UNARY_OPERATORS);
                    if (op.reassignsInput()) {
                        x = assignable(d, b, NUMBER);
                    } else if ((op == UnaryOperator.MINUS || op == UnaryOperator.PLUS || op == UnaryOperator.BITWISE_NOT)
                            && poisonsArithmetic(b, x)) {
                        x = operand(d, b);
                    }
                    return b.unary(op, x);
                });

        generator("binary").family("operations").weight(40).inputs(ANY, ANY).source("CodeGenerators.swift:2467")
                .doc("`lhs op rhs` for a drawn binary operator; a Symbol or a BigInt mixed with a Number on either "
                        + "side is replaced by an arithmetic operand most of the time (G46).")
                .register((d, b, in) -> {
                    int lhs = in[0], rhs = in[1];
                    BinaryOperator op = d.sampled(BINARY_OPERATORS);
                    if (!BinaryOperator.LOGICAL.contains(op)) {
                        if (poisonsArithmetic(b, lhs)) lhs = operand(d, b);
                        if (poisonsArithmetic(b, rhs) || (b.typeOf(lhs).is(BIGINT) != b.typeOf(rhs).is(BIGINT)))
                            rhs = operand(d, b, lhs);
                    }
                    return b.binary(lhs, rhs, op);
                });

        generator("ternary").family("operations").weight(5).inputs(ANY, ANY).source("CodeGenerators.swift:2471")
                .doc("`lhs cmp rhs ? lhs : rhs`.")
                .register((d, b, in) -> {
                    int lhs = in[0], rhs = in[1];
                    CompareOperator op = d.sampled(COMPARATORS);
                    if (RELATIONAL.contains(op) && poisonsArithmetic(b, lhs)) lhs = operand(d, b);
                    if (RELATIONAL.contains(op) && poisonsArithmetic(b, rhs)) rhs = operand(d, b, lhs);
                    int cond = b.compare(lhs, rhs, op);
                    return b.ternary(cond, lhs, rhs);
                });

        generator("compare").family("operations").weight(10).inputs(ANY, ANY).source("CodeGenerators.swift:2580")
                .doc("A relational or equality comparison; a relational one on a Symbol throws, so Symbols are "
                        + "swapped out of those most of the time (G46).")
                .register((d, b, in) -> {
                    int lhs = in[0], rhs = in[1];
                    CompareOperator op = d.sampled(COMPARATORS);
                    if (RELATIONAL.contains(op) && poisonsArithmetic(b, lhs)) lhs = operand(d, b);
                    if (RELATIONAL.contains(op) && poisonsArithmetic(b, rhs)) rhs = operand(d, b, lhs);
                    return b.compare(lhs, rhs, op);
                });

        generator("update").family("operations").weight(20).source("CodeGenerators.swift:2477")
                .doc("`v op= w` on an assignable variable with a value of its type.")
                .register((d, b, in) -> {
                    int v = assignable(d, b);
                    int w = variable(d, b, b.typeOf(v));
                    b.update(v, w, d.sampled(BINARY_OPERATORS));
                    return null;
                });

        generator("reassignment").family("operations").weight(40).source("CodeGenerators.swift:2487")
                .doc("`v = w` on an assignable variable with a value of its type (or any value, sometimes).")
                .register((d, b, in) -> {
                    int v = assignable(d, b);
                    int w = variable(d, b, d.maybe(0.7) ? b.typeOf(v) : ANY);
                    if (w != v) b.reassign(v, w);
                    return null;
                });

        generator("chained_reassignments").family("operations").weight(5).source("CodeGenerators.swift:3001")
                .doc("A chain `a = b; b = c; ...` over assignable variables, in one of two directions.")
                .register((d, b, in) -> {
                    List<Integer> vs = d.lists(2, 5, dd -> assignable(dd, b));
                    boolean forward = d.bool();
                    for (int k = 0; k + 1 < vs.size(); k++) {
                        int x = vs.get(k), y = vs.get(k + 1);
                        if (x != y) {
                            if (forward) b.reassign(y, x);
                            else b.reassign(x, y);
                        }
                    }
                    return null;
                });

        generator("type_test").family("operations").weight(5).inputs(ANY).source("CodeGenerators.swift:2193")
                .doc("`typeof x === \"<type name>\"`.")
                .register((d, b, in) -> {
                    int t = b.typeof(in[0]);
                    return b.compare(t, b.loadString(d.sampled(Env.JS_TYPE_NAMES)), CompareOperator.STRICT_EQUAL);
                });

        generator("void").family("operations").weight(1).inputs(ANY).source("CodeGenerators.swift:2201")
                .doc("`void x`.")
                .register((d, b, in) -> b.void_(in[0]));

        generator("instance_of").family("operations").weight(5).inputs(ANY, FUNCTION).source("CodeGenerators.swift:2257")
                .doc("`x instanceof C`.")
                .register((d, b, in) -> b.testInstanceOf(in[0], in[1]));

        generator("in").family("operations").weight(3).inputs(OBJECT).source("CodeGenerators.swift:2263")
                .doc("`k in obj` for a visible key value.")
                .register((d, b, in) -> b.testIn(variable(d, b), in[0]));

        generator("number_computation").family("operations").weight(40).source("CodeGenerators.swift:3355")
                .doc("A few steps of arithmetic, unary operators and Math calls over visible values and fresh literals.")
                .register((d, b, in) -> {
                    int math = b.builtin(Env.builtin("MATH"));
                    List<Integer> values = new ArrayList<>(d.lists(1, 3, dd -> operand(dd, b)));
                    int literals = d.ints(1, 2);
                    for (int k = 0; k < literals; k++) values.add(b.loadInt(d.sampled(Env.INTERESTING_INTEGERS)));
                    if (d.maybe(0.5)) values.add(b.loadFloat(d.sampled(Env.INTERESTING_FLOATS)));
                    int last = values.get(values.size() - 1);
                    for (int step : d.lists(1, 7, dd -> dd.ints(0, 2))) {
                        if (step == 0) {
                            int x = pick(d, values), y = pick(d, values);
                            last = b.binary(x, y, d.sampled(BINARY_OPERATORS));
                        } else if (step == 1) {
                            UnaryOperator op = d.sampled(NUMBER_COMPUTATION_UNARY);
                            last = b.unary(op, pick(d, values));
                        } else if (d.bool()) {
                            Method m = d.sampled(MATH_UNARY);
                            last = b.callMethod(math, m, List.of(pick(d, values)));
                        } else {
                            Method m = d.sampled(MATH_BINARY);
                            int x = pick(d, values), y = pick(d, values);
                            last = b.callMethod(math, m, List.of(x, y));
                        }
                        values.add(last);
                    }
                    return last;
                });

        generator("int32_coerce").family("operations").weight(4).produces(INTEGER)
                .doc("`x | 0`, `x >>> 0`, `x >> 0`, `x & 0xffffffff` or `~~x` on a number.")
                .register((d, b, in) -> {
                    int x = variable(d, b, NUMBER);
                    if (d.maybe(0.2)) return b.unary(UnaryOperator.BITWISE_NOT, b.unary(UnaryOperator.BITWISE_NOT, x));
                    Int32Coercion c = d.sampled(INT32_COERCIONS);
                    return b.binary(x, b.loadInt(c.k(), c.radix()), c.op());
                });

        generator("template_string").family("operations").weight(1).produces(STRING).source("CodeGenerators.swift:1615")
                .doc("A template literal interpolating visible values between drawn string parts (a Symbol "
                        + "cannot be converted to a string: an arithmetic operand instead).")
                .register((d, b, in) -> {
                    List<Integer> values = d.lists(0, 3, dd -> operand(dd, b));
                    List<String> parts = new ArrayList<>();
                    for (int k = 0; k < values.size() + 1; k++) parts.add(d.sampled(Env.INTERESTING_STRINGS));
                    return b.createTemplate(parts, values);
                });

        generator("concatenated_string").family("operations").weight(2).required(STRING).produces(STRING)
                .source("CodeGenerators.swift:297")
                .doc("`(c ? s : t) + u` over string values: a ConsString whose halves depend on a condition.")
                .register((d, b, in) -> {
                    int s = in[0];
                    int other = pick(d, b.pool(STRING).exact());
                    int cond = variable(d, b);
                    int select = b.ternary(cond, s, other);
                    int third = pick(d, b.pool(STRING).exact());
                    return d.bool() ? b.binary(select, third, BinaryOperator.ADD) : b.binary(third, select, BinaryOperator.ADD);
                });

        List<Builtin> conversions = List.of(Env.builtin("NUMBER"), Env.builtin("PARSE_INT"), Env.builtin("PARSE_FLOAT"));
        generator("number_conversion").family("operations").weight(3).inputs(ANY).produces(NUMBER)
                .doc("`Number(x)`, `parseInt(x)`, `parseFloat(x)` or `+x` (not on a Symbol: every one of them throws).")
                .register((d, b, in) -> {
                    int x = in[0];
                    if (b.typeOf(x).is(SYMBOL)) x = operand(d, b);
                    int kind = d.ints(0, 3);
                    if (kind == 0) return b.unary(UnaryOperator.PLUS, x);
                    return b.callFunction(b.builtin(conversions.get(kind - 1)), List.of(x));
                });

        generator("number_to_string").family("operations").weight(3).required(NUMBER).produces(STRING)
                .doc("`String(n)`, `n.toString(radix)`, `n.toFixed(d)` or `n.toPrecision(p)`.")
                .register((d, b, in) -> {
                    int n = in[0];
                    int kind = d.ints(0, 3);
                    if (kind == 0) return b.callFunction(b.builtin(Env.builtin("STRING")), List.of(n));
                    if (kind == 1)
                        return b.callMethod(n, Env.method("NUMBER_TO_STRING"), List.of(b.loadInt(d.sampled(TO_STRING_RADIXES))));
                    if (kind == 2) return b.callMethod(n, Env.method("NUMBER_TO_FIXED"), List.of(b.loadInt(d.ints(0, 20))));
                    return b.callMethod(n, Env.method("NUMBER_TO_PRECISION"), List.of(b.loadInt(d.ints(1, 21))));
                });
    }
}
