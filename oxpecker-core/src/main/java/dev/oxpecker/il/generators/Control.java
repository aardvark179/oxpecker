// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/CodeGen/CodeGenerators.swift at commit be2811d00282320381fc8007d64873745772011d
// (control flow 2712-3137, BlockStatement/BlockBreak 3338-3351, WithStatement 3296, Eval 3323).
// Begin/End stubs are one Python block each (docs/design-generation-layer.md §7.2); the code between
// them is `nested(b)`. Weights from CodeGeneratorWeights.swift.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.nested;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.OBJECT;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.Analysis;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.CompareOperator;
import dev.oxpecker.il.Context;
import dev.oxpecker.il.DeclarationKind;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.ForEachKind;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Type;
import dev.oxpecker.il.UnaryOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Control flow: conditionals, loops, breaks, try/catch, switch, blocks, with, eval (control.py). */
public final class Control {
    private Control() {}

    /** A for-header variable is reassigned by the afterthought: var or let, never const. */
    public static final List<DeclarationKind> LOOP_DECLARATION_KINDS = List.of(DeclarationKind.VAR, DeclarationKind.LET);

    /** {@code LOOP_DECLARATIONS = st.sampled_from((VAR, LET))}. */
    public static DeclarationKind loopDeclaration(Draw d) { return d.sampled(LOOP_DECLARATION_KINDS); }

    /**
     * Visible labels of enclosing loops (not of blocks, ifs or switches: {@code break label} to those
     * is a BlockBreak, and {@code continue label} needs a loop).
     */
    public static List<Integer> loopLabels(Builder b) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : b.labels.entrySet()) {
            int v = e.getKey();
            if (b.inScope(v) && !b.isHidden(v) && Ops.isAny(b.code.get(e.getValue()).op(), Analysis.LOOP_BODIES)) out.add(v);
        }
        return out;
    }

    static void register() {
        generator("if_else").family("control").weight(10).inputs(BOOLEAN).block().source("CodeGenerators.swift:2712")
                .doc("`if (cond) {...} else {...}` with generated bodies; the else is optional.")
                .register((d, b, in) -> {
                    try (var br = b.if_(in[0], d.maybe(0.2))) {
                        nested(d, b);
                        if (d.maybe(0.6)) {
                            try (var e = br.else_()) {
                                nested(d, b);
                            }
                        }
                    }
                    return null;
                });

        generator("compare_with_if_else").family("control").weight(15).inputs(ANY, ANY).block()
                .source("CodeGenerators.swift:2727")
                .doc("`if (lhs cmp rhs) {...} else {...}`.")
                .register((d, b, in) -> {
                    int cond = b.compare(in[0], in[1], d.sampled(Operations.COMPARATORS));
                    try (var br = b.if_(cond)) {
                        nested(d, b);
                        try (var e = br.else_()) {
                            nested(d, b);
                        }
                    }
                    return null;
                });

        generator("while_loop").family("control").weight(15).block().source("CodeGenerators.swift:2808")
                .doc("A counted while loop: `var i = 0; while (i < n) { ...; i++ }`.")
                .register((d, b, in) -> {
                    int zero = b.loadInt(0);
                    int i = b.declare(zero, d.sampled(LOOP_DECLARATION_KINDS));
                    try (var loop = b.whileLoop()) {
                        int cond = b.compare(i, b.loadInt(d.ints(0, 10)), CompareOperator.LESS);
                        try (var body = loop.body(cond)) {
                            nested(d, b);
                            b.unary(UnaryOperator.POST_INC, i);
                        }
                    }
                    return null;
                });

        generator("do_while_loop").family("control").weight(15).block().source("CodeGenerators.swift:2831")
                .doc("`var i = 0; do { ...; i++ } while (i < n)`.")
                .register((d, b, in) -> {
                    int zero = b.loadInt(0);
                    int i = b.declare(zero, d.sampled(LOOP_DECLARATION_KINDS));
                    try (var loop = b.doWhileLoop()) {
                        nested(d, b);
                        b.unary(UnaryOperator.POST_INC, i);
                        try (var h = loop.header()) {
                            h.done(b.compare(i, b.loadInt(d.ints(0, 10)), CompareOperator.LESS));
                        }
                    }
                    return null;
                });

        generator("simple_for_loop").family("control").weight(10).block().source("CodeGenerators.swift:2845")
                .doc("`for (let i = 0; i < n; i++) {...}`.")
                .register((d, b, in) -> {
                    try (var loop = b.forLoop()) {
                        int start = b.loadInt(0);
                        int cond;
                        try (var c = loop.condition(List.of(start), loopDeclaration(d))) {
                            cond = b.compare(c.var(0), b.loadInt(d.ints(0, 10)), CompareOperator.LESS);
                        }
                        try (var a = loop.afterthought(cond)) {
                            b.unary(UnaryOperator.POST_INC, a.var(0));
                        }
                        try (var body = loop.body()) {
                            nested(d, b);
                        }
                    }
                    return null;
                });

        generator("complex_for_loop").family("control").weight(10).block().source("CodeGenerators.swift:2878")
                .doc("A for loop with two loop variables moving towards each other, or a count-down with an empty header part.")
                .register((d, b, in) -> {
                    try (var loop = b.forLoop()) {
                        int lo = b.loadInt(0), hi = b.loadInt(d.ints(1, 10));
                        int cond;
                        try (var c = loop.condition(List.of(lo, hi), loopDeclaration(d))) {
                            cond = b.compare(c.var(0), c.var(1), CompareOperator.LESS);
                        }
                        try (var a = loop.afterthought(cond)) {
                            b.unary(UnaryOperator.POST_INC, a.var(0));
                            b.unary(UnaryOperator.POST_DEC, a.var(1));
                        }
                        try (var body = loop.body()) {
                            nested(d, b);
                        }
                    }
                    return null;
                });

        generator("for_in_loop").family("control").weight(10).inputs(OBJECT).block().source("CodeGenerators.swift:2901")
                .doc("`for (var k in obj) {...}`.")
                .register((d, b, in) -> {
                    try (var loop = b.forEach(ForEachKind.IN, in[0], loopDeclaration(d))) {
                        nested(d, b);
                    }
                    return null;
                });

        generator("for_of_loop").family("control").weight(10).inputs(Type.array()).block().toggle("for-of")
                .source("CodeGenerators.swift:2909")
                .doc("`for (var x of iterable) {...}`.")
                .register((d, b, in) -> {
                    try (var loop = b.forEach(ForEachKind.OF, in[0], loopDeclaration(d))) {
                        nested(d, b);
                    }
                    return null;
                });

        generator("repeat_loop").family("control").weight(10).block().source("CodeGenerators.swift:2982")
                .doc("`for (let i = 0; i < n; i++) {...}` for a drawn n.")
                .register((d, b, in) -> {
                    int n = d.ints(2, 100);
                    try (var loop = b.repeat(n, loopDeclaration(d))) {
                        nested(d, b);
                    }
                    return null;
                });

        generator("loop_break").family("control").weight(5).context(Context.LOOP).source("CodeGenerators.swift:3032")
                .doc("`break` or `break label` inside a loop.")
                .register((d, b, in) -> {
                    List<Integer> labels = loopLabels(b);
                    if (!labels.isEmpty() && d.maybe(0.2)) b.break_(pick(d, labels));
                    else b.break_();
                    return null;
                });

        generator("loop_continue").family("control").weight(5).context(Context.LOOP).source("CodeGenerators.swift:3037")
                .doc("`continue` or `continue label` inside a loop.")
                .register((d, b, in) -> {
                    List<Integer> labels = loopLabels(b);
                    if (!labels.isEmpty() && d.maybe(0.2)) b.continue_(pick(d, labels));
                    else b.continue_();
                    return null;
                });

        generator("try_catch_finally").family("control").weight(5).block().source("CodeGenerators.swift:3042")
                .doc("try/catch/finally around generated statements.")
                .register((d, b, in) -> {
                    try (var t = b.try_()) {
                        nested(d, b);
                        try (var c = t.catch_()) {
                            nested(d, b);
                        }
                        try (var f = t.finally_()) {
                            nested(d, b);
                        }
                    }
                    return null;
                });

        generator("try_catch").family("control").weight(5).block().source("CodeGenerators.swift:3074")
                .doc("try/catch around generated statements, with a possible throw; the catch inspects the error.")
                .register((d, b, in) -> {
                    try (var t = b.try_()) {
                        nested(d, b);
                        if (d.maybe(0.3)) b.throw_(variable(d, b));
                        try (var c = t.catch_()) {
                            nested(d, b);
                            b.typeof(c.var());
                        }
                    }
                    return null;
                });

        generator("try_finally").family("control").weight(5).block().source("CodeGenerators.swift:3099")
                .doc("try/finally around generated statements.")
                .register((d, b, in) -> {
                    try (var t = b.try_()) {
                        nested(d, b);
                        try (var f = t.finally_()) {
                            nested(d, b);
                        }
                    }
                    return null;
                });

        generator("throw").family("control").weight(1).inputs(ANY).source("CodeGenerators.swift:3124")
                .doc("`throw x`.")
                .register((d, b, in) -> {
                    b.throw_(in[0]);
                    return null;
                });

        generator("conditional_throw").family("control").weight(1).inputs(BOOLEAN).block().source("CodeGenerators.swift:3129")
                .doc("`if (cond) throw x` (or `if (!cond)`).")
                .register((d, b, in) -> {
                    try (var br = b.if_(in[0], d.bool())) {
                        b.throw_(variable(d, b));
                    }
                    return null;
                });

        generator("block_statement").family("control").weight(1).block().source("CodeGenerators.swift:3338")
                .doc("A bare block `{ ... }`, possibly labelled and broken out of.")
                .register((d, b, in) -> {
                    try (var blk = b.block()) {
                        nested(d, b);
                        if (d.maybe(0.3)) b.blockBreak(blk.label());
                    }
                    return null;
                });

        generator("switch").family("control").weight(5).inputs(ANY).block().source("CodeGenerators.swift:2745")
                .doc("`switch (value)` with drawn cases, fallthrough, and an optional default.")
                .register((d, b, in) -> {
                    List<Integer> cases = d.lists(0, 3, dd -> variable(dd, b));
                    try (var sw = b.switch_(in[0])) {
                        for (int c : cases) {
                            try (var cs = sw.case_(c, d.maybe(0.1))) {
                                nested(d, b);
                            }
                        }
                        if (d.maybe(0.5)) {
                            try (var df = sw.default_(d.maybe(0.1))) {
                                nested(d, b);
                            }
                        }
                    }
                    return null;
                });

        generator("switch_break").family("control").weight(5).context(Context.SWITCH_CASE).source("CodeGenerators.swift:2804")
                .doc("`break` inside a switch case.")
                .register((d, b, in) -> {
                    b.switchBreak();
                    return null;
                });

        generator("with_statement").family("control").weight(3).inputs(OBJECT).block().sloppyOnly().toggle("with")
                .source("CodeGenerators.swift:3296")
                .doc("`with (obj) {...}`: names inside resolve against the object (forces an activation).")
                .register((d, b, in) -> {
                    try (var w = b.with_(in[0])) {
                        nested(d, b);
                    }
                    return null;
                });

        generator("eval").family("control").weight(3).block().toggle("eval").source("CodeGenerators.swift:3323")
                .doc("A generated program lifted to a string and evaluated with direct `eval`.")
                .register((d, b, in) -> {
                    int code;
                    try (var cs = b.codeString()) {
                        nested(d, b, 2, 1);
                        code = cs.result();
                    }
                    return b.callFunction(b.builtin(Env.builtin("EVAL")), List.of(code));
                });
    }
}
