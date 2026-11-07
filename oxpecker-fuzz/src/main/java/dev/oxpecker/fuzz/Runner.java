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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Runs a lineage's final program; with {@code require}, a coverage replay (shrink on insert). */
@FunctionalInterface
public interface Runner {
    Observation run(Lineage lineage, Map<String, List<Integer>> require);

    default Observation run(Lineage lineage) { return run(lineage, null); }

    /** A run that has been started but not yet looked at. */
    @FunctionalInterface
    interface Pending {
        /** Waits for the run if need be, then compares its outcomes (on the calling thread). */
        Observation await();
    }

    /**
     * Starts running a lineage's final program and returns without waiting for it, so the caller
     * can start the next one. By default nothing starts until {@link Pending#await}, which then
     * runs it as {@link #run} would.
     */
    default Pending submit(Lineage lineage) { return () -> run(lineage); }

    List<String> SLOW = List.of("timeout", "resource");

    /**
     * Runs a lineage's final program through a harness (an Ensemble), asking the guide for new probe
     * ids. A run with {@code require} is a coverage replay: only the guide runs. {@code submit}
     * queues the case on the harness's processes; the outcomes are compared when awaited.
     */
    static Runner harness(Harness client, int languageVersion) {
        return new Runner() {
            private RunOptions options(Lineage lineage, Map<String, List<Integer>> require) {
                RunOptions o = RunOptions.DEFAULT.withLanguageVersion(languageVersion).withSeed(1)
                        .withCoverageBucket(lineage.template()).withCoverageNewIds(true).withCoverageRequire(require);
                return require != null ? o.withComparators(List.of()) : o;
            }

            @Override public Observation run(Lineage lineage, Map<String, List<Integer>> require) {
                return observe(client.run(lineage.source(), options(lineage, require)));
            }

            @Override public Pending submit(Lineage lineage) {
                CompletableFuture<RunResult> f = client.submit(lineage.source(), options(lineage, null));
                return () -> {
                    try {
                        return observe(f.join());
                    } catch (CompletionException e) {
                        if (e.getCause() instanceof RuntimeException r) throw r;
                        if (e.getCause() instanceof Error err) throw err;
                        throw e;
                    }
                };
            }
        };
    }

    private static Observation observe(RunResult res) {
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
    }
}
