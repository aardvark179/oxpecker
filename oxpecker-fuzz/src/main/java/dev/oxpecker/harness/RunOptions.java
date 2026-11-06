package dev.oxpecker.harness;

import java.util.List;
import java.util.Map;

/**
 * The keyword arguments of {@code HarnessClient.run} / {@code Ensemble.run}, with the same
 * defaults. Immutable; change one with the {@code withX} methods:
 * {@code RunOptions.DEFAULT.withLanguageVersion(250).withSourceName("x.js")}.
 *
 * @param modes          HarnessClient: the modes to run (null: both, or the pinned one).
 *                       Ensemble: run only the comparators whose mode is one of these.
 * @param comparators    Ensemble only: the comparators to run beside the guide (null: all; empty:
 *                       the guide alone). A HarnessClient refuses it.
 * @param continuations  null: auto-detected (only scripts that pause need the continuation runner)
 * @param coverageHits   ask for every probe the run hit (Outcome.hitProbes), for corpus minimisation
 */
public record RunOptions(List<String> modes, List<String> comparators, int languageVersion, boolean strict,
                         long instructionBudget, int wallMs, long seed, String id, String sourceName,
                         Boolean continuations, String coverageBucket, boolean coverageNewIds,
                         Map<String, List<Integer>> coverageRequire, boolean coverageHits) {

    public static final RunOptions DEFAULT = new RunOptions(null, null, 200, false, 5_000_000, 5000, 1, null,
            "case.js", null, null, false, null, false);

    public static RunOptions defaults() {
        return DEFAULT;
    }

    public RunOptions withModes(List<String> v) {
        return new RunOptions(v, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withModes(String... v) {
        return withModes(List.of(v));
    }

    public RunOptions withComparators(List<String> v) {
        return new RunOptions(modes, v, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withComparators(String... v) {
        return withComparators(List.of(v));
    }

    public RunOptions withLanguageVersion(int v) {
        return new RunOptions(modes, comparators, v, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withStrict(boolean v) {
        return new RunOptions(modes, comparators, languageVersion, v, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withInstructionBudget(long v) {
        return new RunOptions(modes, comparators, languageVersion, strict, v, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withWallMs(int v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, v, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withSeed(long v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, v, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withId(String v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, v, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withSourceName(String v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, v,
                continuations, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withContinuations(Boolean v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                v, coverageBucket, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withCoverageBucket(String v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, v, coverageNewIds, coverageRequire, coverageHits);
    }

    public RunOptions withCoverageNewIds(boolean v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, v, coverageRequire, coverageHits);
    }

    public RunOptions withCoverageRequire(Map<String, List<Integer>> v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, v, coverageHits);
    }

    public RunOptions withCoverageHits(boolean v) {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, coverageBucket, coverageNewIds, coverageRequire, v);
    }

    /** The options a comparator gets from an Ensemble: the guide-only coverage arguments
     * (bucket, new ids, require) dropped. */
    RunOptions withoutGuideCoverage() {
        return new RunOptions(modes, comparators, languageVersion, strict, instructionBudget, wallMs, seed, id, sourceName,
                continuations, null, false, null, coverageHits);
    }
}
