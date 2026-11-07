package dev.oxpecker.harness;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What runs cases: one {@link HarnessClient} (one JVM) or an {@link Ensemble} of them. The
 * Python side used either interchangeably; this is that duck type.
 */
public interface Harness extends AutoCloseable {

    /** Runs one case; see {@link RunOptions} for the arguments. */
    RunResult run(String source, RunOptions options);

    /** Starts one case without waiting for it: an {@link Ensemble} queues it on its processes and
     * returns at once; anything else runs it before returning. */
    default CompletableFuture<RunResult> submit(String source, RunOptions options) {
        return CompletableFuture.completedFuture(run(source, options));
    }

    default RunResult run(String source) {
        return run(source, RunOptions.DEFAULT);
    }

    /** The harness's {@code info}, as sent. */
    Map<String, Object> info();

    /** Writes the coverage so far to {@code path} (a JaCoCo .exec); returns the covered probes. */
    int coverageSave(String path);

    /** Merges a saved .exec into the coverage so far; returns the covered probes. */
    int coverageLoad(String path);

    /** Per-bucket Rhino coverage. */
    Map<String, Object> coverageReport();

    void stop();

    @Override
    default void close() {
        stop();
    }
}
