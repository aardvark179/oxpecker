package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.declarationKind;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.NUMBER;

import dev.oxpecker.il.BinaryOperator;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Parameters;
import java.util.List;

/**
 * Continuation generators (continuations.py; toggle: continuations). Rhino implements
 * continuations only in the interpreter; the user wants the mode difference observed.
 *
 * <p>{@code __pause(v)} is a harness host function ({@code Builtins.PAUSE}): in an interpreted
 * script run through Context.executeScriptWithContinuations it captures the continuation, the
 * harness records {@code pause(v)} and resumes with {@code v} as the call's result; in compiled
 * mode the capture fails inside Rhino, which the comparator reports. {@code Continuation} is
 * Rhino's legacy JS-level API.
 */
public final class Continuations {
    private Continuations() {}

    private static int pause(Builder b, int value) {
        return b.callFunction(b.builtin(Env.builtin("PAUSE")), List.of(value));
    }

    private static Method push() { return Env.method("ARRAY_PUSH"); }

    static void register() {
        generator("pause_in_function").family("continuations").weight(4).toggle("continuations").block()
                .doc("A function that pauses mid-way; locals before and after the pause are observed.")
                .register((d, b, in) -> {
                    int f;
                    try (var fb = b.plainFunction(new Parameters(1), Builder.fn().types(NUMBER))) {
                        f = fb.f();
                        int p = fb.arg(0);
                        int sum = b.binary(p, b.loadInt(1), BinaryOperator.ADD);
                        int n = b.declare(sum, declarationKind(d, b.options, true));
                        int what = d.ints(0, 3);
                        // the Python tuple emits every alternative and passes one
                        int s = b.loadString(d.sampled(Env.INTERESTING_STRINGS));
                        int arr = b.createArray(List.of(n, p));
                        int undef = b.loadUndefined();
                        int value = List.of(n, s, arr, undef).get(what);
                        int r = b.declare(pause(b, value));
                        b.reassign(n, b.binary(n, b.loadInt(2), BinaryOperator.MUL));
                        b.doReturn(b.createArray(List.of(r, n)));
                    }
                    return b.declare(b.callFunction(f, List.of(variable(d, b, NUMBER))));
                });

        generator("pause_in_loop").family("continuations").weight(3).toggle("continuations").block()
                .doc("Several pauses from inside a loop, accumulating the resumed values.")
                .register((d, b, in) -> {
                    int acc = b.declare(b.createArray(List.of()));
                    try (var loop = b.repeat(d.ints(1, 4))) {
                        b.callMethod(acc, push(), List.of(pause(b, loop.counter())));
                    }
                    return b.declare(b.getProperty(acc, Env.k("LENGTH")));
                });

        generator("pause_in_try_finally").family("continuations").weight(3).toggle("continuations").block()
                .doc("Pause inside try/catch/finally: does finally run once, and in which order?")
                .register((d, b, in) -> {
                    int log = b.declare(b.createArray(List.of()));
                    try (var t = b.try_()) {
                        b.callMethod(log, push(), List.of(b.loadString("try")));
                        b.callMethod(log, push(), List.of(pause(b, b.loadString("in-try"))));
                        if (d.maybe(0.4)) b.throw_(b.loadString("after-pause"));
                        try (var e = t.catch_()) {
                            b.callMethod(log, push(), List.of(b.binary(b.loadString("catch:"), e.var(), BinaryOperator.ADD)));
                            if (d.maybe(0.5)) b.callMethod(log, push(), List.of(pause(b, b.loadString("in-catch"))));
                        }
                        try (var fin = t.finally_()) {
                            b.callMethod(log, push(), List.of(b.loadString("finally")));
                        }
                    }
                    return null;
                });

        generator("pause_through_frames").family("continuations").weight(2).toggle("continuations").block()
                .doc("Capturing across a non-JavaScript frame (`map` callback, `call`, `apply`, `eval`) is not "
                        + "allowed; what each mode reports for it is the question. No try/catch: Rhino's refusal is an "
                        + "IllegalStateException the harness reports as `unsupported(continuations)`, which the "
                        + "comparator knows; caught in script it becomes an InternalError that reads like a bug.")
                .register((d, b, in) -> {
                    int form = d.ints(0, 3);
                    if (form == 0) {
                        int cb;
                        try (var fb = b.plainFunction(new Parameters(1))) {
                            cb = fb.f();
                            b.doReturn(pause(b, fb.arg(0)));
                        }
                        b.declare(b.callMethod(b.createIntArray(List.of(1L, 2L)), Env.method("ARRAY_MAP"), List.of(cb)));
                    } else if (form == 1) {
                        int g;
                        try (var fb = b.plainFunction(new Parameters(0))) {
                            g = fb.f();
                            b.doReturn(pause(b, b.loadInt(2)));
                        }
                        b.declare(b.callMethod(g, Env.method("FUNCTION_CALL"), List.of(b.loadNull())));
                    } else if (form == 2) {
                        b.declare(b.callMethod(b.builtin(Env.builtin("PAUSE")), Env.method("FUNCTION_APPLY"),
                                List.of(b.loadNull(), b.createIntArray(List.of(4L)))));
                    } else {
                        var cs = b.codeString();
                        try (cs) {
                            pause(b, b.loadInt(1));
                        }
                        b.declare(b.callFunction(b.builtin(Env.builtin("EVAL")), List.of(cs.result())));
                    }
                    return null;
                });

        generator("native_continuation").family("continuations").weight(2).toggle("continuations").block()
                .doc("Rhino's legacy `new Continuation()` object: constructing it, converting it, calling it. "
                        + "No try/catch (see `pause_through_frames`): in the compiled mode the construction fails with "
                        + "an exception the harness classifies as unsupported, a known mode difference.")
                .register((d, b, in) -> {
                    int form = d.ints(0, 3);
                    int ctor = b.builtin(Env.builtin("CONTINUATION"));
                    if (form == 0) {
                        b.declare(b.typeof(b.construct(ctor)));
                    } else if (form == 1) {
                        b.declare(b.callFunction(b.builtin(Env.builtin("STRING")), List.of(b.construct(ctor))));
                    } else {
                        int g;
                        try (var fb = b.plainFunction(new Parameters(0))) {
                            g = fb.f();
                            int k = b.declare(b.construct(ctor));
                            if (form == 3) b.callFunction(k, List.of(b.loadInt(7)));
                            b.doReturn(b.typeof(k));
                        }
                        b.declare(b.callFunction(g, List.of()));
                    }
                    return null;
                });
    }
}
