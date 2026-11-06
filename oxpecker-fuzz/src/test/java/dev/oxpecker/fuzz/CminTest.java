package dev.oxpecker.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.harness.HarnessClient;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.util.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corpus minimisation against Rhino (tests/test_cmin.py's slow test): the kept entries alone
 * must cover what the whole corpus covers, and the choice must not depend on the order entries
 * were found in. The choice itself is tested without Rhino in {@link FuzzTest}.
 */
@Tag("slow")
class CminTest {
    static final GenOptions OPTIONS = GenOptions.builder().maxStatements(5).maxDepth(2).build();
    static final int STEPS = 3;
    static final PrintStream QUIET = new PrintStream(java.io.OutputStream.nullOutputStream());

    static HarnessClient coverageClient() {
        Assumptions.assumeTrue(Files.exists(Oxpecker.harnessBin()), "no harness launcher");
        HarnessClient c = new HarnessClient(Oxpecker.harnessBin(), null, true, true).start();
        if (!Boolean.TRUE.equals(c.info().get("coverage"))) {
            c.stop();
            Assumptions.abort("coverage agent not attached");
        }
        return c;
    }

    /** Probes covered by replaying every entry of a corpus in a fresh JVM. */
    static int replayCoverage(Path corpusDir, Path exec) {
        Corpus corpus = new Corpus(corpusDir);
        HarnessClient c = coverageClient();
        try {
            for (int i = 0; i < corpus.entries.size(); i++) {
                Lineage l = corpus.replay(i, OPTIONS, STEPS);
                c.run(l.source(), RunOptions.DEFAULT.withSeed(1).withCoverageBucket(l.template()));
            }
            return c.coverageSave(exec.toString());
        } finally {
            c.stop();
        }
    }

    @Test void aRunReportsEveryProbeItHitWhenAsked() {
        HarnessClient c = coverageClient();
        try {
            RunResult plain = c.run("var a = [1, 2].map(function (x) { return x + 1; });", RunOptions.DEFAULT);
            RunResult hits = c.run("var a = [1, 2].map(function (x) { return x + 1; });", RunOptions.DEFAULT.withCoverageHits(true));
            for (Outcome o : plain.results().values()) assertTrue(o.hitProbes == null || o.hitProbes.isEmpty());
            for (Outcome o : hits.results().values()) {
                assertTrue(o.hitProbes != null && !o.hitProbes.isEmpty(), o.mode + " reported no hit probes");
                assertTrue(o.hitProbes.keySet().stream().anyMatch(k -> k.startsWith("org/mozilla/javascript/")), o.hitProbes.keySet().toString());
            }
            // the second run's probes are not new, but they are still hits
            assertFalse(Cmin.runHits(hits).isEmpty());
        } finally {
            c.stop();
        }
    }

    @Test void keptEntriesCoverWhatTheCorpusCoversWhateverTheOrder(@TempDir Path tmp) throws Exception {
        HarnessClient c = coverageClient();
        try {
            Fuzz.Config cfg = new Fuzz.Config();
            cfg.corpusDir = tmp.resolve("found");
            cfg.options = OPTIONS;
            cfg.iterations = 60;
            cfg.steps = STEPS;
            cfg.seed = 3L;
            cfg.runner = Runner.harness(c, 200);
            cfg.findingsDir = tmp.resolve("findings");
            cfg.shrinkBudget = 5;
            cfg.sourceShrinkBudget = 0;
            cfg.quiet = true;
            Fuzz.fuzz(cfg);
        } finally {
            c.stop();
        }
        int entries = new Corpus(tmp.resolve("found")).entries.size();
        assertTrue(entries >= 10, "entries " + entries);
        // the same corpus with its lines in reverse order
        Files.createDirectories(tmp.resolve("reversed"));
        List<String> lines = new ArrayList<>(Files.readAllLines(tmp.resolve("found/corpus.jsonl")));
        Collections.reverse(lines);
        Files.writeString(tmp.resolve("reversed/corpus.jsonl"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        Map<String, Set<Integer>> kept = new HashMap<>();
        for (String name : List.of("found", "reversed")) {
            HarnessClient m = coverageClient();
            Map<String, Object> s;
            try {
                s = Cmin.minimise(tmp.resolve(name), tmp.resolve("min-" + name), m, OPTIONS, STEPS, 200, true, QUIET);
            } finally {
                m.stop();
            }
            assertTrue(((List<?>) s.get("undecodable")).isEmpty());
            assertTrue((int) s.get("rebuilt_from_parent") > 0, "children are rebuilt from their parent, not replayed");
            int chosen = (int) s.get("probes_chosen"), covered = (int) s.get("covered_probes"), keptN = (int) s.get("kept");
            // the kept entries' own re-run reaches what they were chosen on, but for a few probes that
            // depend on timing or on what the JVM ran before
            assertTrue(chosen > 0, s.toString());
            assertTrue(covered >= chosen * 0.995, s.toString());
            assertTrue(keptN > 0 && keptN <= entries, s.toString());
            assertEquals(keptN, ((Number) Json.parseObject(Files.readString(tmp.resolve("min-" + name + "/cmin.json"))).get("kept")).intValue());
            // replaying the new corpus as the fuzz loop would reaches the same, within that noise
            assertTrue(replayCoverage(tmp.resolve("min-" + name), tmp.resolve(name + ".exec")) >= chosen * 0.995);
            Set<Integer> ids = new HashSet<>();
            for (Entry e : new Corpus(tmp.resolve("min-" + name)).entries) ids.add(e.id);
            kept.put(name, ids);
        }
        // the smallest entry per probe does not depend on the order (ties of equal size aside)
        Set<Integer> sym = new HashSet<>(kept.get("found"));
        sym.addAll(kept.get("reversed"));
        Set<Integer> both = new HashSet<>(kept.get("found"));
        both.retainAll(kept.get("reversed"));
        sym.removeAll(both);
        assertTrue(sym.size() <= Math.max(2, entries / 10), "order-dependent: " + sym);
    }
}
