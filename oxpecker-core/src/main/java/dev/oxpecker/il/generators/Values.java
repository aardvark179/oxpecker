// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/CodeGen/CodeGenerators.swift at commit be2811d00282320381fc8007d64873745772011d
// (the value generators, lines 281-760, and BuiltinGenerator 1646), with the value pools of
// Sources/Fuzzilli/Environment/JavaScriptEnvironment.swift. Weights from CodeGeneratorWeights.swift.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FLOAT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NULL;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.SYMBOL;
import static dev.oxpecker.il.Type.UNDEFINED;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.Builtin;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.Flags;
import dev.oxpecker.il.FloatStyle;
import dev.oxpecker.il.Key;
import dev.oxpecker.il.Parameters;
import dev.oxpecker.il.Radix;
import dev.oxpecker.il.RegExpFlag;
import dev.oxpecker.il.Type;
import dev.oxpecker.il.UnaryOperator;
import java.util.List;

/**
 * Value generators (values.py): literals and simple objects that can be made with no visible
 * variables. Each is also the value maker for its type (the fallback of {@code variable(t)} on an
 * empty pool).
 */
public final class Values {
    private Values() {}

    static final List<Flags> REGEXP_FLAG_SETS = List.of(
            RegExpFlag.NONE, RegExpFlag.of(RegExpFlag.GLOBAL), RegExpFlag.of(RegExpFlag.IGNORE_CASE),
            RegExpFlag.of(RegExpFlag.GLOBAL | RegExpFlag.IGNORE_CASE), RegExpFlag.of(RegExpFlag.MULTILINE),
            RegExpFlag.of(RegExpFlag.STICKY), RegExpFlag.of(RegExpFlag.DOT_ALL), RegExpFlag.of(RegExpFlag.UNICODE));
    static final List<Radix> RADIXES = List.of(Radix.values());
    static final List<FloatStyle> FLOAT_STYLES = List.of(FloatStyle.values());

    /** {@code SMALL_INTEGERS = st.integers(0, 0x10000)}. */
    static long smallInteger(Draw d) { return d.ints(0, 0x10000); }

    /** {@code st.one_of(st.sampled_from(INTERESTING_INTEGERS), SMALL_INTEGERS)}. */
    static long interestingOrSmall(Draw d) {
        return d.<Long>oneOf(dd -> dd.sampled(Env.INTERESTING_INTEGERS), dd -> smallInteger(dd));
    }

    static void register() {
        generator("integer").family("values").weight(20).rank(0).produces(INTEGER).source("CodeGenerators.swift:281")
                .doc("An integer literal: an interesting value or a small one, in a drawn radix.")
                .valueMaker(ANY).valueMaker(NUMBER).valueMaker(INTEGER)
                .register((d, b, in) -> {
                    long value = interestingOrSmall(d);
                    return b.loadInt(value, d.sampled(RADIXES));
                });

        generator("bigint").family("values").weight(4).produces(BIGINT).source("CodeGenerators.swift:285")
                .doc("A BigInt literal.").valueMaker(BIGINT)
                .register((d, b, in) -> b.loadBigInt(interestingOrSmall(d)));

        generator("float").family("values").weight(10).rank(1).produces(FLOAT).source("CodeGenerators.swift:289")
                .doc("A float literal, plain or in exponent form.").valueMaker(FLOAT)
                .register((d, b, in) -> {
                    double value = d.<Double>oneOf(dd -> dd.sampled(Env.INTERESTING_FLOATS), dd -> dd.floats(0.0, 1e6));
                    return b.loadFloat(value, d.sampled(FLOAT_STYLES));
                });

        generator("negative_number").family("values").weight(6).produces(NUMBER)
                .doc("`-x` of a number literal (JavaScript has no negative literals): -1, -0, -1.5, -0x10.")
                .register((d, b, in) -> {
                    int x;
                    if (d.maybe(0.3)) x = b.loadFloat(d.sampled(Env.INTERESTING_FLOATS));
                    else x = b.loadInt(d.sampled(Env.INTERESTING_INTEGERS), d.sampled(RADIXES));
                    return b.unary(UnaryOperator.MINUS, x);
                });

        generator("special_number").family("values").weight(4).produces(NUMBER)
                .doc("NaN, Infinity or -Infinity.")
                .register((d, b, in) -> {
                    int v = b.builtin(d.sampled(List.of(Env.builtin("NAN"), Env.builtin("INFINITY"))));
                    if (d.maybe(0.3)) v = b.unary(UnaryOperator.MINUS, v);
                    return v;
                });

        generator("string").family("values").weight(10).rank(2).produces(STRING).source("CodeGenerators.swift:293")
                .doc("A string literal: empty, numeric-looking, unicode, a lone surrogate, a property name.")
                .valueMaker(STRING)
                .register((d, b, in) -> b.loadString(d.<String>oneOf(dd -> dd.sampled(Env.INTERESTING_STRINGS), dd -> dd.text(6))));

        generator("boolean").family("values").weight(2).rank(3).produces(BOOLEAN).source("CodeGenerators.swift:314")
                .doc("A boolean literal.").valueMaker(BOOLEAN)
                .register((d, b, in) -> b.loadBool(d.bool()));

        generator("undefined").family("values").weight(1).produces(UNDEFINED).source("CodeGenerators.swift:319")
                .doc("`undefined`.").valueMaker(UNDEFINED)
                .register((d, b, in) -> b.loadUndefined());

        generator("null").family("values").weight(1).produces(NULL).source("CodeGenerators.swift:324")
                .doc("`null`.").valueMaker(NULL)
                .register((d, b, in) -> b.loadNull());

        generator("array").family("values").weight(10).produces(ARRAY).source("CodeGenerators.swift:329")
                .doc("An array literal of visible values.").valueMaker(ARRAY)
                .register((d, b, in) -> {
                    if (b.visible().isEmpty()) return b.createArray(List.of());
                    return b.createArray(d.lists(0, 5, dd -> variable(dd, b)));
                });

        generator("int_array").family("values").weight(10).produces(ARRAY).source("CodeGenerators.swift:341")
                .doc("An array literal of integers.")
                .register((d, b, in) -> b.createIntArray(d.lists(1, 10,
                        dd -> dd.<Long>oneOf(e -> e.sampled(Env.INTERESTING_INTEGERS), e -> (long) e.ints(-10, 10)))));

        generator("float_array").family("values").weight(10).produces(ARRAY).source("CodeGenerators.swift:346")
                .doc("An array literal of floats.")
                .register((d, b, in) -> b.createFloatArray(d.lists(1, 10, dd -> dd.sampled(Env.INTERESTING_FLOATS))));

        generator("regexp").family("values").weight(5).produces(Type.object("RegExp")).source("CodeGenerators.swift:622")
                .doc("A regular expression literal from the pattern table, with drawn flags.")
                .register((d, b, in) -> {
                    Env.RegExpPattern p = d.sampled(Env.REGEXP_PATTERNS);
                    Flags flags = d.sampled(REGEXP_FLAG_SETS);
                    return b.loadRegExp(p.pattern(), flags.with(flags.bits() & ~p.incompatible().bits()));
                });

        List<Builtin> instanceConstructors = List.of(Env.builtin("OBJECT"), Env.builtin("ARRAY"), Env.builtin("MAP"),
                Env.builtin("SET"), Env.builtin("WEAK_MAP"), Env.builtin("WEAK_SET"), Env.builtin("DATE"));
        generator("builtin_object_instance").family("values").weight(10).produces(OBJECT).source("CodeGenerators.swift:394")
                .doc("`new Object()`, `new Array(n)`, `new Map()`, `new Set()`, `new WeakMap()`, `new WeakSet()`, `new Date()`.")
                .valueMaker(OBJECT)
                .register((d, b, in) -> {
                    Builtin ctor = d.sampled(instanceConstructors);
                    List<Integer> args = ctor == Env.builtin("ARRAY") ? List.of(b.loadInt(d.ints(0, 16))) : List.of();
                    return b.construct(b.builtin(ctor), args);
                });

        generator("typed_array").family("values").weight(20).produces(Type.object("TypedArray")).source("CodeGenerators.swift:450")
                .doc("A typed array of a drawn kind and length.")
                .register((d, b, in) -> b.construct(b.builtin(d.sampled(Env.TYPED_ARRAY_CONSTRUCTORS)), List.of(b.loadInt(d.ints(0, 64)))));

        generator("trivial_function").family("values").weight(10).produces(FUNCTION).source("CodeGenerators.swift:748").block()
                .doc("A function with no parameters returning a visible value.")
                .register((d, b, in) -> {
                    int f;
                    try (var fb = b.plainFunction(new Parameters(0))) {
                        f = fb.f();
                        if (!b.visible().isEmpty()) b.doReturn(pick(d, b.visible()));
                    }
                    return f;
                });

        List<Builtin> functionBuiltins = List.of(Env.builtin("PARSE_INT"), Env.builtin("PARSE_FLOAT"), Env.builtin("STRING"),
                Env.builtin("NUMBER"), Env.builtin("BOOLEAN"), Env.builtin("OBJECT"), Env.builtin("ARRAY"),
                Env.builtin("IS_NAN"), Env.builtin("MAP"), Env.builtin("ERROR"));
        generator("builtin_function").family("values").weight(4).produces(FUNCTION)
                .doc("A builtin function or constructor as a value: the function maker that needs no block.")
                .valueMaker(FUNCTION)
                .register((d, b, in) -> b.builtin(d.sampled(functionBuiltins)));

        generator("builtin").family("values").weight(10).source("CodeGenerators.swift:1646")
                .doc("A reference to a global builtin.")
                .register((d, b, in) -> b.builtin(d.sampled(Env.BUILTIN_POOL)));

        generator("symbol").family("values").weight(2).produces(SYMBOL)
                .doc("A symbol: fresh, registered, or well-known.").valueMaker(SYMBOL)
                .register((d, b, in) -> {
                    int sym = b.builtin(Env.builtin("SYMBOL"));
                    int kind = d.ints(0, 2);
                    if (kind == 0) return b.callFunction(sym, List.of(b.loadString(d.sampled(Env.INTERESTING_STRINGS))));
                    if (kind == 1)
                        return b.callMethod(sym, Env.method("SYMBOL_CTOR_FOR"), List.of(b.loadString(d.sampled(Env.INTERESTING_STRINGS))));
                    List<Key> wellKnown = List.of(Env.k("ITERATOR"), Env.k("TO_PRIMITIVE"), Env.k("HAS_INSTANCE"), Env.k("TO_STRING_TAG"));
                    return b.getProperty(sym, d.sampled(wellKnown));
                });
    }
}
