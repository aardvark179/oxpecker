package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Py;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Ensemble: one harness process per execution mode, run side by side.
 *
 * <p>Each process has a role. The <em>guide</em> (exactly one) is the reference every other
 * outcome is compared against and the only process with the JaCoCo agent: coverage, new probe
 * ids and {@code coverageRequire} come from it alone. <em>Comparators</em> only report outcomes.
 * An <em>oracle</em> (not yet implemented) is a harness for another JavaScript engine that speaks
 * the same protocol; it reports only the JS-observable fields (its {@code capabilities}) and will
 * decide which side of a difference is wrong.
 *
 * <p>{@code run} sends a case to all of them at once and returns their outcomes keyed by harness
 * name. A process that dies is restarted on its own; the others' outcomes for that case are kept.
 * Not thread-safe.
 */
public final class Ensemble implements Harness {
    public static final String GUIDE = "guide";
    public static final String COMPARATOR = "comparator";
    public static final String ORACLE = "oracle";
    public static final List<String> ROLES = List.of(GUIDE, COMPARATOR, ORACLE);
    public static final String DEFAULT_GUIDE = "interpreted";

    private final List<HarnessSpec> specs;
    private final String guide;
    private final boolean coverage;
    private final Map<String, HarnessClient> clients = new LinkedHashMap<>();
    /** Each running process's info, as sent. */
    private final Map<String, Map<String, Object>> infos = new LinkedHashMap<>();
    /** ... and as declared (name, version, modes). */
    private final Map<String, HarnessInfo> declared = new HashMap<>();
    private ExecutorService pool;

    public Ensemble(List<HarnessSpec> specs, boolean coverage, boolean quiet, int recycleEvery, double hardTimeoutS) {
        this.specs = List.copyOf(specs);
        List<HarnessSpec> guides = this.specs.stream().filter(s -> s.role().equals(GUIDE)).toList();
        if (guides.size() != 1) throw new IllegalArgumentException("an ensemble needs exactly one guide, got " + guides.size());
        if (this.specs.stream().map(HarnessSpec::name).distinct().count() != this.specs.size()) {
            throw new IllegalArgumentException("harness names must be unique");
        }
        this.guide = guides.get(0).name();
        this.coverage = coverage;
        for (HarnessSpec s : this.specs) {
            clients.put(s.name(), new HarnessClient(Path.of(s.harnessBin()), s.mode(), quiet,
                    coverage && s.role().equals(GUIDE), recycleEvery, hardTimeoutS));
        }
    }

    public Ensemble(List<HarnessSpec> specs, boolean coverage, boolean quiet) {
        this(specs, coverage, quiet, 5000, 60.0);
    }

    public Ensemble(List<HarnessSpec> specs) {
        this(specs, false, true);
    }

    public static Ensemble fromModes(List<String> modes, String guide, boolean coverage, boolean quiet) {
        return new Ensemble(specsFor(modes, guide, false), coverage, quiet);
    }

    /** interpreted (guide) and compiled on the default harness. */
    public static Ensemble fromModes(boolean coverage, boolean quiet) {
        return fromModes(HarnessClient.DEFAULT_MODES, DEFAULT_GUIDE, coverage, quiet);
    }

    public static Ensemble fromModes() {
        return fromModes(false, true);
    }

    // -- targets ---------------------------------------------------------------------------

    /** A {@code [harness:]mode} split. */
    public record Target(String harness, String mode) {}

    /** {@code [harness:]mode} -> (harness, mode); the harness defaults to DEFAULT_HARNESS. */
    public static Target parseTarget(String target) {
        String[] p = Py.rpartition(target, ":");
        return new Target(Py.or(p[0], Oxpecker.DEFAULT_HARNESS), p[2]);
    }

    /** The name of a harness process: the mode alone on the default harness, else {@code harness:mode}. */
    public static String targetName(String harness, String mode) {
        return harness.equals(Oxpecker.DEFAULT_HARNESS) ? mode : harness + ":" + mode;
    }

    private static String targetName(Target t) {
        return targetName(t.harness(), t.mode());
    }

    /** The guide and a comparator per other target, each a {@code [harness:]mode}. The guide must
     * be one of the targets unless {@code includeGuide}, which adds it. */
    public static List<HarnessSpec> specsFor(List<String> targets, String guide, boolean includeGuide) {
        List<Target> parsed = new ArrayList<>(new LinkedHashSet<>(targets.stream().map(Ensemble::parseTarget).toList()));
        Target g = parseTarget(guide);
        if (!parsed.contains(g)) {
            if (!includeGuide) {
                throw new IllegalArgumentException("guide " + Py.repr(guide) + " is not one of "
                        + Py.repr(parsed.stream().map(Ensemble::targetName).toList()));
            }
            parsed.add(0, g);
        }
        List<Target> ordered = new ArrayList<>(List.of(g));
        for (Target t : parsed) if (!t.equals(g)) ordered.add(t);
        List<HarnessSpec> out = new ArrayList<>();
        for (Target t : ordered) {
            out.add(new HarnessSpec(targetName(t), t.equals(g) ? GUIDE : COMPARATOR, t.mode(), t.harness()));
        }
        return out;
    }

    public static List<HarnessSpec> specsFor(List<String> targets, String guide) {
        return specsFor(targets, guide, false);
    }

    // -- accessors -------------------------------------------------------------------------

    public List<HarnessSpec> specs() { return specs; }
    public String guide() { return guide; }
    public boolean coverage() { return coverage; }
    public Map<String, HarnessClient> clients() { return clients; }
    public Map<String, Map<String, Object>> infos() { return infos; }
    public Map<String, HarnessInfo> declared() { return declared; }

    public List<String> names() {
        return specs.stream().map(HarnessSpec::name).toList();
    }

    public List<String> comparators() {
        return specs.stream().filter(s -> s.role().equals(COMPARATOR)).map(HarnessSpec::name).toList();
    }

    public HarnessClient guideClient() {
        return clients.get(guide);
    }

    public int restarts() {
        return clients.values().stream().mapToInt(HarnessClient::restarts).sum();
    }

    public HarnessSpec spec(String name) {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    // -- process management --------------------------------------------------------------

    /** Asks each launcher which modes it supports, then starts one process per spec, pinned to its
     * mode. A spec whose mode its harness does not declare is a HarnessError. */
    public Ensemble start() {
        Set<String> launchers = new TreeSet<>();
        for (HarnessSpec s : specs) launchers.add(s.harnessBin());
        Map<String, HarnessInfo> statics = new HashMap<>();
        ExecutorService describe = Executors.newFixedThreadPool(launchers.size(), daemon("oxpecker-describe"));
        try {
            List<String> ls = new ArrayList<>(launchers);
            List<HarnessInfo> got = map(describe, ls, l -> HarnessClient.describeHarness(Path.of(l)));
            for (int i = 0; i < ls.size(); i++) statics.put(ls.get(i), got.get(i));
        } finally {
            describe.shutdownNow();
        }
        for (HarnessSpec s : specs) {
            HarnessInfo d = statics.get(s.harnessBin());
            if (!d.modes().contains(s.mode())) {
                throw new HarnessError("harness " + Py.repr(s.name()) + ": " + d.name() + " (" + s.harnessBin()
                        + ") does not support mode " + Py.repr(s.mode()) + "; it declares " + String.join(", ", d.modes()));
            }
        }
        pool = Executors.newFixedThreadPool(clients.size(), daemon("oxpecker-harness"));
        for (HarnessClient c : clients.values()) c.start();
        List<String> names = new ArrayList<>(clients.keySet());
        List<Map<String, Object>> got = map(pool, names, n -> clients.get(n).info());
        for (int i = 0; i < names.size(); i++) {
            infos.put(names.get(i), got.get(i));
            declared.put(names.get(i), HarnessInfo.fromJson(got.get(i)));
        }
        return this;
    }

    @Override
    public void stop() {
        for (HarnessClient c : clients.values()) c.stop();
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
    }

    /** The declared name and version of the engine behind harness {@code name}, e.g. "Rhino 2.0.0". */
    public String engine(String name) {
        HarnessInfo d = declared.get(name);
        return d != null ? d.label() : null;
    }

    /** The guide's info, plus {@code guide} and every harness's info (with its role) under {@code harnesses}. */
    @Override
    public Map<String, Object> info() {
        if (infos.isEmpty()) start();
        Map<String, Object> out = new LinkedHashMap<>(infos.get(guide));
        out.put("guide", guide);
        Map<String, Object> hs = new LinkedHashMap<>();
        for (HarnessSpec s : specs) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("role", s.role());
            h.putAll(infos.get(s.name()));
            hs.put(s.name(), h);
        }
        out.put("harnesses", hs);
        return out;
    }

    /** What identifies this ensemble across runs (stored with a fuzz corpus). */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guide", guide);
        List<Object> hs = new ArrayList<>();
        for (HarnessSpec s : specs) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", s.name());
            h.put("role", s.role());
            h.put("harness", s.harness());
            h.put("mode", s.mode());
            h.put("engine", engine(s.name()));
            hs.add(h);
        }
        out.put("harnesses", hs);
        return out;
    }

    // -- coverage: the guide's alone -------------------------------------------------------

    @Override
    public int coverageSave(String path) {
        return guideClient().coverageSave(path);
    }

    @Override
    public int coverageLoad(String path) {
        return guideClient().coverageLoad(path);
    }

    @Override
    public Map<String, Object> coverageReport() {
        return guideClient().coverageReport();
    }

    // -- requests -------------------------------------------------------------------------

    /**
     * Runs one case in the guide and in {@code options.comparators()} (default: all of them; empty
     * runs the guide alone), or in the comparators whose mode is one of {@code options.modes()}.
     * The coverage arguments (bucket, new ids, require) go to the guide only.
     */
    @Override
    public RunResult run(String source, RunOptions options) {
        List<String> comparators = options.comparators();
        if (options.modes() != null) {
            if (comparators != null) throw new IllegalArgumentException("pass comparators or modes, not both");
            Set<String> modes = new HashSet<>(options.modes());
            comparators = specs.stream().filter(s -> s.role().equals(COMPARATOR) && modes.contains(s.mode()))
                    .map(HarnessSpec::name).toList();
        }
        if (pool == null) start();
        Set<String> wanted = new HashSet<>(comparators == null ? comparators() : comparators);
        Set<String> unknown = new TreeSet<>(Py.ORDER);
        unknown.addAll(wanted);
        unknown.removeAll(comparators());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not comparators of this ensemble: " + Py.repr(new ArrayList<>(unknown)));
        }
        List<String> names = names().stream().filter(n -> n.equals(guide) || wanted.contains(n)).toList();
        String caseId = options.id() != null && !options.id().isEmpty() ? options.id() : HarnessClient.newId();
        RunOptions base = options.withModes((List<String>) null).withComparators((List<String>) null).withId(caseId);
        RunOptions forComparators = base.withoutGuideCoverage();

        List<RunResult> got = map(pool, names, n -> clients.get(n).run(source, n.equals(guide) ? base : forComparators));
        Map<String, Outcome> results = new LinkedHashMap<>();
        boolean restarted = false;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            RunResult res = got.get(i);
            HarnessSpec spec = spec(name);
            if (res.results().size() != 1) {
                throw new IllegalStateException("harness " + name + " returned " + res.results().size() + " outcomes");
            }
            Outcome o = res.results().values().iterator().next();
            HarnessInfo d = declared.get(name);
            o.harness = name;
            o.role = spec.role();
            o.engine = engine(name);
            o.capabilities = d != null ? new ArrayList<>(d.capabilities()) : null;
            results.put(name, o);
            restarted |= res.restarted();
        }
        return new RunResult(caseId, results, restarted, guide);
    }

    /**
     * Why coverage recorded under {@code stored} ({@link #describe}) does not carry over to
     * {@code current}: the guide's mode or engine changed. null if it carries over.
     */
    public static String describeMismatch(Map<String, Object> stored, Map<String, Object> current) {
        Map<String, Object> a = guideOf(stored), b = guideOf(current);
        for (String key : List.of("mode", "engine")) {
            if (!Py.eq(a.get(key), b.get(key))) {
                return "the guide's " + key + " changed: " + Py.str(a.get(key)) + " -> " + Py.str(b.get(key));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> guideOf(Map<String, Object> d) {
        Object hs = d.get("harnesses");
        if (hs instanceof List<?> l) {
            for (Object h : l) {
                if (h instanceof Map<?, ?> m && Objects.equals(m.get("name"), d.get("guide"))) return (Map<String, Object>) m;
            }
        }
        return Map.of();
    }

    // -- helpers ---------------------------------------------------------------------------

    /** ThreadPoolExecutor.map: results in order, the first failure re-raised. */
    private static <T, R> List<R> map(ExecutorService pool, List<T> items, Function<T, R> f) {
        List<Future<R>> fs = new ArrayList<>();
        for (T x : items) fs.add(pool.submit(() -> f.apply(x)));
        List<R> out = new ArrayList<>();
        for (Future<R> fu : fs) {
            try {
                out.add(fu.get());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error err) throw err;
                throw new HarnessError(String.valueOf(e.getCause()), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HarnessError("interrupted", e);
            }
        }
        return out;
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
