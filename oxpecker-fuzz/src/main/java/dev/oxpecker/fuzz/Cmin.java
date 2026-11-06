package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.harness.HarnessClient;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.util.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Corpus minimisation, as afl-cmin does it (cmin.py): replay every entry of a corpus once against
 * the current Rhino and harness, and keep, for every coverage probe any entry reaches, the smallest
 * entry that reaches it. The union of those entries covers everything the whole corpus covers.
 *
 * <p>Each run reports every probe it hit as one bitmap per class ({@code coverage_hits}), and the
 * choice is made here: entries in order of size (program text length), each kept if it hits a
 * probe no smaller entry hit; ties go to the earlier entry. Entries that time out are dropped. The
 * kept entries are then run once more in a fresh JVM to write the new corpus's coverage.exec.
 */
public final class Cmin {
    private Cmin() {}

    /** One entry's size, id and hits (class name -> probe bitmap, bit i = probe i). */
    public record Sized(int size, int id, Map<String, BigInteger> hits) {}

    /** Every probe a run hit in any mode. */
    public static Map<String, BigInteger> runHits(RunResult run) {
        Map<String, BigInteger> hits = new LinkedHashMap<>();
        for (Outcome o : run.results().values()) {
            if (o.hitProbes == null) continue;
            for (var e : o.hitProbes.entrySet()) {
                byte[] le = Base64.getDecoder().decode(e.getValue());
                byte[] be = new byte[le.length + 1];
                for (int i = 0; i < le.length; i++) be[be.length - 1 - i] = le[i];
                hits.merge(e.getKey(), new BigInteger(be), BigInteger::or);
            }
        }
        return hits;
    }

    /**
     * afl-cmin's choice over (size, entry id, hits): the entries that are the smallest to hit at
     * least one probe, with how many probes each is the smallest for. Ties go to the earlier entry.
     */
    public static Map<Integer, Integer> choose(List<Sized> sized) {
        Map<String, BigInteger> covered = new HashMap<>();
        Map<Integer, Integer> wins = new LinkedHashMap<>();
        List<Sized> order = new ArrayList<>(sized);
        order.sort(Comparator.comparingInt(Sized::size));          // stable: ties keep their order
        for (Sized s : order) {
            int fresh = 0;
            for (var e : s.hits.entrySet()) {
                BigInteger have = covered.getOrDefault(e.getKey(), BigInteger.ZERO);
                BigInteger f = e.getValue().andNot(have);
                if (f.signum() != 0) {
                    fresh += f.bitCount();
                    covered.put(e.getKey(), have.or(f));
                }
            }
            if (fresh > 0) wins.put(s.id, fresh);
        }
        return wins;
    }

    /** An entry's lineage as {@link #lineagesDepthFirst} built it. */
    public record Built(Entry entry, Lineage lineage, double ms, boolean rebuilt) {}

    /**
     * Every entry's lineage, visiting the lineage tree depth first: a child is built from its
     * parent's lineage by replaying only its own round ({@link Lineages#continueLineage}), as the
     * fuzz loop does on a cache miss. Depth first holds only the ancestors of the entry being built.
     */
    public static List<Built> lineagesDepthFirst(Corpus src, GenOptions options, int steps) {
        Map<Integer, List<Entry>> children = new HashMap<>();
        List<Entry> roots = new ArrayList<>();
        for (Entry e : src.entries) {
            if (e.parent != null && e.parent != e.id && src.byId.containsKey(e.parent))
                children.computeIfAbsent(e.parent, k -> new ArrayList<>()).add(e);
            else
                roots.add(e);
        }
        record Item(Entry e, Lineage parentLineage, Entry parentEntry) {}
        Deque<Item> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(new Item(roots.get(i), null, null));
        // ArrayDeque.push adds at the head: pushing in reverse leaves the first root on top
        List<Built> out = new ArrayList<>();
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            long t = System.nanoTime();
            Lineage lineage = null;
            boolean rebuilt = false;
            Choices choices = Choices.decode(it.e.choices);
            if (choices != null) {
                int cap = it.e.cap != null ? it.e.cap : steps;
                if (it.parentLineage != null) {
                    Choices pc = Choices.decode(it.parentEntry.choices);
                    if (pc != null) {
                        lineage = Lineages.continueLineage(it.parentLineage, pc, choices, options, cap);
                        rebuilt = lineage != null;
                    }
                }
                if (lineage == null) lineage = Lineages.replay(choices, options, cap);
            }
            out.add(new Built(it.e, lineage, (System.nanoTime() - t) / 1e6, rebuilt));
            List<Entry> kids = children.getOrDefault(it.e.id, List.of());
            for (int k = kids.size() - 1; k >= 0; k--)
                stack.push(new Item(kids.get(k), lineage, lineage != null ? it.e : null));
        }
        return out;
    }

    static double round1(double x) { return Math.round(x * 10) / 10.0; }

    /**
     * Writes the minimised corpus (corpus.jsonl, coverage.exec, cmin.json) to {@code destDir},
     * which must not hold a corpus yet. {@code client} is a coverage-enabled harness client.
     */
    public static Map<String, Object> minimise(Path sourceDir, Path destDir, HarnessClient client, GenOptions options, int steps,
                                               int languageVersion, boolean quiet, PrintStream err) {
        Corpus src = new Corpus(sourceDir);
        if (Files.exists(destDir.resolve("corpus.jsonl")))
            throw new UncheckedIOException(new FileAlreadyExistsException(destDir + " already holds a corpus"));
        long t0 = System.nanoTime();
        List<Integer> undecodable = new ArrayList<>(), restarted = new ArrayList<>(), timedOut = new ArrayList<>();
        Map<Integer, Double> replayMs = new LinkedHashMap<>();
        Map<Integer, String[]> sources = new HashMap<>();
        List<Sized> sized = new ArrayList<>();
        int fromParent = 0;
        List<Built> built = lineagesDepthFirst(src, options, steps);
        for (int i = 0; i < built.size(); i++) {
            Built b = built.get(i);
            replayMs.put(b.entry.id, b.ms);
            if (b.rebuilt) fromParent++;
            if (b.lineage == null) {
                undecodable.add(b.entry.id);
                continue;
            }
            String source = b.lineage.source();
            sources.put(b.entry.id, new String[]{source, b.lineage.template()});
            RunResult run = client.run(source, RunOptions.DEFAULT.withLanguageVersion(languageVersion).withSeed(1)
                    .withCoverageBucket(b.lineage.template()).withCoverageHits(true));
            if (run.restarted()) restarted.add(b.entry.id);
            if (run.results().values().stream().anyMatch(o -> Runner.SLOW.contains(o.status))) {
                timedOut.add(b.entry.id);
                continue;
            }
            sized.add(new Sized(source.length(), b.entry.id, runHits(run)));
            if (!quiet && (i + 1) % 250 == 0) {
                double rebuilding = replayMs.values().stream().mapToDouble(Double::doubleValue).sum() / 1000;
                err.printf("  %d/%d (%.0fs, rebuilding %.0fs of it)%n", i + 1, src.entries.size(), (System.nanoTime() - t0) / 1e9, rebuilding);
            }
        }
        sized.sort(Comparator.comparingInt(Sized::id));      // ties to the earlier entry, whatever the visiting order
        Map<Integer, Integer> wins = choose(sized);
        int chosenProbes = wins.values().stream().mapToInt(Integer::intValue).sum();
        // coverage.exec of the new corpus: the kept entries alone, in a fresh JVM
        client.restart();
        Corpus dest = new Corpus(destDir);
        for (Entry e : src.entries) {
            if (!wins.containsKey(e.id)) continue;
            dest.add(e);
            String[] st = sources.get(e.id);
            client.run(st[0], RunOptions.DEFAULT.withLanguageVersion(languageVersion).withSeed(1).withCoverageBucket(st[1]));
        }
        int covered = client.coverageSave(dest.exec.toString());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", sourceDir.toString());
        summary.put("entries", src.entries.size());
        summary.put("kept", dest.entries.size());
        summary.put("undecodable", undecodable);
        summary.put("restarted", restarted);
        summary.put("timed_out", timedOut);
        summary.put("probes_chosen", chosenProbes);
        summary.put("covered_probes", covered);
        summary.put("probes_not_reached", chosenProbes - covered);
        summary.put("seconds", round1((System.nanoTime() - t0) / 1e9));
        summary.put("rebuild", rebuildStats(src, replayMs));
        summary.put("rebuilt_from_parent", fromParent);
        Map<String, Object> w = new LinkedHashMap<>();
        wins.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> w.put(String.valueOf(e.getKey()), e.getValue()));
        summary.put("wins", w);
        try {
            Files.writeString(destDir.resolve("cmin.json"), Json.pyDumps(summary, 1, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return summary;
    }

    /** How long rebuilding entries took, overall and by lineage depth (steps). */
    static Map<String, Object> rebuildStats(Corpus src, Map<Integer, Double> replayMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (replayMs.isEmpty()) return out;
        List<Double> ms = new ArrayList<>(replayMs.values());
        ms.sort(null);
        double sum = ms.stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, List<Double>> byDepth = new TreeMap<>();
        for (Entry e : src.entries)
            if (replayMs.containsKey(e.id)) byDepth.computeIfAbsent(e.steps.size(), k -> new ArrayList<>()).add(replayMs.get(e.id));
        out.put("seconds", round1(sum / 1000));
        out.put("mean_ms", round1(sum / ms.size()));
        out.put("median_ms", round1(ms.get(ms.size() / 2)));
        out.put("p90_ms", round1(ms.get((int) (ms.size() * 0.9))));
        out.put("max_ms", round1(ms.get(ms.size() - 1)));
        Map<String, Object> bd = new LinkedHashMap<>();
        for (var e : byDepth.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", e.getValue().size());
            m.put("mean_ms", round1(e.getValue().stream().mapToDouble(Double::doubleValue).sum() / e.getValue().size()));
            bd.put(String.valueOf(e.getKey()), m);
        }
        out.put("by_depth", bd);
        return out;
    }
}
