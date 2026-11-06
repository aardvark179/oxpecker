package dev.oxpecker.fuzz;

import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import java.util.List;
import java.util.Map;

/**
 * What one run of a lineage's final program showed. {@code run} may be null for a stand-in
 * runner; {@code ms} is the run time of the slowest mode; {@code timedOut}: a mode hit the
 * instruction budget, the watchdog or a resource limit.
 */
public record Observation(Verdict verdict, RunResult run, int newProbes, Map<String, List<Integer>> newProbeIds,
                          Map<String, List<Integer>> requiredReached, long ms, boolean timedOut) {
    public Observation {
        newProbeIds = newProbeIds == null ? Map.of() : newProbeIds;
        requiredReached = requiredReached == null ? Map.of() : requiredReached;
    }

    public Observation(Verdict verdict, RunResult run, int newProbes, Map<String, List<Integer>> newProbeIds,
                       Map<String, List<Integer>> requiredReached) {
        this(verdict, run, newProbes, newProbeIds, requiredReached, 0, false);
    }
}
