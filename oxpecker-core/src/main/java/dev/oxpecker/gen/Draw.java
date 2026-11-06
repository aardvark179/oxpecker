package dev.oxpecker.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.jetCheck.GenerationEnvironment;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;

/**
 * The drawing context a {@link Gen} receives: every random decision of a generator is one of the
 * primitives here, and nothing else (the rules of docs/design-generation-layer.md §7.5 carried
 * over from the Hypothesis strategies).
 *
 * <p>Each primitive is one jetCheck int draw, made in its own sub-structure through a
 * distribution of the engine's ({@link Forced}), and recorded in the order drawn. That is what lets
 * {@link Engine} replay a recorded {@link Choices} exactly, extend it with fresh draws, and hand it
 * to jetCheck's shrinker as the starting example (variation 1 of the port plan: stock jetCheck,
 * whose public API can shrink only a value its own checker generated). Lists use jetCheck's
 * {@code listsOf}, so the shrinker removes ranges of elements; {@link #draw(String, Gen)} gives a
 * sub-draw a stable identity, so the shrinker may replace a structure by a nested one of the same
 * label (jetCheck's recursion shrink).
 */
public final class Draw {
    final GenerationEnvironment env;
    final Engine.Session session;

    Draw(GenerationEnvironment env, Engine.Session session) {
        this.env = env;
        this.session = session;
    }

    // ------------------------------------------------------------------------- primitives
    /**
     * An int in [lo, hi]; shrinks towards 0 (towards lo when 0 is out of range). Uniform for ranges
     * up to 128 values; wider ranges are biased like Hypothesis's bounded {@code st.integers} (see
     * {@link Biased}), which matters for the index draws into the long generator pools.
     */
    public int ints(int lo, int hi) {
        if (lo > hi) throw new IllegalArgumentException("empty range [" + lo + ", " + hi + "]");
        IntDistribution dist = (long) hi - lo + 1 > 128 ? new Biased(lo, hi) : IntDistribution.uniform(lo, hi);
        int v = env.generate(Generator.integers(new Forced(dist, session)));
        session.record(v);
        return v;
    }

    /** Hypothesis's {@code st.booleans()}: one draw, shrinking to false. */
    public boolean bool() { return ints(0, 1) == 1; }

    /**
     * A boolean that shrinks to false; true with probability {@code p} (strategies.maybe): one
     * index draw into a fixed list of 100, the trues at the end.
     */
    public boolean maybe(double p) {
        if (!(0.0 < p && p < 1.0)) throw new IllegalArgumentException("p must be strictly between 0 and 1");
        int w = (int) Math.max(1, Math.min(99, Math.round(p * 100)));
        return ints(0, 99) >= 100 - w;
    }

    /**
     * {@code xs[i]} for a drawn index (strategies.pick, and {@code st.sampled_from}): always one
     * draw, also for a one-element pool, so a pool that shrinks to one element does not shift
     * every later choice.
     */
    public <T> T pick(List<? extends T> xs) {
        if (xs.isEmpty()) throw new IllegalArgumentException("pick from an empty pool");
        return xs.get(ints(0, xs.size() - 1));
    }

    @SafeVarargs
    public final <T> T pick(T... xs) { return pick(List.of(xs)); }

    /** {@code st.sampled_from}: the same draw as {@link #pick}. */
    public <T> T sampled(List<? extends T> xs) { return pick(xs); }

    /** {@code st.just}: no draw. */
    public <T> T just(T x) { return x; }

    /** {@code st.one_of}: an index, then the alternative. */
    @SafeVarargs
    public final <T> T oneOf(Gen<? extends T>... alternatives) {
        return draw(alternatives[ints(0, alternatives.length - 1)]);
    }

    /**
     * {@code st.lists(element, min_size, max_size)}: a length in [min, max] (uniform) then the
     * elements, each in its own sub-structure. The shrinker removes ranges of elements.
     */
    public <T> List<T> lists(int min, int max, Gen<T> element) {
        if (min > max) throw new IllegalArgumentException("empty size range");
        int mark = session.mark();
        Generator<T> item = Generator.from(env2 -> element.draw(new Draw(env2, session)));
        List<T> out = env.generate(Generator.listsOf(new Forced(IntDistribution.uniform(min, max), session), item));
        session.insert(mark, out.size());
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    /**
     * {@code st.lists(element, min_size, max_size, unique=True)}: a length in [min, max], then the
     * elements; an element equal to an earlier one is redrawn in the same sub-structure (up to 10
     * times, after which the slot is dropped, as Hypothesis gives up on a run of duplicates). Fewer
     * than {@code min} distinct elements rejects the draw. Equality is {@code equals}; the set is
     * only consulted, never iterated.
     */
    public <T> List<T> uniqueLists(int min, int max, Gen<T> element) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        List<T> raw = lists(min, max, d -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                T x = d.draw(element);
                if (seen.add(x)) return x;
            }
            return null;
        });
        List<T> out = new ArrayList<>(raw.size());
        for (T x : raw) if (x != null) out.add(x);
        if (out.size() < min) Reject.reject("too few distinct elements");
        return Collections.unmodifiableList(out);
    }

    /**
     * {@code st.floats(lo, hi, allow_nan=False, allow_infinity=False)}: a whole part and a
     * 1/1024 fraction, shrinking to {@code lo}. Coarser than Hypothesis's floats; the
     * interesting floats come from the explicit pools.
     */
    public double floats(double lo, double hi) {
        if (!(lo <= hi)) throw new IllegalArgumentException("empty float range");
        double span = hi - lo;
        int whole = ints(0, (int) Math.min(Integer.MAX_VALUE - 1, Math.floor(span)));
        int frac = ints(0, 1023);
        return Math.min(hi, lo + whole + frac / 1024.0);
    }

    /**
     * {@code st.text(max_size=n)}: up to n code points, three in four ASCII; shrinks to the empty
     * string and towards NUL. Never a lone surrogate (as Hypothesis's text).
     */
    public String text(int maxSize) {
        List<Integer> cps = lists(0, maxSize, d -> {
            if (d.ints(0, 3) < 3) return d.ints(0, 127);
            int v = d.ints(0, 0x10FFFF - 0x800);
            return v < 0xD800 ? v : v + 0x800;
        });
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) sb.appendCodePoint(cp);
        return sb.toString();
    }

    // ------------------------------------------------------------------------- composition
    /** {@code draw(strategy)}: the sub-draw runs in its own sub-structure (a Hypothesis span). */
    public <T> T draw(Gen<? extends T> g) {
        return env.generate(Generator.from(env2 -> g.draw(new Draw(env2, session))));
    }

    /**
     * {@link #draw(Gen)} with a label: every sub-draw with the same label shares one jetCheck
     * generator identity, so the shrinker may replace such a structure by a nested one of the same
     * label (a statement list by an inner statement list). Use it for recursive strategies only.
     */
    public <T> T draw(String label, Gen<? extends T> g) {
        Generator<Object> labelled = LABELLED.computeIfAbsent(label, l -> Generator.from(Draw::runPending));
        session.pending.push(g);
        @SuppressWarnings("unchecked") T v = (T) env.generate(labelled);
        return v;
    }

    private static final Map<String, Generator<Object>> LABELLED = new ConcurrentHashMap<>();

    private static Object runPending(GenerationEnvironment env2) {
        // The session is reached through the environment's thread: a labelled generator is shared
        // by every session, so it cannot capture one. Engine binds the session to the thread.
        Engine.Session s = Engine.Session.current();
        Gen<?> g = s.pending.pop();
        return g.draw(new Draw(env2, s));
    }

    // ------------------------------------------------------------------------- the distributions
    /**
     * A wide bounded range drawn the way Hypothesis's {@code st.integers(lo, hi)} came out when
     * measured (4000 seeded draws of {@code integers(0, 599)}: mean 250, 14.7 % in the first tenth,
     * about 1.5 % on each endpoint): a mixture of uniform over the range (0.6), uniform over its
     * lower half (0.375), and each endpoint (1/64 each). Valid values are the whole range, so
     * replay and shrinking are unaffected.
     */
    record Biased(int lo, int hi) implements IntDistribution {
        @Override public int generateInt(Random r) {
            long span = (long) hi - lo;
            double u = r.nextDouble();
            if (u < 1.0 / 64) return lo;
            if (u < 2.0 / 64) return hi;
            if (u < 2.0 / 64 + 0.375) return (int) (lo + (long) (r.nextDouble() * (span / 2 + 1)));
            return (int) (lo + (long) (r.nextDouble() * (span + 1)));
        }

        @Override public boolean isValidValue(int i) { return lo <= i && i <= hi; }

        @Override public int getMin() { return lo; }

        @Override public int getMax() { return hi; }
    }

    /**
     * Every draw's distribution. Normally it is the wrapped one; while the engine force-feeds a
     * recorded {@link Choices} into jetCheck's own checker (to shrink it), {@code generateInt}
     * answers the recorded values instead of random ones.
     */
    record Forced(IntDistribution d, Engine.Session session) implements IntDistribution {
        @Override public int generateInt(Random random) {
            return session.forcing() ? session.nextForced(d) : d.generateInt(random);
        }

        @Override public boolean isValidValue(int i) { return d.isValidValue(i); }

        @Override public int getMin() { return d.getMin(); }

        @Override public int getMax() { return d.getMax(); }
    }
}
