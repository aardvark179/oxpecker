package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.gen.Engine;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate;
import dev.oxpecker.il.Mutate.Lineage;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The choice-sequence shrinker over a lineage (fuzz.Shrinker): jetCheck's structural shrinker,
 * started from the lineage's recorded choices ({@link Engine#shrink}), running the program for
 * each candidate while {@code interesting} holds, at most {@code budget} runs. The smallest
 * lineage (by instruction count) that stayed interesting, with its choices and observation, is
 * the result; null when the lineage itself is not interesting on its re-run.
 */
public final class Shrinker {
    public record Result(Lineage lineage, Choices choices, Observation observation) {}

    private final Runner runner;
    private final GenOptions options;
    private final int steps;
    private final BiPredicate<Lineage, Observation> interesting;
    private final int budget;
    public int calls = 0;

    public Shrinker(Runner runner, GenOptions options, int steps, BiPredicate<Lineage, Observation> interesting, int budget) {
        this.runner = runner;
        this.options = options;
        this.steps = steps;
        this.interesting = interesting;
        this.budget = budget;
    }

    /** {@code cap} is the lineage's own cap (G49); {@code steps} stands in when null. */
    public Result shrink(Choices choices, Integer cap) {
        calls = 0;
        Map<Lineage, Observation> seen = new IdentityHashMap<>();
        Engine.Shrunk<Lineage> s = Engine.shrink(Mutate.evolved(options, cap != null ? cap : steps), choices, l -> {
            calls++;
            Observation obs = runner.run(l);
            boolean hit = interesting.test(l, obs);
            if (hit) seen.put(l, obs);
            return hit;
        }, budget, l -> l.code().size());
        if (s == null) return null;
        return new Result(s.value(), s.choices(), seen.get(s.value()));
    }

    public Result shrink(Choices choices) { return shrink(choices, null); }
}
