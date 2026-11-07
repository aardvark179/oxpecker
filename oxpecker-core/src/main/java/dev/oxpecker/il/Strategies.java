package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.SYMBOL;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.gen.Engine;
import dev.oxpecker.gen.Gen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generators, value makers, templates and programs as {@link Gen}s (strategies.py).
 *
 * <p>The strategy rules of docs/design-generation-layer.md §7.5 live here, not in generator code:
 * {@link Draw#lists} for every repetition, {@link #pick} for every context-dependent pool, fixed
 * pools (one for all statement contexts, one for object-literal members, one for switch cases)
 * with weights as repetition, a context-independent fallback, visible variables most recent first,
 * {@code maybe()} shrinking to false, lifting choices drawn after generation. The builder never
 * draws; every choice is a draw here or in a generator.
 *
 * <p>Python's {@code @st.composite} helpers ({@code variable}, {@code operand}, ...) are static
 * methods taking the {@link Draw}; each runs in its own sub-draw, as each composite was its own
 * Hypothesis span.
 */
public final class Strategies {
    private Strategies() {}

    // ------------------------------------------------------------------------------- primitives
    /**
     * {@code xs[i]} for a drawn index. For any pool whose size depends on the context (visible
     * variables, candidates): always one draw, also for a one-element pool.
     */
    public static <T> T pick(Draw d, List<? extends T> xs) {
        if (xs.isEmpty()) throw new IllegalArgumentException("pick from an empty pool");
        return d.pick(xs);
    }

    // ------------------------------------------------------------------------------- registry
    public static final List<String> FAMILY_ORDER = List.of("values", "containers", "operations", "properties",
            "calls", "control", "compiler-targeted", "java-interop", "continuations", "exotic", "api", "objects",
            "members", "cases", "toggled");

    /** A generator body: {@code (draw, builder, inputs) -> the variable it made, or null}. */
    @FunctionalInterface
    public interface GenFn {
        Integer run(Draw d, Builder b, int[] inputs);
    }

    /** A registered generator (strategies.GeneratorSpec). */
    public record GeneratorSpec(String name, String family, int weight, int rank, int context, List<Type> inputs,
                                List<Type> required, Type produces, String toggle, boolean sloppyOnly, String source,
                                GenFn body, String doc, boolean scaffolds, boolean isBlock, String syntax) {
        public boolean applicable(Builder b) {
            if (toggle != null && !b.options.toggles().contains(toggle)) return false;
            if (syntax != null && !Env.SYNTAX_SUPPORTED.contains(syntax)) return false;
            if (sloppyOnly && b.strict()) return false;
            if (isBlock && b.depth <= 0) return false;
            if ((context & ~b.context()) != 0 && !scaffolds) return false;
            for (Type t : required) if (b.pool(t).exact().isEmpty()) return false;
            return true;
        }

        /**
         * Invokes the generator in its own sub-draw, stamping provenance (strategies.generator's
         * {@code attributed}): the builder's current generator, a fresh origin, and a drawn
         * default declaration kind, restored afterwards (the origin counter is kept).
         */
        public Integer invoke(Draw d, Builder b, int... args) {
            return d.draw(dd -> {
                String savedGen = b.currentGen;
                DeclarationKind savedKind = b.defaultKind;
                b.currentGen = name;
                b.origin = b.origin + 1;
                b.defaultKind = declarationKind(dd, b.options, false);
                try {
                    return body.run(dd, b, args);
                } finally {
                    b.currentGen = savedGen;
                    b.defaultKind = savedKind;
                }
            });
        }

        @Override public boolean equals(Object o) { return this == o; }

        @Override public int hashCode() { return System.identityHashCode(this); }

        @Override public String toString() { return "GeneratorSpec(" + name + ")"; }
    }

    public static final Map<String, GeneratorSpec> REGISTRY = new LinkedHashMap<>();
    public static final Map<Type, GeneratorSpec> VALUE_MAKERS = new LinkedHashMap<>();
    public static final Map<String, TemplateSpec> TEMPLATES = new HashMap<>();

    /** A program template: the overall shape of a program. */
    public record TemplateSpec(String name, int weight, java.util.function.BiConsumer<Draw, Builder> body,
                               String doc) {}

    private static volatile boolean registered = false;

    /** Populates the registries on first use: every generator family, then the templates. */
    public static synchronized void registered() {
        if (registered) return;
        registered = true;
        dev.oxpecker.il.generators.Families.registerAll();
        Templates.registerAll();
    }

    /** Starts a generator registration: {@code generator("while_loop").family("control")...register(body)}. */
    public static Registration generator(String name) { return new Registration(name); }

    public static final class Registration {
        private final String name;
        private String family;
        private int weight = 1, rank = 50, context = Context.JAVASCRIPT;
        private List<Type> inputs = List.of(), required = List.of();
        private Type produces;
        private final List<Type> valueMakers = new ArrayList<>();
        private String toggle, source = "", doc = "", syntax;
        private boolean sloppyOnly, scaffolds, block;

        Registration(String name) { this.name = name; }

        public Registration family(String v) { family = v; return this; }
        public Registration weight(int v) { weight = v; return this; }
        public Registration rank(int v) { rank = v; return this; }
        public Registration context(int v) { context = v; return this; }
        public Registration inputs(Type... v) { inputs = List.of(v); return this; }
        public Registration required(Type... v) { required = List.of(v); return this; }
        public Registration produces(Type v) { produces = v; return this; }
        public Registration toggle(String v) { toggle = v; return this; }
        public Registration sloppyOnly() { sloppyOnly = true; return this; }
        public Registration source(String v) { source = v; return this; }
        public Registration doc(String v) { doc = v; return this; }
        public Registration scaffolds() { scaffolds = true; return this; }
        public Registration block() { block = true; return this; }
        public Registration syntax(String v) { syntax = v; return this; }
        /**
         * Also marks the generator as the fallback for {@code variable(t)} on an empty pool. Call it
         * in the order Python's {@code @value_maker} decorators applied (bottom-up), since the
         * registry's order decides which maker serves a supertype.
         */
        public Registration valueMaker(Type t) { valueMakers.add(t); return this; }

        public GeneratorSpec register(GenFn body) {
            if (!FAMILY_ORDER.contains(family)) throw new IllegalArgumentException("unknown family " + family);
            if (REGISTRY.containsKey(name)) throw new IllegalArgumentException("duplicate generator " + name);
            GeneratorSpec spec = new GeneratorSpec(name, family, weight, rank, context, inputs, required, produces,
                    toggle, sloppyOnly, source, body, doc, scaffolds, block, syntax);
            REGISTRY.put(name, spec);
            for (Type t : valueMakers) VALUE_MAKERS.put(t, spec);
            return spec;
        }
    }

    public static void template(String name, int weight, String doc, java.util.function.BiConsumer<Draw, Builder> body) {
        TEMPLATES.put(name, new TemplateSpec(name, weight, body, doc));
    }

    /**
     * Whether the probed Rhino parses the construct {@code key} names (a {@code syntax} key of the
     * capability table); generators consult it before drawing an optional form.
     */
    public static boolean syntaxOk(String key) { return Env.SYNTAX_SUPPORTED.contains(key); }

    public static final List<DeclarationKind> DECLARATION_KINDS = List.of(DeclarationKind.VAR, DeclarationKind.VAR,
            DeclarationKind.LET, DeclarationKind.CONST);
    public static final List<DeclarationKind> MUTABLE_KINDS = List.of(DeclarationKind.VAR, DeclarationKind.LET);

    /**
     * The kind of a declaration site (G25): var twice, let, const; only var (no draw) without the
     * {@code let} toggle; {@code mutable} leaves const out for a variable its generator will reassign.
     */
    public static DeclarationKind declarationKind(Draw d, GenOptions options, boolean mutable) {
        if (!options.toggles().contains(GenOptions.TOGGLE_LET)) return DeclarationKind.VAR;
        return d.sampled(mutable ? MUTABLE_KINDS : DECLARATION_KINDS);
    }

    public static DeclarationKind declarationKind(Draw d, GenOptions options) { return declarationKind(d, options, false); }

    // ------------------------------------------------------------------------------- pools
    public record Pools(List<GeneratorSpec> statements, List<GeneratorSpec> members, List<GeneratorSpec> cases,
                        List<GeneratorSpec> values) {
        public List<GeneratorSpec> forContext(int ctx) {
            if ((ctx & Context.OBJECT_LITERAL) != 0 && (ctx & Context.JAVASCRIPT) == 0) return members;
            if ((ctx & Context.SWITCH_BLOCK) != 0 && (ctx & Context.JAVASCRIPT) == 0) return cases;
            return statements;
        }
    }

    private static final Map<List<Object>, Pools> POOL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fixed, ordered lists: by family order then rank then name, each entry repeated by its weight
     * times the option multipliers. Filtering removes entries and never reorders the rest.
     */
    public static Pools pools(GenOptions options) {
        registered();
        List<Object> key = java.util.Arrays.asList(options.families(), options.generators(), options.weights(), REGISTRY.size());
        return POOL_CACHE.computeIfAbsent(key, k -> {
            List<GeneratorSpec> specs = new ArrayList<>(REGISTRY.values());
            specs.sort(Comparator.comparingInt((GeneratorSpec s) -> FAMILY_ORDER.indexOf(s.family()))
                    .thenComparingInt(GeneratorSpec::rank).thenComparing(GeneratorSpec::name));
            if (options.families() != null) specs.removeIf(s -> !options.families().contains(s.family()));
            if (options.generators() != null) specs.removeIf(s -> !options.generators().contains(s.name()));
            List<GeneratorSpec> statements = new ArrayList<>(), members = new ArrayList<>(), cases = new ArrayList<>(),
                    values = new ArrayList<>();
            for (GeneratorSpec s : specs) {
                double mult = options.weights().getOrDefault(s.name(), options.weights().getOrDefault(s.family(), 1.0));
                long n = pyRound(s.weight() * mult);
                if (n > 0) {
                    List<GeneratorSpec> into = s.family().equals("members") ? members : s.family().equals("cases") ? cases : statements;
                    for (long i = 0; i < n; i++) into.add(s);
                }
                if (VALUE_MAKERS.containsValue(s) && !values.contains(s)) values.add(s);
            }
            return new Pools(List.copyOf(statements), List.copyOf(members), List.copyOf(cases), List.copyOf(values));
        });
    }

    /** Python's round(): halves to even. */
    static long pyRound(double x) { return (long) Math.rint(x); }

    public static GeneratorSpec fallback(List<GeneratorSpec> pool, Builder b) {
        for (GeneratorSpec s : pool) if (s.applicable(b)) return s;
        return null;
    }

    // ------------------------------------------------------------------------------- variables
    /**
     * A visible variable for use as {@code t}: an exact match with a drawn {@code maybe(0.8)}, else
     * one that may be of the type, else any; on an empty pool the value maker for {@code t} is drawn
     * (design §7.1). The boolean is drawn whether or not it is needed, so the choice keeps its meaning.
     */
    public static int variable(Draw d, Builder b, Type t) {
        return d.draw(dd -> variable0(dd, b, t));
    }

    public static int variable(Draw d, Builder b) { return variable(d, b, ANY); }

    private static int variable0(Draw d, Builder b, Type t) {
        boolean wantExact = d.maybe(0.8);
        Builder.Pool p = b.pool(t);
        if (!p.exact().isEmpty() && wantExact) return pick(d, p.exact());
        if (!p.exact().isEmpty()) return pick(d, concat(p.exact(), p.maybe()));
        if (!p.all().isEmpty() && t.equals(ANY)) return pick(d, p.all());
        // no variable is known to have the type: a may-be one (an unknown, often a parameter) or a
        // fresh value of the type, half and half, so loose inputs stay but do not dominate the
        // completion rate (an `undefined` callee, a Symbol in arithmetic)
        GeneratorSpec maker = makerFor(t);
        if (!p.maybe().isEmpty() && (maker == null || !maker.applicable(b) || d.maybe(0.5))) return pick(d, p.maybe());
        if (maker != null && maker.applicable(b)) {
            Integer v = maker.invoke(d, b);
            if (v != null) return v;
        }
        if (!p.maybe().isEmpty()) return pick(d, p.maybe());
        if (!p.all().isEmpty()) return pick(d, p.all());
        GeneratorSpec any = VALUE_MAKERS.get(ANY);
        return any != null ? any.invoke(d, b) : b.loadInt(0);
    }

    static <T> List<T> concat(List<? extends T> a, List<? extends T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static GeneratorSpec makerFor(Type t) {
        GeneratorSpec s = VALUE_MAKERS.get(t);
        if (s != null) return s;
        for (var e : VALUE_MAKERS.entrySet()) if (e.getKey().is(t)) return e.getValue();
        return null;
    }

    /**
     * A Symbol throws in every arithmetic or relational operation and a BigInt throws when mixed
     * with a Number: the values behind most TypeErrors of the operations family (G46).
     */
    public static boolean poisonsArithmetic(Builder b, int v) {
        Type t = b.typeOf(v);
        return t.is(SYMBOL) || t.is(BIGINT);
    }

    /**
     * A value for arithmetic: with p=0.85 one that is neither a Symbol nor a BigInt (unless
     * {@code like} is a BigInt, then another BigInt, which is the one legal mix); otherwise any
     * value, so type confusion stays in the mix at a rate the completion rate can afford (G46).
     */
    public static int operand(Draw d, Builder b, Integer like) {
        return d.draw(dd -> {
            if (like != null && b.typeOf(like).is(BIGINT)) return variable(dd, b, BIGINT);
            if (!dd.maybe(0.85)) return variable(dd, b);
            List<Integer> clean = new ArrayList<>();
            for (int v : b.pool(ANY).all()) if (!poisonsArithmetic(b, v)) clean.add(v);
            if (!clean.isEmpty()) return pick(dd, clean);
            return variable(dd, b, NUMBER);
        });
    }

    public static int operand(Draw d, Builder b) { return operand(d, b, null); }

    /** A declared, assignable variable preferably of type {@code t}; declares one from a temporary if none exists. */
    public static int assignable(Draw d, Builder b, Type t) {
        return d.draw(dd -> {
            Builder.Pool p = b.pool(t, true);
            if (!p.exact().isEmpty()) return pick(dd, p.exact());
            if (!p.all().isEmpty()) return pick(dd, p.all());
            int v = variable(dd, b, t);
            return b.declare(v, declarationKind(dd, b.options, true));
        });
    }

    public static int assignable(Draw d, Builder b) { return assignable(d, b, ANY); }

    /**
     * Argument variables for calling {@code f}: one per declared parameter, drawn for its type, plus
     * 0..3 extra for a rest parameter or an unknown signature (ProgramBuilder.swift:1260-1302).
     */
    public static List<Integer> argumentsFor(Draw d, Builder b, int f) {
        return d.draw(dd -> {
            Signature sig = b.typeOf(f).signature();
            List<Integer> args = new ArrayList<>();
            if (sig == null) {
                int n = dd.ints(0, 3);
                for (int i = 0; i < n; i++) args.add(variable(dd, b));
                return args;
            }
            for (Param p : sig.params()) {
                if (p.mode() == ParamMode.REST) {
                    int n = dd.ints(0, 3);
                    for (int i = 0; i < n; i++) args.add(variable(dd, b, p.type()));
                } else if (p.mode() == ParamMode.OPT && !dd.maybe(0.5)) {
                    break;
                } else {
                    args.add(variable(dd, b, p.type()));
                }
            }
            return args;
        });
    }

    // ------------------------------------------------------------------------------- code
    /**
     * One generator invocation: index into the fixed pool; an inapplicable pick falls back to the
     * first applicable entry; loose inputs are drawn for their types.
     */
    public static void oneStatement(Draw d, Builder b, List<GeneratorSpec> pool) {
        d.draw(dd -> {
            GeneratorSpec spec = pool.get(dd.ints(0, pool.size() - 1));
            if (!spec.applicable(b)) {
                spec = fallback(pool, b);
                if (spec == null) return null;
            }
            int[] inputs = new int[spec.inputs().size() + spec.required().size()];
            int k = 0;
            for (Type t : spec.inputs()) inputs[k++] = variable(dd, b, t);
            for (Type t : spec.required()) inputs[k++] = pick(dd, b.pool(t).exact());
            spec.invoke(dd, b, inputs);
            return null;
        });
    }

    /** {@link Draw#lists} over generator invocations for the context now open: one span per statement. */
    public static int statements(Draw d, Builder b, Integer lo, Integer hi) {
        return d.draw("statements", dd -> {
            List<GeneratorSpec> pool = pools(b.options).forContext(b.context());
            if (pool.isEmpty()) return 0;
            int min = lo == null ? b.options.minStatements() : lo;
            int max = hi == null ? b.options.maxStatements() : hi;
            return dd.lists(min, max, e -> { oneStatement(e, b, pool); return Boolean.TRUE; }).size();
        });
    }

    public static int statements(Draw d, Builder b) { return statements(d, b, null, null); }

    /** Statements inside a block a generator opened; spends one level of the nesting budget. */
    public static int nested(Draw d, Builder b, int n, int lo) {
        if (b.depth <= 0) return 0;
        b.depth -= 1;
        try {
            return statements(d, b, lo, n);
        } finally {
            b.depth += 1;
        }
    }

    public static int nested(Draw d, Builder b) { return nested(d, b, 2, 0); }

    public static int nested(Draw d, Builder b, int n) { return nested(d, b, n, 0); }

    /**
     * The program prefix: a list of value-maker draws (Fuzzilli's buildPrefix), so generators that
     * follow have visible variables.
     */
    public static List<Integer> prefix(Draw d, Builder b, int lo, int hi) {
        return d.draw(dd -> {
            List<GeneratorSpec> pool = pools(b.options).values();
            if (pool.isEmpty()) return List.<Integer>of();
            List<Integer> vs = dd.lists(lo, hi, e -> e.draw(e2 -> {
                GeneratorSpec spec = pool.get(e2.ints(0, pool.size() - 1));
                if (!spec.applicable(b)) spec = fallback(pool, b);
                return spec != null ? spec.invoke(e2, b) : null;
            }));
            List<Integer> out = new ArrayList<>();
            for (Integer v : vs) if (v != null) out.add(v);
            return out;
        });
    }

    public static List<Integer> prefix(Draw d, Builder b) { return prefix(d, b, 2, 5); }

    public static final List<Lift> LIFT_CHOICES = List.of(Lift.INLINE, Lift.INLINE, Lift.INLINE, Lift.VAR, Lift.LET, Lift.CONST);

    /**
     * Draws the {@code lift} of every value-producing instruction that has none: from the fixed
     * list (INLINE, VAR, LET, CONST), shrinking to INLINE (design §3.6).
     */
    public static List<Instruction> liftingChoices(Draw d, List<Instruction> code) {
        return d.draw(dd -> {
            List<Instruction> out = new ArrayList<>(code.size());
            for (Instruction instr : code) {
                if (instr.lift() == null && instr.op().outputs() == 1 && !instr.op().isBlock())
                    out.add(instr.withLift(dd.sampled(LIFT_CHOICES)));
                else
                    out.add(instr);
            }
            return List.copyOf(out);
        });
    }

    /** A generated program and its sources. */
    public record Generated(Program program, String template) {
        public List<Instruction> code() { return program.code(); }

        public String source() {
            return Lifter.lift(program.code(), new LiftOptions(true, true, program.options().strict(), program.options().probeWidth()));
        }

        public String plainSource() {
            return Lifter.lift(program.code(), new LiftOptions(false, false, program.options().strict(), 6));
        }

        @Override public String toString() { return source(); }
    }

    /**
     * A whole program: template (drawn by weight unless {@code template} names one), builder,
     * verify, lifting choices. A verification failure is a generator bug and raises; nothing is filtered.
     */
    public static Gen<Generated> programs(GenOptions options, String template) {
        return d -> {
            registered();
            String tname;
            if (template != null) {
                tname = template;
            } else {
                List<String> weighted = new ArrayList<>();
                for (String n : TEMPLATES.keySet().stream().sorted().toList())
                    for (int i = 0; i < TEMPLATES.get(n).weight(); i++) weighted.add(n);
                tname = weighted.get(d.ints(0, weighted.size() - 1));
            }
            TemplateSpec spec = Objects.requireNonNull(TEMPLATES.get(tname), () -> "unknown template " + tname);
            Builder b = new Builder(options);
            d.draw(dd -> { spec.body().accept(dd, b); return null; });
            List<Instruction> code = b.finish();
            code = liftingChoices(d, code);
            Analysis.verify(code, options.strict());
            return new Generated(new Program(code, options, tname), tname);
        };
    }

    public static Gen<Generated> programs(GenOptions options) { return programs(options, null); }

    // ------------------------------------------------------------------------------- from a shell
    /** Runs a strategy once, deterministically from {@code seed}. */
    public static <T> T draw(Gen<T> g, long seed) {
        Engine.Drawn<T> r = Engine.fresh(g, seed);
        if (r == null) throw new IllegalStateException("the draw rejected");
        return r.value();
    }

    /** One generated program from a seed. */
    public static Generated generate(long seed, GenOptions options) { return draw(programs(options), seed); }

    public static Generated generate(long seed) { return generate(seed, GenOptions.DEFAULT); }

    public static Generated generate(long seed, GenOptions options, String template) { return draw(programs(options, template), seed); }

    /** A generator run alone: the prefix it was given and what it emitted. */
    public record Snippet(String name, List<Instruction> code, int start) {
        public List<Instruction> emitted() { return code.subList(start, code.size()); }

        public String js() { return Lifter.lift(code, new LiftOptions(false, true, false, 6)); }

        @Override public String toString() { return js(); }
    }

    /** Runs one registered generator after a small prefix of values, deterministically from a seed. */
    public static Snippet runGenerator(String name, long seed, int prefixSize, GenOptions options) {
        registered();
        GeneratorSpec spec = Objects.requireNonNull(REGISTRY.get(name), () -> "unknown generator " + name);
        return draw(d -> {
            Builder b = new Builder(options);
            prefix(d, b, prefixSize, prefixSize);
            if (!spec.applicable(b))
                throw new IllegalStateException(name + " is not applicable at top level (context " + Context.str(spec.context()) + ")");
            int[] inputs = new int[spec.inputs().size() + spec.required().size()];
            int k = 0;
            for (Type t : spec.inputs()) inputs[k++] = variable(d, b, t);
            for (Type t : spec.required()) {
                List<Integer> pool = b.pool(t).exact();
                inputs[k++] = pool.get(d.ints(0, pool.size() - 1));
            }
            int start = b.code.size();
            spec.invoke(d, b, inputs);
            List<Instruction> code = liftingChoices(d, b.finish());
            Analysis.verify(code);
            return new Snippet(name, code, start);
        }, seed);
    }

    public static Snippet runGenerator(String name, long seed) { return runGenerator(name, seed, 3, GenOptions.DEFAULT); }

    /** Registered generator names, optionally one family. */
    public static List<String> generators(String family) {
        registered();
        return REGISTRY.values().stream().filter(s -> family == null || s.family().equals(family))
                .map(GeneratorSpec::name).sorted().toList();
    }

    public static List<String> templates() {
        registered();
        return TEMPLATES.keySet().stream().sorted().toList();
    }
}
