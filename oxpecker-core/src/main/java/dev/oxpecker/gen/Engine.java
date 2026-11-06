package dev.oxpecker.gen;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jetbrains.jetCheck.GenerationEnvironment;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.IntSource;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jetbrains.jetCheck.PropertyFalsified;

/**
 * Running {@link Gen}s: fresh draws, exact replay, prefix-then-fresh extension (what Hypothesis's
 * {@code ConjectureData(prefix=..., random=...)} and {@code for_choices} did), and shrinking a
 * recorded example.
 *
 * <p>Generation uses jetCheck's {@link GenerationEnvironment#generative} over an {@link IntSource}
 * of ours. Shrinking uses jetCheck's own checker: {@code forAll} with one iteration whose first
 * (and only) generation is force-fed the recorded choices through {@link Draw.Forced}, so
 * jetCheck finds exactly that example "failing" and shrinks it with its structural shrinker. The
 * property counts calls and passes unconditionally once the budget is spent (jetCheck has no
 * shrink budget), so the rest of the shrink costs generation only.
 */
public final class Engine {
    private Engine() {}

    static final int SIZE_HINT = 100;     // unused by Draw's explicit distributions; jetCheck wants one

    /** A value and the choices that drew it. */
    public record Drawn<T>(T value, Choices choices) {}

    /** Why a generation produced no value. */
    public enum Failure { REJECTED, EXHAUSTED, INVALID }

    // --------------------------------------------------------------------------- session
    /** Per-generation state: the recorded choices, the forced stream, pending labelled draws. */
    static final class Session {
        private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
        private int[] rec = new int[64];
        private int n = 0;
        private int[] forced = null;
        private int forcedPos = 0;
        boolean forcedMismatch = false;
        final Deque<Gen<?>> pending = new ArrayDeque<>();

        static Session current() {
            Session s = CURRENT.get();
            if (s == null) throw new IllegalStateException("a labelled draw outside an Engine run");
            return s;
        }

        void reset() {
            n = 0;
            pending.clear();
        }

        void record(int v) {
            if (n == rec.length) rec = Arrays.copyOf(rec, n * 2);
            rec[n++] = v;
        }

        int mark() { return n; }

        void insert(int at, int v) {
            record(0);
            System.arraycopy(rec, at, rec, at + 1, n - 1 - at);
            rec[at] = v;
        }

        Choices recorded() { return new Choices(Arrays.copyOf(rec, n)); }

        boolean forcing() { return forced != null; }

        int nextForced(IntDistribution d) {
            if (forcedPos >= forced.length || !d.isValidValue(forced[forcedPos])) {
                forcedMismatch = true;
                throw new Reject("forced choices do not fit");
            }
            return forced[forcedPos++];
        }
    }

    /** The root jetCheck generator of a run: a fresh recording per generation, rejections as values. */
    private static <T> Generator<Result<T>> root(Gen<T> g, Session s) {
        return Generator.from(env -> {
            Session prev = Session.CURRENT.get();
            Session.CURRENT.set(s);
            s.reset();
            try {
                T v = g.draw(new Draw(env, s));
                return new Result<>(v, s.recorded(), null);
            } catch (Reject e) {
                return new Result<>(null, s.recorded(), Failure.REJECTED);
            } catch (Exhausted e) {
                return new Result<>(null, s.recorded(), Failure.EXHAUSTED);
            } catch (Invalid e) {
                return new Result<>(null, s.recorded(), Failure.INVALID);
            } finally {
                if (prev == null) Session.CURRENT.remove(); else Session.CURRENT.set(prev);
            }
        });
    }

    /** A generation's outcome: a value and its choices, or a failure. */
    record Result<T>(T value, Choices choices, Failure failure) {
        boolean ok() { return failure == null; }
    }

    static final class Exhausted extends RuntimeException {
        Exhausted() { super(null, null, false, false); }
    }

    static final class Invalid extends RuntimeException {
        Invalid() { super(null, null, false, false); }
    }

    /** Replays {@code prefix} exactly, then draws from {@code rng} (or fails, when it is null). */
    static final class PrefixSource implements IntSource {
        private final int[] prefix;
        private final Random rng;
        private int pos = 0;

        PrefixSource(Choices prefix, Random rng) {
            this.prefix = prefix == null ? new int[0] : prefix.values();
            this.rng = rng;
        }

        @Override public int drawInt(IntDistribution d) {
            if (pos < prefix.length) {
                int v = prefix[pos++];
                if (!d.isValidValue(v)) throw new Invalid();
                return v;
            }
            if (rng == null) throw new Exhausted();
            return d.generateInt(rng);
        }
    }

    private static <T> Result<T> run(Gen<T> g, IntSource source) {
        Session s = new Session();
        return GenerationEnvironment.generative(source, SIZE_HINT).generate(root(g, s));
    }

    // --------------------------------------------------------------------------- generation
    /** A fresh draw from {@code rng}; null when the draw rejected. */
    public static <T> Drawn<T> fresh(Gen<T> g, Random rng) { return withPrefix(g, null, rng); }

    /** A fresh draw from a seed (Python's {@code draw(strategy, seed)}); null when it rejected. */
    public static <T> Drawn<T> fresh(Gen<T> g, long seed) { return withPrefix(g, null, new Random(seed)); }

    /**
     * {@code prefix} replayed exactly, then fresh draws from {@code rng}; null when the draw
     * rejected or the prefix does not fit (Python's {@code seeded_data(prefix=...)}).
     */
    public static <T> Drawn<T> withPrefix(Gen<T> g, Choices prefix, Random rng) {
        Result<T> r = run(g, new PrefixSource(prefix, rng));
        return r.ok() ? new Drawn<>(r.value, r.choices) : null;
    }

    /**
     * The value {@code choices} draws, or null when they no longer decode (the strategies changed
     * since they were recorded, they run out, or the draw rejects). Choices left over are ignored.
     */
    public static <T> T replay(Gen<T> g, Choices choices) {
        Result<T> r = run(g, new PrefixSource(choices, null));
        return r.ok() ? r.value : null;
    }

    /** Like {@link #replay} but with the choices actually consumed. */
    public static <T> Drawn<T> replayDrawn(Gen<T> g, Choices choices) {
        Result<T> r = run(g, new PrefixSource(choices, null));
        return r.ok() ? new Drawn<>(r.value, r.choices) : null;
    }

    // --------------------------------------------------------------------------- shrinking
    /** The outcome of a {@link #shrink}: the smallest interesting value seen, and the calls spent. */
    public record Shrunk<T>(T value, Choices choices, int calls, boolean reproduced) {}

    /**
     * Shrinks the example {@code choices} draws while {@code interesting} holds, with at most
     * {@code budget} calls of {@code interesting}. Returns the smallest interesting value seen
     * ({@code size} decides; ties keep the earlier one) with its choices, or null when the
     * example itself is not interesting (it does not reproduce) or does not decode.
     *
     * <p>{@code interesting} may throw: an exception counts as "not interesting". Calls on the
     * same choices are answered once (jetCheck re-checks the first example before shrinking).
     */
    public static <T> Shrunk<T> shrink(Gen<T> g, Choices choices, Predicate<? super T> interesting, int budget,
                                       ToIntFunction<? super T> size) {
        Session s = new Session();
        s.forced = choices.values();
        Map<Choices, Boolean> seen = new HashMap<>();
        int[] calls = {0};
        boolean[] first = {true, false};          // first generation pending, reproduced
        Object[] best = {null, null};             // value, choices
        Predicate<Result<T>> property = r -> {
            if (first[0]) {
                first[0] = false;
                s.forced = null;
                if (!r.ok() || s.forcedMismatch || !r.choices.equals(choices)) return true;
            }
            if (!r.ok()) return true;
            Boolean known = seen.get(r.choices);
            if (known != null) return !known;
            if (calls[0] >= budget) return true;
            calls[0]++;
            boolean bad;
            try {
                bad = interesting.test(r.value);
            } catch (RuntimeException e) {
                bad = false;
            }
            seen.put(r.choices, bad);
            if (bad) {
                if (r.choices.equals(choices)) first[1] = true;
                @SuppressWarnings("unchecked") T b = (T) best[0];
                if (b == null || size.applyAsInt(r.value) < size.applyAsInt(b)) {
                    best[0] = r.value;
                    best[1] = r.choices;
                }
            }
            return !bad;
        };
        try {
            PropertyChecker.customized().silent().withSeed(0).withIterationCount(1).forAll(root(g, s), property);
        } catch (PropertyFalsified e) {
            // the expected end: jetCheck shrank as far as it could (or the budget ran out)
        }
        if (!first[1] || best[0] == null) return null;
        @SuppressWarnings("unchecked") T v = (T) best[0];
        return new Shrunk<>(v, (Choices) best[1], calls[0], true);
    }
}
