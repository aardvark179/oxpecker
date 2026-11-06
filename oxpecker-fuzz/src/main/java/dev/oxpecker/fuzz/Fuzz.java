package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.harness.Harness;
import dev.oxpecker.harness.HarnessClient;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.il.Mutate.Step;
import dev.oxpecker.related.Fingerprint;
import dev.oxpecker.related.KnownIndex;
import dev.oxpecker.related.Related;
import dev.oxpecker.shrink.SourceShrink;
import dev.oxpecker.util.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;

/**
 * The differential-testing loop ({@code oxpecker fuzz}; fuzz.py): proposal §3.6 and §5.3 on top
 * of the generation layer.
 *
 * <pre>
 * corpus   lineages that reached Rhino probes nothing before them had: corpus.jsonl plus the
 *          JaCoCo store coverage.exec, so a later run resumes the frontier; the probes are the
 *          guide harness's only, and harnesses.json records which guide that was
 * loop     with p_new a fresh lineage, else a corpus entry extended by up to `steps` more
 *          mutation steps; the child's program runs in the guide and every comparator
 *          new probes -> (optionally shrunk while it still reaches them) -> corpus
 *          a hard verdict with an unknown signature -> shrunk -> recorded
 * shrink   jetCheck's shrinker started from the recorded choices, with a call budget
 * </pre>
 */
public final class Fuzz {
    private Fuzz() {}

    /** The loop's parameters, with fuzz()'s keyword defaults. */
    public static final class Config {
        public Path corpusDir;
        public GenOptions options = GenOptions.DEFAULT;
        public Integer iterations = null;
        public Double seconds = null;
        public int steps = 5;
        public double pNew = 0.2;
        public Long seed = null;
        public Set<String> known = null;
        public KnownIndex relatedIndex = null;
        public Path findingsDir = Findings.defaultDir();
        public int shrinkBudget = 150;
        public int compactBudget = 0;
        public int sourceShrinkBudget = 300;
        public int languageVersion = 200;
        public Runner runner = null;
        public Ensemble client = null;
        public boolean quiet = false;
        public int maxDepth = Lineages.MAX_DEPTH;
        public boolean hardOnly = false;
        public boolean resetCoverage = false;
        public PrintStream err = System.err;
    }

    // --------------------------------------------------------------------------- parent choice
    /**
     * AFL's exec-time factor in calculate_score: an entry ten times slower than the corpus mean is
     * picked a tenth as often, one four times faster three times as often. Entries without a
     * recorded time count as average.
     */
    public static double speedWeight(long ms, double meanMs) {
        if (ms <= 0 || meanMs <= 0) return 1.0;
        double r = ms / meanMs;
        double[][] over = {{10, 0.1}, {4, 0.25}, {2, 0.5}, {1.33, 0.75}};
        for (double[] o : over) if (r > o[0]) return o[1];
        double[][] under = {{0.25, 3.0}, {0.33, 2.0}, {0.5, 1.5}};
        for (double[] u : under) if (r < u[0]) return u[1];
        return 1.0;
    }

    /**
     * How likely each entry is to be extended: its speed factor, halved for every child of it that
     * timed out this run (to 1/64).
     */
    public static List<Double> parentWeights(List<Entry> entries, Map<Integer, Integer> childTimeouts) {
        double sum = 0;
        int n = 0;
        for (Entry e : entries) if (e.ms > 0) { sum += e.ms; n++; }
        double mean = n > 0 ? sum / n : 0.0;
        List<Double> out = new ArrayList<>();
        for (Entry e : entries)
            out.add(speedWeight(e.ms, mean) * Math.pow(0.5, Math.min(childTimeouts.getOrDefault(e.id, 0), 6)));
        return out;
    }

    /** Python's {@code random.choices(population, weights)[0]}: one uniform draw against the cumulative weights. */
    static <T> T weightedChoice(Random rng, List<T> xs, List<Double> weights) {
        double total = 0;
        for (double w : weights) total += w;
        double x = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < xs.size(); i++) {
            acc += weights.get(i);
            if (x < acc) return xs.get(i);
        }
        return xs.get(xs.size() - 1);
    }

    // --------------------------------------------------------------------------- the loop
    private static void bump(Map<String, Integer> m, String k, int by) { m.merge(k, by, Integer::sum); }

    /**
     * Runs the loop. {@code runner} (or an Ensemble in {@code client}) runs programs; without either
     * a coverage-enabled ensemble with the default guide is started. Resuming a corpus whose
     * coverage came from another guide is refused unless {@code resetCoverage}. Returns the summary
     * also written to {@code last-run.json}.
     */
    public static Map<String, Object> fuzz(Config c) {
        Random rng = c.seed != null ? new Random(c.seed) : new Random();
        Corpus corpus = new Corpus(c.corpusDir);
        Map<String, Integer> stats = new TreeMap<>();
        Map<String, Integer> sigCounts = new LinkedHashMap<>();
        Set<String> known = new HashSet<>(c.known != null ? c.known : Set.of());
        long t0 = System.nanoTime();
        double lastReport = 0;
        Ensemble ownClient = null;
        Ensemble client = c.client;
        Runner runner = c.runner;
        if (runner == null) {
            if (client == null) {
                ownClient = client = Ensemble.fromModes(true, c.quiet);
                client.start();
                if (!Boolean.TRUE.equals(client.info().get("coverage"))) {
                    client.stop();
                    throw new IllegalStateException("coverage agent not attached; the harness build must include jacocoagent.jar");
                }
            }
            runner = Runner.harness(client, c.languageVersion);
            try {
                corpus.checkGuide(client.describe(), c.resetCoverage);
            } catch (RuntimeException e) {
                if (ownClient != null) ownClient.stop();
                throw e;
            }
        }
        if (client != null) {
            // the checkpoint a restarted guide JVM resumes from (HarnessClient.coverage_checkpoint)
            HarnessClient guide = client.guideClient();
            if (guide != null) guide.setCoverageCheckpoint(corpus.exec.toString());
            if (Files.exists(corpus.exec)) {
                int covered = client.coverageLoad(corpus.exec.toString());
                if (!c.quiet) c.err.println("resumed: " + corpus.entries.size() + " corpus entries, " + covered + " probes already covered");
            }
        }

        LineageCache lineages = new LineageCache();
        Map<Integer, Integer> childTimeouts = new HashMap<>();
        List<Double> weights = null;
        Map<String, Integer> attempted = new HashMap<>(), rejected = new HashMap<>(), productive = new HashMap<>();
        int i = 0;
        Map<String, Object> report = new HashMap<>();
        try {
            while (true) {
                if (c.iterations != null && i >= c.iterations) break;
                double elapsed = (System.nanoTime() - t0) / 1e9;
                if (c.seconds != null && elapsed >= c.seconds) break;
                i++;
                Entry parent = null;
                Lineage parentLineage = null;
                Lineages.Drawn drawn = null;
                if (!corpus.entries.isEmpty() && rng.nextDouble() > c.pNew) {
                    if (weights == null) weights = parentWeights(corpus.entries, childTimeouts);
                    parent = weightedChoice(rng, corpus.entries, weights);
                    Choices parentChoices = Choices.decode(parent.choices);
                    parentLineage = parentChoices != null ? lineages.get(parent, c.options, c.steps, corpus.byId) : null;
                    if (parentLineage != null && parentLineage.depth() >= c.maxDepth) {
                        bump(stats, "at-max-depth", 1);
                    } else {
                        drawn = parentLineage != null
                                ? Lineages.extend(parentChoices, c.options, c.steps, rng, parentLineage, null, c.maxDepth) : null;
                        bump(stats, drawn != null ? "extended" : "extend-failed", 1);
                    }
                }
                if (drawn == null) {
                    parent = null;
                    drawn = Lineages.drawLineage(c.options, c.steps, rng);
                    bump(stats, "fresh", 1);
                }
                if (drawn == null) {
                    bump(stats, "rejected", 1);
                    continue;
                }
                Lineage lineage = drawn.lineage();
                Choices choices = drawn.choices();
                Lineage baseLineage = parent != null ? parentLineage : null;
                for (var e : lineage.attemptedBy.entrySet())
                    bump(attempted, e.getKey(), e.getValue() - (baseLineage != null ? baseLineage.attemptedBy.getOrDefault(e.getKey(), 0) : 0));
                for (var e : lineage.rejectedBy.entrySet())
                    bump(rejected, e.getKey(), e.getValue() - (baseLineage != null ? baseLineage.rejectedBy.getOrDefault(e.getKey(), 0) : 0));
                Observation obs = runner.run(lineage);
                Verdict v = obs.verdict();
                bump(stats, v.kind, 1);
                if (obs.timedOut() && parent != null) {
                    childTimeouts.merge(parent.id, 1, Integer::sum);
                    weights = null;
                }
                if (v.kind.equals(Compare.SOFT) || v.kind.equals(Compare.JAVA_TYPES)) {
                    if (!c.hardOnly) Findings.record(Findings.Recordable.of(lineage), obs.run(), v, c.findingsDir);
                } else if (v.kind.equals(Compare.HARD)) {
                    String sig = v.signature();
                    sigCounts.merge(sig, 1, Integer::sum);
                    if (known.contains(sig)) {
                        Findings.record(Findings.Recordable.of(lineage), obs.run(), v, c.findingsDir);
                    } else {
                        known.add(sig);
                        bump(stats, "new-findings", 1);
                        // Shrink towards this finding's coarse signature, not "any unknown hard
                        // verdict": the signature was just added to `known`, so the loop's own
                        // predicate would reject every candidate, the replay of the finding included.
                        String coarse = v.coarseSignature();
                        BiPredicate<Lineage, Observation> sameBug = (l, o) ->
                                o.verdict().kind.equals(Compare.HARD) && o.verdict().coarseSignature().equals(coarse);
                        Shrinker shrinker = new Shrinker(runner, c.options, c.steps, sameBug, c.shrinkBudget);
                        Shrinker.Result small = shrinker.shrink(choices, lineage.cap);
                        if (small == null) small = new Shrinker.Result(lineage, choices, obs);
                        Findings.Recordable recordAs = Findings.Recordable.of(small.lineage());
                        RunResult recRun = small.observation().run();
                        Verdict recVerdict = small.observation().verdict();
                        if (client != null && c.sourceShrinkBudget > 0) {
                            // then statement-level deletion on the lifted source, which gets much further
                            // than choice shrinking within a budget (proposal §3.7)
                            Shrunk shrunk = shrinkLifted(client, small.lineage().source(), recVerdict.coarseSignature(),
                                    c.languageVersion, c.sourceShrinkBudget, recVerdict.against);
                            if (shrunk != null) {
                                recordAs = shrunk.reproducer;
                                recRun = shrunk.run;
                                recVerdict = shrunk.verdict;
                            }
                        }
                        List<Related> related = List.of();
                        if (c.relatedIndex != null)
                            related = c.relatedIndex.related(Fingerprint.of("finding", recordAs.source(), recRun, recVerdict));
                        Path path = Findings.record(recordAs, recRun, recVerdict, c.findingsDir, true, null, "oxpecker diff", related);
                        if (parent != null) parent.found.add(sig);
                        if (!c.quiet) {
                            String pretty = recVerdict.pretty();
                            c.err.println("[finding] " + pretty.substring(0, Math.min(160, pretty.length())) + "\n          -> " + path
                                    + " (" + lines(recordAs.source()) + " lines; lineage shrink " + lineage.code().size() + " -> "
                                    + small.lineage().code().size() + " instructions, " + lineage.steps.size() + " -> "
                                    + small.lineage().steps.size() + " steps in " + shrinker.calls + " runs; source shrink "
                                    + lines(small.lineage().source()) + " -> " + lines(recordAs.source()) + " lines)");
                            for (Related r : related) c.err.println("          related: " + r);
                        }
                    }
                }
                if (obs.newProbes() > 0 && obs.timedOut()) {
                    // a hang is not a parent (AFL keeps them out of its queue): its children inherit the
                    // runaway, and one past the watchdog costs the wall limit plus a JVM restart
                    bump(stats, "timed-out-not-added", 1);
                } else if (obs.newProbes() > 0) {
                    Choices eChoices = choices;
                    Lineage eLineage = lineage;
                    Map<String, List<Integer>> probes = obs.newProbeIds();
                    if (c.compactBudget > 0 && !obs.newProbeIds().isEmpty()) {
                        Compacted compacted = compact(runner, c.options, lineage.cap, choices, obs.newProbeIds(), c.compactBudget);
                        if (compacted != null) {
                            eChoices = compacted.choices;
                            eLineage = compacted.lineage;
                            probes = compacted.probes;
                            bump(stats, "compacted", 1);
                        }
                    }
                    Entry entry = new Entry(corpus.nextId(), eChoices.encode(), eLineage.template(), eLineage.stepNames(),
                            obs.newProbes(), probes, parent != null ? parent.id : null, eLineage.code().size(), eLineage.cap, obs.ms());
                    corpus.add(entry);
                    weights = null;
                    lineages.put(entry.id, eLineage);
                    List<Step> st = eLineage.steps;
                    for (Step s : st.subList(baseLineage != null ? Math.min(baseLineage.steps.size(), st.size()) : 0, st.size()))
                        bump(productive, s.name(), 1);
                    bump(stats, "corpus-added", 1);
                    bump(stats, "new-probes", obs.newProbes());
                    // the checkpoint a restarted JVM resumes from: only runs with new probes move the frontier
                    if (client != null) client.coverageSave(corpus.exec.toString());
                }
                double now = (System.nanoTime() - t0) / 1e9;
                if (!c.quiet && now - lastReport > 10) {
                    lastReport = now;
                    StringBuilder sb = new StringBuilder();
                    for (String k : List.of("equal", "hard", "java-types", "soft", "rejected"))
                        if (stats.containsKey(k)) sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(stats.get(k));
                    c.err.printf("%7d runs %5.1f/s  corpus %d  new probes %d  findings %d  %s%n", i, i / now, corpus.entries.size(),
                            stats.getOrDefault("new-probes", 0), stats.getOrDefault("new-findings", 0), sb);
                }
            }
        } finally {
            if (client != null) {
                client.coverageSave(corpus.exec.toString());
                try {
                    report = client.coverageReport();
                } catch (RuntimeException e) {
                    report = new HashMap<>();
                }
            }
            if (ownClient != null) ownClient.stop();
        }
        stats.put("lineage-cache-hits", lineages.hits);
        stats.put("lineage-cache-misses", lineages.misses);
        stats.put("lineage-rebuilt-from-parent", lineages.incremental);
        stats.put("steps-attempted", attempted.values().stream().mapToInt(Integer::intValue).sum());
        stats.put("steps-rejected", rejected.values().stream().mapToInt(Integer::intValue).sum());
        Map<String, Object> mutatorStats = new LinkedHashMap<>();
        Set<String> names = new TreeSet<>(attempted.keySet());
        names.addAll(productive.keySet());
        for (String n : names) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempted", attempted.getOrDefault(n, 0));
            m.put("rejected", rejected.getOrDefault(n, 0));
            m.put("corpus", productive.getOrDefault(n, 0));
            mutatorStats.put(n, m);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runs", i);
        summary.put("seconds", (System.nanoTime() - t0) / 1e9);
        summary.put("corpus", corpus.entries.size());
        summary.put("stats", stats);
        summary.put("signatures", sigCounts);
        summary.put("coveredProbes", report.get("coveredProbes"));
        summary.put("totalProbes", report.get("totalProbes"));
        summary.put("steps", c.steps);
        summary.put("max_depth", c.maxDepth);
        summary.put("p_new", c.pNew);
        summary.put("max_entry_depth", corpus.entries.stream().mapToInt(e -> e.steps.size()).max().orElse(0));
        summary.put("mutators", mutatorStats);
        try {
            Files.writeString(corpus.dir.resolve("last-run.json"), Json.pyDumps(summary, 1, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return summary;
    }

    private static int lines(String s) { return s.split("\n", -1).length - (s.endsWith("\n") ? 1 : 0); }

    // --------------------------------------------------------------------------- source shrinking
    /** A source-shrunk finding: what {@link Findings#record} needs. */
    public record Reproducer(String source, String template) implements Findings.Recordable {
        /** The source without its probe lines (Python: {@code "\n".join(splitlines, filtered) + "\n"}). */
        public String plainSource() {
            return source.lines().filter(ln -> !ln.stripLeading().startsWith("__probe(")).collect(java.util.stream.Collectors.joining("\n")) + "\n";
        }
    }

    public record Shrunk(Reproducer reproducer, RunResult run, Verdict verdict) {}

    /**
     * Statement-level delta debugging ({@link SourceShrink#shrinkSource}) while the coarse signature
     * holds; null if nothing could go. With {@code against} (a comparator of the Ensemble), only the
     * guide and that comparator run.
     */
    public static Shrunk shrinkLifted(Harness client, String source, String coarse, int languageVersion, int budget, String against) {
        RunOptions o = RunOptions.DEFAULT.withLanguageVersion(languageVersion).withSeed(1);
        if (against != null) o = o.withComparators(List.of(against));
        RunOptions opts = o;
        String small = SourceShrink.shrinkSource(source,
                src -> Compare.compareRun(client.run(src, opts)).coarseSignature().equals(coarse), budget);
        if (small.equals(source)) return null;
        RunResult run = client.run(small, opts);
        Verdict v = Compare.compareRun(run);
        if (!v.coarseSignature().equals(coarse)) return null;
        return new Shrunk(new Reproducer(small, "shrunk"), run, v);
    }

    // --------------------------------------------------------------------------- compaction
    public record Compacted(Choices choices, Lineage lineage, Map<String, List<Integer>> probes) {}

    /**
     * Shrink on insert (proposal §5.3): re-runs the entry asking for its new probes and keeps the
     * subset it reaches again (once-per-JVM probes drop out, D36), then shrinks the lineage while it
     * still reaches that subset. {@code steps} is the lineage's cap (G49).
     */
    public static Compacted compact(Runner runner, GenOptions options, int steps, Choices choices,
                                    Map<String, List<Integer>> newIds, int budget) {
        Lineage lineage = Lineages.replay(choices, options, steps);
        if (lineage == null) return null;
        Map<String, List<Integer>> again = runner.run(lineage, newIds).requiredReached();
        if (ProbeSets.count(again) == 0) return null;
        Runner requiring = (l, require) -> runner.run(l, again);
        Shrinker.Result best = new Shrinker(requiring, options, steps,
                (l, o) -> ProbeSets.covers(o.requiredReached(), again), budget).shrink(choices);
        if (best == null) return null;
        return new Compacted(best.choices(), best.lineage(), again);
    }
}
