// The JIT templates are translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License
// 2.0), Sources/Fuzzilli/CodeGen/ProgramTemplates.swift at commit
// be2811d00282320381fc8007d64873745772011d (JIT1Function 299, JIT2Functions 338, JITTrickyFunction 398).
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import static dev.oxpecker.il.Strategies.argumentsFor;
import static dev.oxpecker.il.Strategies.nested;
import static dev.oxpecker.il.Strategies.oneStatement;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.pools;
import static dev.oxpecker.il.Strategies.prefix;
import static dev.oxpecker.il.Strategies.template;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.generators.Calls;
import java.util.ArrayList;
import java.util.List;

/**
 * Program templates (templates.py): the overall shape of a generated program. Every template
 * shares the same statement floor (D28) and places probes.
 */
public final class Templates {
    private Templates() {}

    static final List<Long> WARMUPS = List.of(10L, 100L);

    /**
     * A function variable is assignable ({@code f = 1} is a shape worth testing), so a template
     * calls its function only while the types at this point allow it.
     */
    static void callIfStillCallable(Draw d, Builder b, int f) {
        d.draw(dd -> {
            if (b.typeOf(f).mayBe(Type.FUNCTION)) b.callFunction(f, argumentsFor(dd, b, f));
            return null;
        });
    }

    /** Top-level statements (one list draw) with a probe after every {@code probe_every} of them. */
    static int probed(Draw d, Builder b) {
        return d.draw(dd -> {
            List<Strategies.GeneratorSpec> pool = pools(b.options).forContext(b.context());
            int every = b.options.probeEvery();
            int[] count = {0};
            dd.lists(b.options.minStatements(), b.options.maxStatements(), e -> {
                oneStatement(e, b, pool);
                count[0]++;
                if (every != 0 && count[0] % every == 0) b.probe();
                return Boolean.TRUE;
            });
            return count[0];
        });
    }

    public static void registerAll() {
        template("plain", 6, "A prefix of values, then a flat list of generated statements with probes.", (d, b) -> {
            prefix(d, b);
            probed(d, b);
            b.probe(ProbeLabel.END);
        });

        template("jit1_function", 3, "A function with a generated body called a hundred times in a loop, then with other arguments.", (d, b) -> {
            prefix(d, b);
            probed(d, b);
            Calls.Params p = Calls.parameters(d, b);
            int f;
            try (var fb = b.plainFunction(p.params(), Builder.fn().named(true).types(p.types()))) {
                f = fb.f();
                nested(d, b, 8, 1);
                b.doReturn(pick(d, b.visible()));
            }
            try (var r = b.repeat(d.sampled(WARMUPS))) {
                callIfStillCallable(d, b, f);
            }
            b.probe(ProbeLabel.WARM);
            probed(d, b);
            callIfStillCallable(d, b, f);
            b.probe(ProbeLabel.CHANGED);
            b.probe(ProbeLabel.END);
        });

        template("jit2_functions", 3, "Two generated functions warmed up in loops, then called in alternation.", (d, b) -> {
            prefix(d, b);
            probed(d, b);
            List<Integer> fs = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                Calls.Params p = Calls.parameters(d, b);
                try (var fb = b.plainFunction(p.params(), Builder.fn().named(true).types(p.types()))) {
                    nested(d, b, 6, 1);
                    b.doReturn(pick(d, b.visible()));
                    fs.add(fb.f());
                }
            }
            for (int f : fs) {
                try (var r = b.repeat(d.sampled(WARMUPS))) {
                    callIfStillCallable(d, b, f);
                }
            }
            b.probe(ProbeLabel.WARM);
            for (int k = fs.size() - 1; k >= 0; k--) callIfStillCallable(d, b, fs.get(k));
            b.probe(ProbeLabel.END);
        });

        template("jit_tricky_function", 2, "A function whose body has a branch taken only in some iterations of the warm-up loop.", (d, b) -> {
            prefix(d, b);
            probed(d, b);
            Calls.Params p = Calls.parameters(d, b);
            int f;
            try (var fb = b.plainFunction(p.params(), Builder.fn().named(true).types(p.types()))) {
                f = fb.f();
                nested(d, b, 4);
                b.declare(b.loadInt(0));
                b.doReturn(pick(d, b.visible()));
            }
            long iterations = d.sampled(WARMUPS);
            try (var r = b.repeat(iterations)) {
                int i = r.var(0);
                int cond;
                if (d.bool()) {
                    cond = b.compare(i, b.loadInt(d.ints(0, (int) iterations - 1)), CompareOperator.EQUAL);
                } else {
                    int m = b.binary(i, b.loadInt(d.sampled(List.of(2L, 5L, 10L))), BinaryOperator.MOD);
                    cond = b.compare(m, b.loadInt(0), CompareOperator.EQUAL);
                }
                try (var br = b.if_(cond)) {
                    nested(d, b, 3);
                }
                callIfStillCallable(d, b, f);
            }
            b.probe(ProbeLabel.END);
        });
    }
}
