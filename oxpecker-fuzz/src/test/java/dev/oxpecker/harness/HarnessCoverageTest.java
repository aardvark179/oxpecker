package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Per-run probe sets from the harness (decision D36): which probes a run hit for the first time,
 * and which probes of a required set P a run reaches -- what shrink-on-insert needs, because on a
 * re-run nothing is new any more. Needs the harness coverage jar. */
@Tag("slow")
class HarnessCoverageTest {
    static final Path COV_JAR = Oxpecker.harnessLibs(Oxpecker.DEFAULT_HARNESS).resolve("oxpecker-harness-cov.jar");
    static final String PROGRAM = """

            var b = 1n; var n = 0;
            for (var i = 0; i < 3; i++) { n += i; }
            var s = String(b * 3n) + [1, 2, 3].map(function (x) { return x * 2; }).join(",");
            """;

    /** A fresh JVM per test: "new" is relative to everything this JVM ran. */
    HarnessClient cov;

    @BeforeEach
    void start() {
        assumeTrue(Files.exists(Oxpecker.harnessBin()) && Files.exists(COV_JAR), "harness/coverage jar not built");
        cov = new HarnessClient(Oxpecker.harnessBin(), null, true, true).start();
        assumeTrue(Boolean.TRUE.equals(cov.info().get("coverage")), "coverage agent not attached");
        cov.run("1;");                                  // warm up: JVM- and Rhino-startup probes out of the way
    }

    @AfterEach
    void stop() {
        if (cov != null) cov.stop();
    }

    static Map<String, Set<Integer>> union(RunResult run, Function<Outcome, Map<String, List<Integer>>> field) {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (Outcome o : run.results().values()) {
            Map<String, List<Integer>> f = field.apply(o);
            if (f == null) continue;
            f.forEach((cls, ids) -> out.computeIfAbsent(cls, k -> new HashSet<>()).addAll(ids));
        }
        return out;
    }

    static int size(Map<String, ? extends java.util.Collection<Integer>> probes) {
        return probes.values().stream().mapToInt(java.util.Collection::size).sum();
    }

    static Map<String, List<Integer>> sortedLists(Map<String, Set<Integer>> m) {
        Map<String, List<Integer>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, v.stream().sorted().toList()));
        return out;
    }

    @Test
    void newProbeIdsAddUpToTheNewProbeCount() {
        RunResult run = cov.run(PROGRAM, RunOptions.DEFAULT.withCoverageNewIds(true));
        for (Outcome o : run.results().values()) {
            assertNotNull(o.newProbeIds);
            assertEquals((int) o.newProbes, o.newProbeIds.values().stream().mapToInt(List::size).sum());
        }
        assertTrue(size(union(run, o -> o.newProbeIds)) > 0);
    }

    @Test
    void aRerunReachesItsProbesThoughNoneAreNew() {
        RunResult first = cov.run(PROGRAM + "\nvar again = 1;", RunOptions.DEFAULT.withCoverageNewIds(true));
        Map<String, List<Integer>> p = sortedLists(union(first, o -> o.newProbeIds));
        RunResult rerun = cov.run(PROGRAM + "\nvar again = 1;", RunOptions.DEFAULT.withCoverageRequire(p));
        assertTrue(rerun.results().values().stream().allMatch(o -> Integer.valueOf(0).equals(o.newProbes)));
        Map<String, Set<Integer>> reached = union(rerun, o -> o.requiredReached);
        reached.forEach((cls, ids) -> assertTrue(p.get(cls).containsAll(ids)));
        double share = (double) size(reached) / Math.max(1, size(p));
        // What is not reached again is once-per-JVM (class init, lazy caches): the share
        // shrink-on-insert must filter out before using P as its property (critique §4.3).
        System.out.printf("re-run reaches %.0f%% of the probes that were new%n", share * 100);
        assertTrue(share >= 0.5);
    }

    @Test
    void aDifferentProgramReachesMuchLessOfTheSet() {
        RunResult first = cov.run(PROGRAM + "\nvar third = [].concat([1n]);", RunOptions.DEFAULT.withCoverageNewIds(true));
        Map<String, List<Integer>> p = sortedLists(union(first, o -> o.newProbeIds));
        RunResult full = cov.run(PROGRAM + "\nvar third = [].concat([1n]);", RunOptions.DEFAULT.withCoverageRequire(p));
        RunResult other = cov.run("var z = 2;", RunOptions.DEFAULT.withCoverageRequire(p));
        assertTrue(size(union(other, o -> o.requiredReached)) < size(union(full, o -> o.requiredReached)));
    }

    @Test
    void aRestartedJvmResumesFromTheCoverageCheckpoint(@TempDir Path tmp) {
        // Without it every probe is new again after a restart, and the fuzz loop adds whatever runs
        // next to the corpus: 71 % of the runs of a three-hour campaign (2026-11-01).
        String exec = tmp.resolve("coverage.exec").toString();
        java.util.function.IntSupplier newAfterRestart = () -> {
            cov.run(PROGRAM);
            cov.coverageSave(exec);
            cov.restart();
            return cov.run(PROGRAM).results().values().stream().mapToInt(o -> o.newProbes == null ? 0 : o.newProbes).sum();
        };
        int lost = newAfterRestart.getAsInt();
        cov.setCoverageCheckpoint(exec);
        int resumed = newAfterRestart.getAsInt();
        System.out.printf("new probes after a restart: %d without the checkpoint, %d with it%n", lost, resumed);
        assertTrue(lost > 1000);
        assertTrue(resumed <= lost / 20);
    }
}
