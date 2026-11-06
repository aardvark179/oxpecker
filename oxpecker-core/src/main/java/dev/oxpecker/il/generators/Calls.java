// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/CodeGen/CodeGenerators.swift at commit be2811d00282320381fc8007d64873745772011d
// (functions 1673-1805, calls 2268-2455, ThisGenerator 788, ArgumentsAccessGenerator 792,
// FunctionWithArgumentsAccessGenerator 796, LoadNewTargetGenerator 3599, Api*CallGenerator 3604-3631).
// Weights from CodeGeneratorWeights.swift. See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.argumentsFor;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.nested;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.syntaxOk;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.Builtin;
import dev.oxpecker.il.Context;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Parameters;
import dev.oxpecker.il.Type;
import java.util.ArrayList;
import java.util.List;

/** Functions and calls (calls.py). */
public final class Calls {
    private Calls() {}

    /** calls.parameters' result: the parameter list and the declared parameter types. */
    public record Params(Parameters params, List<Type> types) {}

    static final List<Type> PARAMETER_TYPE_CANDIDATES = List.of(NUMBER, STRING, OBJECT, FUNCTION, ARRAY);

    public static Params parameters(Draw d, Builder b) { return parameters(d, b, false, false); }

    /**
     * A parameter list sized by the visible variables, with a rest parameter sometimes
     * (ProgramBuilder.swift:635-701) and types drawn from what is visible. {@code simple} forbids
     * the rest parameter: a function with a "use strict" directive must have a simple parameter
     * list. {@code arrow} asks the capability table separately: Rhino 1.9.2 parses
     * {@code function (a, ...r)} but not {@code (a, ...r) =>}.
     */
    public static Params parameters(Draw d, Builder b, boolean simple, boolean arrow) {
        return d.draw(dd -> {
            int nVisible = b.visible().size();
            int count = nVisible <= 1 ? 0 : nVisible <= 5 ? dd.ints(1, 2) : dd.ints(2, 4);
            boolean restOk = arrow ? syntaxOk("arrow-rest") : syntaxOk("rest-params");
            boolean hasRest = count > 0 && !simple && restOk && dd.maybe(0.2);
            List<Type> types = new ArrayList<>();
            for (int k = 0; k < count; k++) types.add(dd.maybe(0.2) ? ANY : dd.sampled(PARAMETER_TYPE_CANDIDATES));
            return new Params(new Parameters(count, hasRest), List.copyOf(types));
        });
    }

    /**
     * The {@link Builtin} behind {@code v} when it is a builtin, possibly through
     * {@code let f = WeakMap}-style declarations; null otherwise.
     */
    public static Builtin definingBuiltin(Builder b, int v) {
        int seen = 0;
        while (b.defs.containsKey(v) && seen < 8) {
            var instr = b.code.get(b.defs.get(v));
            if (instr.op() instanceof Ops.LoadBuiltin lb) return lb.builtin();
            if (instr.op() instanceof Ops.Declare) {
                v = instr.input(0);
                seen++;
                continue;
            }
            return null;
        }
        return null;
    }

    /** A builtin constructor the registry knows is not callable without {@code new} (Set, Map, Promise, ...). */
    public static boolean notCallable(Builder b, int f) {
        Builtin builtin = definingBuiltin(b, f);
        return builtin != null && builtin.calls() == null && builtin.constructs() != null;
    }

    /** {@code b.do_return(pick(draw, b.visible()))} when anything is visible. */
    private static void returnVisible(Draw d, Builder b) {
        if (!b.visible().isEmpty()) b.doReturn(pick(d, b.visible()));
    }

    static void register() {
        generator("plain_function").family("calls").weight(15).block().produces(FUNCTION).source("CodeGenerators.swift:1673")
                .doc("A function with drawn parameters and a generated body that returns a value; then a call.")
                .register((d, b, in) -> {
                    Params p = parameters(d, b);
                    int f;
                    try (var fb = b.plainFunction(p.params(), Builder.fn().named(d.maybe(0.5)).types(p.types()))) {
                        f = fb.f();
                        nested(d, b);
                        returnVisible(d, b);
                    }
                    b.callFunction(f, argumentsFor(d, b, f));
                    return f;
                });

        generator("strict_function").family("calls").weight(3).block().produces(FUNCTION).source("CodeGenerators.swift:1703")
                .doc("A strict-mode function with a generated body, then a call.")
                .register((d, b, in) -> {
                    Params p = parameters(d, b, true, false);
                    int f;
                    try (var fb = b.plainFunction(p.params(), Builder.fn().named(d.maybe(0.5)).strict(true).types(p.types()))) {
                        f = fb.f();
                        nested(d, b);
                        returnVisible(d, b);
                    }
                    b.callFunction(f, argumentsFor(d, b, f));
                    return f;
                });

        generator("arrow_function").family("calls").weight(3).block().produces(FUNCTION).source("CodeGenerators.swift:1736")
                .doc("An arrow function with a generated body (no call: arrows are mostly used as arguments).")
                .register((d, b, in) -> {
                    Params p = parameters(d, b, false, true);
                    int f;
                    try (var fb = b.arrowFunction(p.params(), Builder.fn().types(p.types()))) {
                        f = fb.f();
                        nested(d, b);
                        returnVisible(d, b);
                    }
                    return f;
                });

        generator("generator_function").family("calls").weight(3).block().toggle("generators").produces(FUNCTION)
                .source("CodeGenerators.swift:1765")
                .doc("A generator function yielding values, then called.")
                .register((d, b, in) -> {
                    Params p = parameters(d, b);
                    int f;
                    try (var fb = b.generatorFunction(p.params(), Builder.fn().named(d.maybe(0.5)).types(p.types()))) {
                        f = fb.f();
                        nested(d, b);
                        if (d.bool()) b.yield_(variable(d, b));
                        else b.yieldEach(b.createArray(d.lists(1, 5, dd -> variable(dd, b))));
                        returnVisible(d, b);
                    }
                    b.callFunction(f, argumentsFor(d, b, f));
                    return f;
                });

        generator("function_call").family("calls").weight(30).inputs(FUNCTION).source("CodeGenerators.swift:2342")
                .doc("Call a function with arguments drawn for its signature; a builtin constructor the registry "
                        + "knows is not callable without `new` (Set, Map, Promise, ...) is skipped.")
                .register((d, b, in) -> {
                    int f = in[0];
                    if (!b.typeOf(f).mayBe(FUNCTION) || notCallable(b, f)) return null;
                    List<Integer> args = argumentsFor(d, b, f);
                    return b.callFunction(f, args, d.maybe(0.05));
                });

        generator("constructor_call").family("calls").weight(20).inputs(FUNCTION).source("CodeGenerators.swift:2347")
                .doc("`new C(args)`; a builtin the registry knows is not constructible (Symbol, BigInt) is skipped.")
                .register((d, b, in) -> {
                    int c = in[0];
                    if (!b.typeOf(c).mayBe(FUNCTION)) return null;
                    Builtin builtin = definingBuiltin(b, c);
                    if (builtin != null && builtin.constructs() == null && builtin.calls() != null) return null;
                    return b.construct(c, argumentsFor(d, b, c));
                });

        generator("method_call").family("calls").weight(30).inputs(OBJECT).source("CodeGenerators.swift:2268")
                .doc("Call a method the receiver's type is known to have, with arguments drawn for its parameters.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    List<Method> methods = Env.methodsOf(b.typeOf(obj).group());
                    if (methods.isEmpty()) return null;
                    Method m = pick(d, methods);
                    List<Integer> args;
                    if (!m.params().isEmpty()) {
                        args = new ArrayList<>();
                        for (Type t : m.params()) args.add(variable(d, b, t));
                    } else {
                        args = d.lists(0, m.arity(), dd -> variable(dd, b));
                    }
                    return b.callMethod(obj, m, args, d.maybe(0.05));
                });

        generator("computed_method_call").family("calls").weight(10).inputs(OBJECT).source("CodeGenerators.swift:2305")
                .doc("`obj[name](args)` for a method name of the receiver's type.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    List<Method> methods = Env.methodsOf(b.typeOf(obj).group());
                    if (methods.isEmpty()) return null;
                    Method m = pick(d, methods);
                    int key = b.loadString(m.name());
                    return b.callComputedMethod(obj, key, d.lists(0, m.arity(), dd -> variable(dd, b)));
                });

        generator("return").family("calls").weight(3).inputs(ANY).context(Context.SUBROUTINE).source("CodeGenerators.swift:2429")
                .doc("`return x` (or a bare return).")
                .register((d, b, in) -> {
                    if (d.maybe(0.9)) b.doReturn(in[0]);
                    else b.doReturn();
                    return null;
                });

        generator("yield").family("calls").weight(5).inputs(ANY).context(Context.GENERATOR_FUNCTION).toggle("generators")
                .source("CodeGenerators.swift:2439")
                .doc("`yield x`.")
                .register((d, b, in) -> d.maybe(0.9) ? b.yield_(in[0]) : b.yield_());

        generator("yield_each").family("calls").weight(5).required(ARRAY).context(Context.GENERATOR_FUNCTION).toggle("generators")
                .source("CodeGenerators.swift:2448")
                .doc("`yield* iterable`.")
                .register((d, b, in) -> {
                    b.yieldEach(in[0]);
                    return null;
                });

        generator("this").family("calls").weight(3).source("CodeGenerators.swift:788")
                .doc("`this`.")
                .register((d, b, in) -> b.this_());

        generator("arguments_access").family("calls").weight(3).context(Context.SUBROUTINE).source("CodeGenerators.swift:792")
                .doc("`arguments` (forces an activation in Rhino).")
                .register((d, b, in) -> b.arguments());

        generator("function_with_arguments_access").family("calls").weight(2).block().produces(FUNCTION)
                .source("CodeGenerators.swift:796")
                .doc("A function reading `arguments`, called with a drawn number of arguments.")
                .register((d, b, in) -> {
                    Params p = parameters(d, b);
                    int f;
                    try (var fb = b.plainFunction(p.params(), Builder.fn().types(p.types()))) {
                        f = fb.f();
                        b.arguments();
                        nested(d, b);
                        b.doReturn(pick(d, b.visible()));
                    }
                    b.callFunction(f, d.lists(0, 5, dd -> variable(dd, b)));
                    return f;
                });

        generator("new_target").family("calls").weight(3).context(Context.SUBROUTINE).source("CodeGenerators.swift:3599")
                .syntax("new-target")
                .doc("`new.target`.")
                .register((d, b, in) -> b.newTarget());

        generator("unbound_function_call").family("calls").weight(10).inputs(FUNCTION).source("CodeGenerators.swift:2381")
                .doc("`f.call(receiver, args)`, `f.apply(receiver, [args])` or `f.bind(receiver, args)`.")
                .register((d, b, in) -> {
                    int f = in[0];
                    int receiver = variable(d, b, OBJECT);
                    List<Integer> args = argumentsFor(d, b, f);
                    int kind = d.ints(0, 2);
                    if (kind == 0) return b.callMethod(f, Env.method("FUNCTION_CALL"), cons(receiver, args));
                    if (kind == 1) return b.callMethod(f, Env.method("FUNCTION_APPLY"), List.of(receiver, b.createArray(args)));
                    int keep = d.ints(0, args.size());
                    return b.callMethod(f, Env.method("FUNCTION_BIND"), cons(receiver, args.subList(0, keep)));
                });

        generator("api_method_call").family("api").weight(15).inputs(OBJECT).source("CodeGenerators.swift:3617")
                .doc("Call a probed method of a builtin receiver with arguments of its declared types, or any values.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    List<Method> methods = Env.methodsOf(b.typeOf(obj).group());
                    if (methods.isEmpty()) return null;
                    Method m = pick(d, methods);
                    List<Integer> args = new ArrayList<>();
                    for (Type t : m.params()) args.add(variable(d, b, t));
                    if (args.isEmpty()) args = d.lists(0, Math.min(m.arity(), 3), dd -> variable(dd, b));
                    return b.callMethod(obj, m, args);
                });

        generator("api_function_call").family("api").weight(15).inputs(FUNCTION).source("CodeGenerators.swift:3631")
                .doc("Call a builtin function with drawn arguments (not a constructor that needs `new`).")
                .register((d, b, in) -> {
                    int f = in[0];
                    if (!b.typeOf(f).mayBe(FUNCTION) || notCallable(b, f)) return null;
                    return b.callFunction(f, argumentsFor(d, b, f));
                });
    }

    private static List<Integer> cons(int x, List<Integer> xs) {
        List<Integer> out = new ArrayList<>(xs.size() + 1);
        out.add(x);
        out.addAll(xs);
        return out;
    }
}
