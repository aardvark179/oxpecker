package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.gen.Engine;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate;
import dev.oxpecker.il.Mutate.Lineage;
import java.util.Random;

/**
 * Drawing, replaying and extending lineages from their choices (the top of fuzz.py). A corpus
 * entry is a lineage stored as the {@link Choices} that draw it, so it can be replayed exactly,
 * extended by more mutation steps (the final "one more?" choice flipped to 1, the rest drawn
 * fresh) and shrunk.
 */
public final class Lineages {
    private Lineages() {}

    /** Safety cap on a lineage's depth across extension rounds (G49). */
    public static final int MAX_DEPTH = 20;

    /** A lineage and the choices that draw it. */
    public record Drawn(Lineage lineage, Choices choices) {}

    /**
     * Draws a lineage: replaying {@code prefix} first (exactly), then fresh choices from
     * {@code rng}. Null when the draw rejected or the prefix does not fit.
     */
    public static Drawn drawLineage(GenOptions options, int steps, Choices prefix, Random rng) {
        Engine.Drawn<Lineage> d = Engine.withPrefix(Mutate.evolved(options, steps), prefix, rng);
        return d == null ? null : new Drawn(d.value(), d.choices());
    }

    public static Drawn drawLineage(GenOptions options, int steps, Random rng) { return drawLineage(options, steps, null, rng); }

    public static Drawn drawLineage(GenOptions options, int steps, long seed) { return drawLineage(options, steps, null, new Random(seed)); }

    /**
     * The lineage a choice sequence draws, or null if it no longer decodes (the strategies changed
     * since it was recorded). {@code steps} must be the cap the sequence was drawn with (a corpus
     * entry records it as {@code cap}): the "one more?" draws depend on it.
     */
    public static Lineage replay(Choices choices, GenOptions options, int steps) {
        if (choices == null) return null;
        return Engine.replay(Mutate.evolved(options, steps), choices);
    }

    /**
     * The lineage {@code choices} draws, given {@code parent}, the lineage {@code parentChoices}
     * draws, when {@code choices} extends it the way {@link #extend} does: only the child's own draws
     * are replayed, not the whole history. Null when {@code choices} does not continue
     * {@code parentChoices} (a compacted entry) or no longer decodes; the caller falls back to
     * {@link #replay}.
     */
    public static Lineage continueLineage(Lineage parent, Choices parentChoices, Choices choices, GenOptions options, int cap) {
        Choices kept = parent.atCap() ? parentChoices : parentChoices.prefix(parentChoices.size() - 1);
        if (!choices.startsWith(kept) || choices.size() <= kept.size()) return null;
        return Engine.replay(Mutate.evolved(options, cap, null, parent), choices.suffix(kept.size()));
    }

    /**
     * A parent lineage with up to {@code steps} more mutation steps, drawn fresh (proposal §3.5).
     * {@code steps} is per round (G49): the child's cap is the parent's depth plus {@code steps},
     * bounded by {@code maxDepth}. Null when the parent is at {@code maxDepth} or its choices no
     * longer decode.
     *
     * <p>Only the new steps are drawn ({@code evolved(parent=...)}): {@code parent} is the lineage
     * {@code choices} draws, replayed here (with {@code cap}, its own cap) when not given. The
     * choices returned are the parent's, its stop flag (if it drew one) replaced by 1, plus the new
     * draws: the sequence a full replay with the child's cap consumes, so {@link #replay} and the
     * shrinker see no difference.
     */
    public static Drawn extend(Choices choices, GenOptions options, int steps, Random rng, Lineage parent, Integer cap, int maxDepth) {
        if (parent == null) {
            parent = replay(choices, options, cap != null ? cap : steps);
            if (parent == null) return null;
        }
        if (parent.depth() >= maxDepth || choices == null || choices.isEmpty()) return null;
        Choices kept;
        if (parent.atCap()) {
            kept = choices;                         // ended by its cap: no stop flag was drawn
        } else {
            if (choices.get(-1) != 0) return null;
            kept = choices.prefix(choices.size() - 1);
        }
        int newCap = Math.min(parent.depth() + steps, maxDepth);
        Engine.Drawn<Lineage> child = Engine.withPrefix(Mutate.evolved(options, newCap, null, parent), Choices.of(1), rng);
        if (child == null) return null;
        return new Drawn(child.value(), kept.concat(child.choices()));
    }

    public static Drawn extend(Choices choices, GenOptions options, int steps, Random rng, Lineage parent) {
        return extend(choices, options, steps, rng, parent, null, MAX_DEPTH);
    }
}
