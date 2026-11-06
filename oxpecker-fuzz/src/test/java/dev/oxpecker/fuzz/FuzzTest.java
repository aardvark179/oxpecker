package dev.oxpecker.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Instruction;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Program;
import dev.oxpecker.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The differential-testing loop without Rhino (tests/test_fuzz.py): a fake runner with a planted
 * bug and planted probes drives corpus growth, finding detection, shrinking and compaction. One
 * slow test runs the real harness for a few iterations.
 */
class FuzzTest {
    static final GenOptions OPTIONS = GenOptions.builder().maxStatements(5).maxDepth(2).build();
    static final int STEPS = 4;

    static String sig(Lineage l) { return Program.signature(l.code()); }

    /**
     * Probes = the operation classes a program uses (new ones are new probes, cumulatively, like
     * the JVM's store). The planted bug: a compound assignment anywhere in the program.
     */
    static class FakeRunner implements Runner {
        final Set<String> seen = new HashSet<>();
        int calls = 0;

        static int id(String n) { return Math.floorMod(n.hashCode(), 100000); }

        @Override public Observation run(Lineage lineage, Map<String, List<Integer>> require) {
            calls++;
            Set<String> names = new TreeSet<>();
            for (Instruction i : lineage.code()) names.add(i.op().name());
            List<Integer> ids = names.stream().map(FakeRunner::id).sorted().toList();
            Set<String> fresh = new TreeSet<>(names);
            fresh.removeAll(seen);
            seen.addAll(fresh);
            Map<String, List<Integer>> newIds = fresh.isEmpty() ? Map.of() : Map.of("ops", fresh.stream().map(FakeRunner::id).sorted().toList());
            Map<String, List<Integer>> reached = new java.util.LinkedHashMap<>();
            if (require != null)
                for (var e : require.entrySet())
                    reached.put(e.getKey(), new TreeSet<>(e.getValue()).stream().filter(ids::contains).toList());
            boolean bug = lineage.code().stream().anyMatch(i -> i.op() instanceof Ops.Update);
            Verdict v = bug ? new Verdict(Compare.HARD, List.of("planted: a compound assignment")) : new Verdict(Compare.EQUAL);
            return new Observation(v, null, fresh.size(), newIds, reached, 0, false);
        }
    }

    /** FakeRunner where one program in three (by a checksum of its text) runs past the watchdog. */
    static class HangingRunner extends FakeRunner {
        static boolean hangs(String source) {
            CRC32 c = new CRC32();
            c.update(source.getBytes(StandardCharsets.UTF_8));
            return c.getValue() % 3 == 0;
        }

        @Override public Observation run(Lineage lineage, Map<String, List<Integer>> require) {
            Observation o = super.run(lineage, require);
            if (hangs(lineage.source()))
                return new Observation(new Verdict(Compare.DISCARD, List.of("both timeout")), null, o.newProbes(), o.newProbeIds(),
                        o.requiredReached(), 8000, true);
            return new Observation(o.verdict(), null, o.newProbes(), o.newProbeIds(), o.requiredReached(), 10 + lineage.code().size(), false);
        }
    }

    static Fuzz.Config config(Path tmp, int iterations, long seed, Runner runner, int shrinkBudget) {
        Fuzz.Config c = new Fuzz.Config();
        c.corpusDir = tmp.resolve("corpus");
        c.options = OPTIONS;
        c.iterations = iterations;
        c.steps = STEPS;
        c.seed = seed;
        c.runner = runner;
        c.findingsDir = tmp.resolve("findings");
        c.shrinkBudget = shrinkBudget;
        c.quiet = true;
        return c;
    }

    @Test void drawReplayAndExtendRoundTrip() {
        Lineages.Drawn drawn = null;
        for (long seed = 3; drawn == null || drawn.lineage().atCap(); seed++) drawn = Lineages.drawLineage(OPTIONS, STEPS, seed);
        Lineage lineage = drawn.lineage();
        Choices choices = drawn.choices();
        assertEquals(0, choices.get(-1));                        // a complete lineage ends with "no more"
        Lineage again = Lineages.replay(choices, OPTIONS, STEPS);
        assertNotNull(again);
        assertEquals(sig(lineage), sig(again));
        assertEquals(choices, Choices.decode(choices.encode()));
        Lineages.Drawn longer = Lineages.extend(choices, OPTIONS, STEPS, new Random(7), null);
        assertNotNull(longer);
        assertEquals(Program.signature(lineage.base.code()), Program.signature(longer.lineage().base.code()));
        assertTrue(longer.choices().startsWith(choices.prefix(choices.size() - 1)));
        assertTrue(longer.lineage().depth() > lineage.depth());
        assertEquals(lineage.stepNames(), longer.lineage().stepNames().subList(0, lineage.steps.size()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void extensionDrawsOnlyTheNewStepsAndReplaysIdentically(int seed) {
        Lineages.Drawn drawn = Lineages.drawLineage(OPTIONS, STEPS, seed);
        assertNotNull(drawn);
        Lineage parent = drawn.lineage();
        Choices parentChoices = drawn.choices();
        assertEquals(STEPS, parent.cap);
        assertEquals(parent.atCap(), parentChoices.get(-1) != 0 || parent.depth() >= STEPS);
        Lineages.Drawn longer = Lineages.extend(parentChoices, OPTIONS, STEPS, new Random(seed), parent);
        assertNotNull(longer);
        Lineage child = longer.lineage();
        assertEquals(parent.depth() + STEPS, child.cap);
        assertTrue(child.depth() > parent.depth());
        int kept = parent.atCap() ? parentChoices.size() : parentChoices.size() - 1;
        assertTrue(longer.choices().startsWith(parentChoices.prefix(kept)));
        assertEquals(1, longer.choices().get(kept));
        Lineage again = Lineages.replay(longer.choices(), OPTIONS, child.cap);
        assertNotNull(again);
        assertEquals(sig(child), sig(again));
        assertEquals(child.stepNames(), again.stepNames());
        assertEquals(child.rejected, again.rejected);
        assertEquals(child.cap, again.cap);
        // without the parent in hand, extend replays it (from its own cap) and draws the same new steps
        Lineages.Drawn same = Lineages.extend(parentChoices, OPTIONS, STEPS, new Random(seed), null, parent.cap, Lineages.MAX_DEPTH);
        assertNotNull(same);
        assertEquals(longer.choices(), same.choices());
        // the safety cap on depth makes an entry a dead end, nothing else does
        assertNull(Lineages.extend(parentChoices, OPTIONS, STEPS, new Random(seed), parent, null, parent.depth()));
        assertEquals(parent.steps.size() + parent.rejected, parent.depth());
        assertEquals(STEPS, parent.cap);
    }

    @Test void aParentThatHitItsCapExtendsRoundAfterRound() {
        Lineages.Drawn drawn = null;
        for (int seed = 0; seed < 80; seed++) {
            drawn = Lineages.drawLineage(OPTIONS, STEPS, seed);
            if (drawn != null && drawn.lineage().atCap()) break;
            drawn = null;
        }
        if (drawn == null) fail("no lineage reached the cap in 80 seeds");
        Lineage parent = drawn.lineage();
        Choices choices = drawn.choices();
        List<Integer> depths = new ArrayList<>(List.of(parent.depth()));
        for (int round = 0; round < 3; round++) {
            Lineages.Drawn longer = Lineages.extend(choices, OPTIONS, STEPS, new Random(round), parent, null, 3 * STEPS);
            assertNotNull(longer, "round " + round + " refused at depth " + parent.depth());
            parent = longer.lineage();
            choices = longer.choices();
            depths.add(parent.depth());
            Lineage again = Lineages.replay(choices, OPTIONS, parent.cap);
            assertNotNull(again);
            assertEquals(sig(parent), sig(again));
        }
        List<Integer> sorted = new ArrayList<>(depths);
        sorted.sort(null);
        assertEquals(sorted, depths);
        assertTrue(depths.get(depths.size() - 1) > STEPS);
        assertNull(Lineages.extend(choices, OPTIONS, STEPS, new Random(9), parent, null, parent.depth()));
    }

    @Test void lineageCacheServesAddedEntriesAndReplaysLoadedOnes() {
        LineageCache cache = new LineageCache(2);
        List<Entry> entries = new ArrayList<>();
        List<Lineage> ls = new ArrayList<>();
        for (int seed = 0; seed < 3; seed++) {
            Lineages.Drawn d = Lineages.drawLineage(OPTIONS, STEPS, seed);
            entries.add(new Entry(seed, d.choices().encode(), d.lineage().template(), d.lineage().stepNames(), 0, null, null, 0, null, 0));
            ls.add(d.lineage());
        }
        cache.put(0, ls.get(0));
        assertSame(ls.get(0), cache.get(entries.get(0), OPTIONS, STEPS, null));
        assertEquals(1, cache.hits);
        Lineage loaded = cache.get(entries.get(1), OPTIONS, STEPS, null);       // a miss: replayed from the choices
        assertEquals(1, cache.misses);
        assertNotNull(loaded);
        assertEquals(sig(ls.get(1)), sig(loaded));
        assertSame(loaded, cache.get(entries.get(1), OPTIONS, STEPS, null));    // now cached
        cache.get(entries.get(2), OPTIONS, STEPS, null);                        // evicts the least recently used (0)
        assertNotEquals(System.identityHashCode(ls.get(0)), System.identityHashCode(cache.get(entries.get(0), OPTIONS, STEPS, null)));
        assertEquals(3, cache.misses);
    }

    @Test void aMissedEntryIsRebuiltFromItsParentExactlyAsAFullReplay(@TempDir Path tmp) {
        Fuzz.fuzz(config(tmp, 150, 4, new FakeRunner(), 5));
        Corpus corpus = new Corpus(tmp.resolve("corpus"));
        List<Entry> children = corpus.entries.stream().filter(e -> e.parent != null).toList();
        assertTrue(children.size() >= 5, "children " + children.size());
        assertTrue(children.stream().mapToInt(e -> e.steps.size()).max().orElse(0) > STEPS);   // more than one round deep
        LineageCache cache = new LineageCache(4);                   // small: ancestors get evicted and rebuilt too
        List<Entry> rev = new ArrayList<>(corpus.entries);
        java.util.Collections.reverse(rev);
        for (Entry e : rev) {
            Lineage rebuilt = cache.get(e, OPTIONS, STEPS, corpus.byId);
            Lineage full = Lineages.replay(Choices.decode(e.choices), OPTIONS, e.cap);
            assertNotNull(rebuilt);
            assertNotNull(full);
            assertEquals(full.source(), rebuilt.source());
            assertEquals(full.depth(), rebuilt.depth());
            assertEquals(full.atCap(), rebuilt.atCap());
            assertEquals(e.steps, rebuilt.stepNames());
        }
        assertTrue(cache.incremental >= children.size());
    }

    @Test void speedWeightIsAflsExecTimeFactor() {
        assertEquals(0.1, Fuzz.speedWeight(1100, 100));
        assertEquals(0.25, Fuzz.speedWeight(500, 100));
        assertEquals(0.75, Fuzz.speedWeight(150, 100));
        assertEquals(1.0, Fuzz.speedWeight(100, 100));
        assertEquals(3.0, Fuzz.speedWeight(20, 100));
        assertEquals(1.5, Fuzz.speedWeight(40, 100));
        assertEquals(1.0, Fuzz.speedWeight(0, 100));                 // no time recorded (older corpora): average
    }

    @Test void parentsWhoseChildrenTimeOutArePickedLess() {
        List<Entry> es = new ArrayList<>();
        for (int i = 0; i < 3; i++) es.add(new Entry(i, "", "t", List.of(), 1, null, null, 0, null, 20));
        List<Double> w = Fuzz.parentWeights(es, Map.of(1, 2, 2, 10));
        assertEquals(1.0, w.get(0));
        assertEquals(0.25, w.get(1));
        assertEquals(Math.pow(0.5, 6), w.get(2));
    }

    @Test void aTimedOutRunIsNotAddedToTheCorpus(@TempDir Path tmp) {
        Map<String, Object> summary = Fuzz.fuzz(config(tmp, 150, 5, new HangingRunner(), 5));
        @SuppressWarnings("unchecked") Map<String, Integer> stats = (Map<String, Integer>) summary.get("stats");
        assertTrue(stats.getOrDefault("timed-out-not-added", 0) > 0, stats.toString());
        Corpus corpus = new Corpus(tmp.resolve("corpus"));
        for (Entry e : corpus.entries) {
            assertFalse(HangingRunner.hangs(Lineages.replay(Choices.decode(e.choices), OPTIONS, e.cap).source()));
            assertTrue(e.ms > 0 && e.ms < 8000);
        }
    }

    @Test void cminBuildsEveryEntryDepthFirstExactlyAsAFullReplay(@TempDir Path tmp) {
        Fuzz.fuzz(config(tmp, 150, 4, new FakeRunner(), 5));
        Corpus corpus = new Corpus(tmp.resolve("corpus"));
        List<Cmin.Built> built = Cmin.lineagesDepthFirst(corpus, OPTIONS, STEPS);
        assertEquals(corpus.entries.stream().map(e -> e.id).sorted().toList(), built.stream().map(b -> b.entry().id).sorted().toList());
        for (Cmin.Built b : built) {
            Lineage full = Lineages.replay(Choices.decode(b.entry().choices), OPTIONS, b.entry().cap);
            assertNotNull(b.lineage());
            assertNotNull(full);
            assertEquals(full.source(), b.lineage().source());
        }
        assertEquals(corpus.entries.stream().filter(e -> e.parent != null).count(), built.stream().filter(Cmin.Built::rebuilt).count());
    }

    @Test void cminChooseKeepsTheSmallestEntryPerProbeWhateverTheOrder() {
        java.math.BigInteger b1111 = java.math.BigInteger.valueOf(0b1111), b0011 = java.math.BigInteger.valueOf(0b0011),
                b0110 = java.math.BigInteger.valueOf(0b0110), one = java.math.BigInteger.ONE;
        List<Cmin.Sized> sized = List.of(new Cmin.Sized(30, 3, Map.of("A", b1111)), new Cmin.Sized(10, 1, Map.of("A", b0011)),
                new Cmin.Sized(20, 2, Map.of("A", b0110)), new Cmin.Sized(10, 4, Map.of("A", b0011)));
        assertEquals(Map.of(1, 2, 2, 1, 3, 1), Cmin.choose(sized));
        List<Cmin.Sized> rev = new ArrayList<>(sized);
        java.util.Collections.reverse(rev);
        assertEquals(Set.of(4, 2, 3), Cmin.choose(rev).keySet());
        assertEquals(Map.of(9, 1, 8, 1), Cmin.choose(List.of(new Cmin.Sized(5, 9, Map.of("A", one)), new Cmin.Sized(5, 8, Map.of("B", one)))));
        assertEquals(Map.of(), Cmin.choose(List.of()));
    }

    @Test void loopGrowsTheCorpusAndFindsAndShrinksThePlantedBug(@TempDir Path tmp) throws Exception {
        Map<String, Object> summary = Fuzz.fuzz(config(tmp, 80, 1, new FakeRunner(), 40));
        assertEquals(80, summary.get("runs"));
        Corpus corpus = new Corpus(tmp.resolve("corpus"));
        assertFalse(corpus.entries.isEmpty(), "nothing reached new probes");
        @SuppressWarnings("unchecked") Map<String, Integer> stats = (Map<String, Integer>) summary.get("stats");
        assertEquals(corpus.entries.size(), stats.get("corpus-added"));
        assertTrue(stats.getOrDefault("extended", 0) > 0);
        assertEquals(0, stats.get("lineage-cache-misses"));          // every entry was put on add
        assertTrue(stats.get("lineage-cache-hits") >= stats.get("extended"));
        assertTrue(corpus.entries.stream().allMatch(e -> e.cap != null && e.cap >= STEPS));
        assertEquals(20, summary.get("max_depth"));
        @SuppressWarnings("unchecked") Map<String, Map<String, Integer>> m = (Map<String, Map<String, Integer>>) summary.get("mutators");
        assertFalse(m.isEmpty());
        int attempted = m.values().stream().mapToInt(v -> v.get("attempted")).sum();
        assertEquals((int) stats.get("steps-attempted"), attempted);
        assertTrue(attempted > 0);
        assertEquals((int) stats.get("steps-rejected"), m.values().stream().mapToInt(v -> v.get("rejected")).sum());
        assertTrue(m.values().stream().allMatch(v -> v.get("rejected") <= v.get("attempted")));
        assertTrue(m.values().stream().mapToInt(v -> v.get("corpus")).sum() <= corpus.entries.stream().mapToInt(e -> e.steps.size()).sum());
        Lineage first = corpus.replay(0, OPTIONS, STEPS);
        assertNotNull(first);
        assertEquals(corpus.entries.get(0).template, first.template());
        assertTrue(Files.exists(tmp.resolve("corpus/last-run.json")));
        assertTrue(stats.getOrDefault("new-findings", 0) >= 1, summary.toString());
        List<Path> reproducers;
        try (var s = Files.list(tmp.resolve("findings"))) {
            reproducers = s.filter(p -> p.getFileName().toString().matches("hard-.*\\.js")).sorted().toList();
        }
        assertFalse(reproducers.isEmpty());
        String text = Files.readString(reproducers.get(0));
        assertTrue(text.contains("oxpecker-expect: hard") && text.contains("="));
        List<String> log = Files.readAllLines(tmp.resolve("findings/hard.jsonl")).stream().filter(l -> !l.isBlank()).toList();
        assertFalse(log.isEmpty());
        assertTrue(((String) Json.parseObject(log.get(0)).get("signature")).startsWith("hard:"));
    }

    @Test void shrinkerReturnsASmallerLineageThatStillFails() {
        FakeRunner runner = new FakeRunner();
        Lineages.Drawn found = null;
        Random rng = new Random(5);
        for (int k = 0; k < 300 && found == null; k++) {
            Lineages.Drawn d = Lineages.drawLineage(OPTIONS, STEPS, rng);
            if (d != null && runner.run(d.lineage()).verdict().kind.equals(Compare.HARD)) found = d;
        }
        assertNotNull(found, "the planted bug never showed in 300 draws");
        Shrinker shrinker = new Shrinker(runner, OPTIONS, STEPS, (l, o) -> o.verdict().kind.equals(Compare.HARD), 60);
        Shrinker.Result best = shrinker.shrink(found.choices());
        assertNotNull(best);
        assertEquals(Compare.HARD, best.observation().verdict().kind);
        assertTrue(shrinker.calls > 1, "the shrinker did not shrink");
        assertTrue(best.lineage().code().size() <= found.lineage().code().size());
        Lineage again = Lineages.replay(best.choices(), OPTIONS, STEPS);
        assertNotNull(again);
        assertEquals(Compare.HARD, runner.run(again).verdict().kind);
    }

    @Test void compactionKeepsTheProbesAndDoesNotGrowTheEntry() {
        FakeRunner runner = new FakeRunner();
        Lineages.Drawn drawn = Lineages.drawLineage(OPTIONS, STEPS, 11);
        assertNotNull(drawn);
        Observation obs = runner.run(drawn.lineage());
        assertTrue(obs.newProbes() > 0);
        Fuzz.Compacted r = Fuzz.compact(runner, OPTIONS, STEPS, drawn.choices(), obs.newProbeIds(), 40);
        assertNotNull(r);
        assertFalse(r.probes().isEmpty());
        assertTrue(r.lineage().code().size() <= drawn.lineage().code().size());
        assertEquals(r.probes(), runner.run(r.lineage(), r.probes()).requiredReached());
    }

    @Test @Tag("slow")
    void loopRunsAgainstRhino(@TempDir Path tmp) {
        Ensemble client = Ensemble.fromModes(true, true);
        client.start();
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.TRUE.equals(client.info().get("coverage")),
                    "coverage agent not attached");
            Fuzz.Config c = config(tmp, 12, 2, null, 20);
            c.steps = 3;
            c.client = client;
            Map<String, Object> summary = Fuzz.fuzz(c);
            assertEquals(12, summary.get("runs"));
            assertTrue(Files.exists(tmp.resolve("corpus/coverage.exec")));
        } finally {
            client.stop();
        }
    }
}
