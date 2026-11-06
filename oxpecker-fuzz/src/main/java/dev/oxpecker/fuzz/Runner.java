package dev.oxpecker.fuzz;

import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Harness;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.il.Mutate.Lineage;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Runs a lineage's final program; with {@code require}, a coverage replay (shrink on insert). */
@FunctionalInterface
public interface Runner {
    Observation run(Lineage lineage, Map<String, List<Integer>> require);

    default Observation run(Lineage lineage) { return run(lineage, null); }

    List<String> SLOW = List.of("timeout", "resource");

    /**
     * Runs a lineage's final program through a harness (an Ensemble), asking the guide for new probe
     * ids. A run with {@code require} is a coverage replay: only the guide runs.
     */
    static Runner harness(Harness client, int languageVersion) {
        return (lineage, require) -> {
            RunOptions o = RunOptions.DEFAULT.withLanguageVersion(languageVersion).withSeed(1)
                    .withCoverageBucket(lineage.template()).withCoverageNewIds(true).withCoverageRequire(require);
            if (require != null) o = o.withComparators(List.of());
            RunResult res = client.run(lineage.source(), o);
            Map<String, List<Integer>> newIds = ProbeSets.empty(), reached = ProbeSets.empty();
            int fresh = 0;
            Collection<Outcome> from = res.guide() != null && res.results().containsKey(res.guide())
                    ? List.of(res.results().get(res.guide())) : res.results().values();
            for (Outcome out : from) {
                fresh += out.newProbes != null ? out.newProbes : 0;
                ProbeSets.union(newIds, out.newProbeIds);
                ProbeSets.union(reached, out.requiredReached);
            }
            long ms = 0;
            boolean timedOut = false;
            for (Outcome out : res.results().values()) {
                ms = Math.max(ms, out.ms);
                timedOut |= SLOW.contains(out.status);
            }
            return new Observation(Compare.compareRun(res), res, fresh, newIds, reached, ms, timedOut);
        };
    }
}
