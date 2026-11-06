package dev.oxpecker.inspector;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.cli.Fmt;
import dev.oxpecker.cli.Main;
import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.harness.Harness;
import dev.oxpecker.harness.HarnessClient;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Instruction;
import dev.oxpecker.il.LiftOptions;
import dev.oxpecker.il.Lifter;
import dev.oxpecker.il.Ops;
import dev.oxpecker.il.Strategies;
import dev.oxpecker.il.Type;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Inspector export (oxpecker/inspector.py): samples every generator and template, runs the samples
 * through the harness and writes a run directory the UI ({@code ui/}) reads:
 *
 * <pre>
 *     ui/data/&lt;run-id&gt;/catalogue.json   generators and templates from the registry
 *                      samples.jsonl    one program per line with per-mode outcomes and verdict
 *                      metrics.json     per generator/template quality aggregates
 *                      findings.json    hard/java-types/soft findings, deduplicated by signature
 *                      coverage.json    with --coverage: per-focus Rhino coverage
 * </pre>
 *
 * A generator sample is {@code runGenerator(name, seed)}: a small prefix of values, then the
 * generator once, lifted with probes; a template sample is {@code generate(seed, template=name)}.
 * The schema is {@code ui/lib/types.ts} (the UI tolerates missing fields).
 *
 * <pre>
 *     oxpecker export -o ui/data --per-generator 20     also prints the quality table
 *     oxpecker metrics ui/data/&lt;run-id&gt;
 * </pre>
 */
public final class Inspector {
    private Inspector() {}

    /** Fewer probes than this before a throw counts as an early throw. */
    public static final int STATUS_THROWN_EARLY = 2;

    public static String runId() {
        String commit;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            commit = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
        } catch (IOException e) {
            commit = "nogit";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            commit = "nogit";
        }
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm")) + "-" + (commit.isEmpty() ? "nogit" : commit);
    }

    // ------------------------------------------------------------------------------- catalogue

    /** Where generators and templates are registered: generators/*.java and Templates.java. */
    private static final String IL_SOURCES = "oxpecker-core/src/main/java/dev/oxpecker/il";

    private static Map<String, String[]> sourceIndex;

    /**
     * (path:line, source text) of each registration, best effort: the statement from
     * {@code generator("name")} or {@code template("name", ...)} to its closing semicolon. Python
     * read the function behind the strategy; a Java generator is a lambda in that statement.
     */
    static synchronized Map<String, String[]> sourceIndex() {
        if (sourceIndex != null) return sourceIndex;
        Map<String, String[]> out = new HashMap<>();
        try {
            Path root = Oxpecker.root();
            Path il = root.resolve(IL_SOURCES);
            List<Path> files = new ArrayList<>();
            Path gens = il.resolve("generators");
            if (Files.isDirectory(gens)) {
                try (Stream<Path> s = Files.list(gens)) {
                    s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                }
            }
            if (Files.exists(il.resolve("Templates.java"))) files.add(il.resolve("Templates.java"));
            Pattern reg = Pattern.compile("\\b(generator|template)\\(\"([^\"]+)\"");
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                String rel = root.relativize(f).toString().replace('\\', '/');
                Matcher m = reg.matcher(text);
                while (m.find()) {
                    String key = (m.group(1).equals("generator") ? "g:" : "t:") + m.group(2);
                    if (out.containsKey(key)) continue;
                    int lineStart = text.lastIndexOf('\n', m.start()) + 1;
                    int end = statementEnd(text, m.start());
                    int lineEnd = text.indexOf('\n', end);
                    lineEnd = lineEnd < 0 ? text.length() : lineEnd + 1;
                    int line = 1;
                    for (int i = 0; i < lineStart; i++) if (text.charAt(i) == '\n') line++;
                    out.put(key, new String[] {rel + ":" + line, text.substring(lineStart, lineEnd)});
                }
            }
        } catch (IOException | RuntimeException e) {
            // no sources (a jar outside the repository): paths and snippets stay empty
        }
        sourceIndex = out;
        return out;
    }

    /** The index of the semicolon ending the statement starting at {@code from}: the first one
     * outside brackets, string, char and text-block literals, and comments. */
    static int statementEnd(String s, int from) {
        int depth = 0;
        int i = from, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int e = s.indexOf("\"\"\"", i + 3);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) j += s.charAt(j) == '\\' ? 2 : 1;
                i = j + 1;
                continue;
            }
            if (s.startsWith("//", i)) {
                int e = s.indexOf('\n', i);
                i = e < 0 ? n : e;
                continue;
            }
            if (s.startsWith("/*", i)) {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ';' && depth <= 0) return i;
            i++;
        }
        return n - 1;
    }

    private static String[] sourceOf(String key) {
        return sourceIndex().getOrDefault(key, new String[] {"", ""});
    }

    public static List<Map<String, Object>> catalogue() {
        Strategies.registered();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Strategies.GeneratorSpec spec : Strategies.REGISTRY.values()) {
            String[] src = sourceOf("g:" + spec.name());
            List<String> inputTypes = new ArrayList<>();
            for (Type t : spec.inputs()) inputTypes.add(t.toString());
            for (Type t : spec.required()) inputTypes.add("required " + t);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", spec.name());
            e.put("kind", "generator");
            e.put("family", spec.family());
            e.put("weight", spec.weight());
            e.put("toggle", spec.toggle());
            e.put("sloppyOnly", spec.sloppyOnly());
            e.put("inputTypes", inputTypes);
            e.put("outputType", spec.produces() != null ? spec.produces().toString() : "");
            e.put("description", spec.doc());
            e.put("context", Main.contextName(spec.context()));
            e.put("block", spec.isBlock());
            e.put("sourcePath", src[0]);
            e.put("sourceSnippet", src[1]);
            e.put("fuzzilli", spec.source());
            out.add(e);
        }
        for (Strategies.TemplateSpec t : Strategies.TEMPLATES.values()) {
            String[] src = sourceOf("t:" + t.name());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", t.name());
            e.put("kind", "template");
            e.put("family", "templates");
            e.put("weight", t.weight());
            e.put("toggle", null);
            e.put("sloppyOnly", false);
            e.put("inputTypes", List.of());
            e.put("outputType", "");
            e.put("description", t.doc());
            e.put("sourcePath", src[0]);
            e.put("sourceSnippet", src[1]);
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------------------- samples

    /** Inclusive 0-based line spans per generator, from the lifter's {@code // [name]} comments: a
     * span runs from its comment to the line before the next comment or the end. */
    public static List<Map<String, Object>> generatorTrace(String source) {
        List<String> lines = Py.splitlines(source);
        List<int[]> marks = new ArrayList<>();
        List<String> gens = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String ln = Py.strip(lines.get(i));
            if (ln.startsWith("// [") && ln.endsWith("]")) {
                marks.add(new int[] {i});
                gens.add(Py.slice(ln, 4, Py.len(ln) - 1));
            }
        }
        int total = lines.size();
        List<Map<String, Object>> spans = new ArrayList<>();
        for (int k = 0; k < marks.size(); k++) {
            int line = marks.get(k)[0];
            int end = k + 1 < marks.size() ? marks.get(k + 1)[0] - 1 : total - 1;
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("gen", gens.get(k));
            span.put("lines", List.of(line, Math.max(line, end)));
            spans.add(span);
        }
        return spans;
    }

    public static Map<String, Object> statsOf(List<Instruction> code, String source) {
        int depth = 0, maxDepth = 0, top = 0, statements = 0, probes = 0;
        Set<Integer> variables = new HashSet<>();
        for (Instruction instr : code) {
            var op = instr.op();
            boolean probe = op instanceof Ops.Probe;
            if (op.isBlockEnd() && !op.isBlockStart()) depth--;
            if (depth == 0 && !probe && !(op.isBlockEnd() && !op.isBlockStart())) top++;
            if (op.isBlockStart() && !op.isBlockEnd()) {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            }
            if (probe) probes++;
            else statements++;
            for (int v : instr.inouts()) variables.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statements", statements);
        out.put("topLevelStatements", top);
        out.put("vars", variables.size());
        out.put("maxDepth", maxDepth);
        out.put("probes", probes);
        out.put("bytes", source.getBytes(StandardCharsets.UTF_8).length);
        return out;
    }

    /** One sample: {kind, focus, seed, template, code, source, sourcePlain, enabledGenerators, fired}. */
    public record Sample(String kind, String focus, long seed, String template, List<Instruction> code, String source,
                         String sourcePlain, List<String> enabledGenerators, boolean fired, String error) {}

    private static String[] lifted(List<Instruction> code, GenOptions options) {
        String probed = Lifter.lift(code, new LiftOptions(true, true, options.strict(), options.probeWidth()));
        String plain = Lifter.lift(code, new LiftOptions(false, false, options.strict(), 6));
        return new String[] {probed, plain};
    }

    public static List<Sample> sample(GenOptions options, int perGenerator, int perTemplate, long seed) {
        Strategies.registered();
        List<Sample> out = new ArrayList<>();
        List<String> enabled = new ArrayList<>();
        for (var e : Strategies.REGISTRY.entrySet()) {
            String toggle = e.getValue().toggle();
            if (toggle == null || options.toggles().contains(toggle)) enabled.add(e.getKey());
        }
        enabled = Py.sorted(enabled);
        // the options run_generator and generate were given: these keyword arguments only
        GenOptions opts = GenOptions.builder().maxStatements(options.maxStatements()).minStatements(options.minStatements())
                .maxDepth(options.maxDepth()).strict(options.strict()).toggles(options.toggles()).build();
        for (String name : Strategies.REGISTRY.keySet()) {
            for (int k = 0; k < perGenerator; k++) {
                Strategies.Snippet snippet;
                try {
                    snippet = Strategies.runGenerator(name, seed + k, 3, opts);
                } catch (RuntimeException e) {       // not applicable at top level, or a generator bug
                    out.add(new Sample("generator", name, seed + k, "snippet", List.of(), "", "", List.of(name), false,
                            e.getClass().getSimpleName() + ": " + e.getMessage()));
                    continue;
                }
                String[] l = lifted(snippet.code(), options);
                boolean fired = snippet.emitted().stream().anyMatch(i -> name.equals(i.gen()));
                out.add(new Sample("generator", name, seed + k, "snippet", snippet.code(), l[0], l[1], List.of(name), fired, null));
            }
        }
        for (String name : Strategies.TEMPLATES.keySet()) {
            for (int k = 0; k < perTemplate; k++) {
                Strategies.Generated gen = Strategies.generate(seed + k, opts, name);
                out.add(new Sample("template", name, seed + k, name, gen.code(), gen.source(), gen.plainSource(), enabled, true, null));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------- export

    /** The keyword arguments of {@code export}, with its defaults. */
    public static final class ExportConfig {
        public int perGenerator = 20;
        public int perTemplate = 20;
        public long seed = 0;
        public GenOptions options = GenOptions.DEFAULT;
        public int languageVersion = 200;
        public boolean quiet = false;
        public boolean coverage = false;
        /** A running harness to use; null starts an ensemble of {@code modes} and {@code guide}. */
        public Harness client = null;
        public List<String> modes = HarnessClient.DEFAULT_MODES;
        public String guide = Ensemble.DEFAULT_GUIDE;
        public PrintStream err = System.err;
    }

    /** Per-focus counters. */
    private static final class Acc {
        int samples, fired, syntaxBoth, earlyThrow, throwAny, timeout, resource, fallback, crash, hard, javaTypes, soft, equal, discard;
        final List<Integer> probesBeforeThrow = new ArrayList<>(), bytes = new ArrayList<>(), statements = new ArrayList<>();
        final Map<String, Integer> exceptions = new LinkedHashMap<>();
        final Set<String> sources = new HashSet<>();
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double mean(List<Integer> xs) {
        double s = 0;
        for (int x : xs) s += x;
        return s / xs.size();
    }

    public static Path export(Path outRoot, ExportConfig c) {
        String rid = runId();
        Path out = outRoot.resolve(rid);
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(out.resolve("catalogue.json"), Json.pyDumps(catalogue(), 1, false));

        List<Sample> samples = sample(c.options, c.perGenerator, c.perTemplate, c.seed);
        Map<String, Acc> metrics = new LinkedHashMap<>();
        Map<String, Integer> usage = new LinkedHashMap<>();
        Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> findingFocus = new HashMap<>();
        long t0 = System.nanoTime();
        Harness client = c.client;
        boolean own = client == null;
        if (own) client = Ensemble.fromModes(c.modes, c.guide, c.coverage, true).start();
        try (BufferedWriter sf = Files.newBufferedWriter(out.resolve("samples.jsonl"), StandardCharsets.UTF_8)) {
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                String focus = s.focus();
                Acc m = metrics.computeIfAbsent(focus, k -> new Acc());
                m.samples++;
                if (s.source().isEmpty()) continue;
                String id = String.format("s-%06d", i);
                RunResult run = client.run(s.source(), RunOptions.DEFAULT.withLanguageVersion(c.languageVersion).withId(id).withSeed(1)
                        .withCoverageBucket(c.coverage ? focus : null));
                Verdict v = Compare.compareRun(run);
                Map<String, Object> stats = statsOf(s.code(), s.source());
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", id);
                rec.put("runId", rid);
                rec.put("kind", s.kind());
                rec.put("focus", focus);
                rec.put("source", s.source());
                rec.put("sourcePlain", s.sourcePlain());
                rec.put("seed", s.seed());
                rec.put("template", s.template());
                rec.put("enabledGenerators", s.enabledGenerators());
                rec.put("generatorTrace", generatorTrace(s.source()));
                rec.put("stats", stats);
                rec.put("results", run.toJson().get("results"));
                rec.put("verdict", v.toJson());
                sf.write(Json.pyDumps(rec, null, false) + "\n");
                for (Instruction ins : s.code()) if (ins.gen() != null && !ins.gen().isEmpty()) usage.merge(ins.gen(), 1, Integer::sum);
                if (s.fired()) m.fired++;
                Map<String, Outcome> res = run.results();
                Set<String> statuses = new HashSet<>();
                for (Outcome o : res.values()) statuses.add(o.status);
                if (statuses.equals(Set.of("syntax"))) m.syntaxBoth++;
                for (Outcome o : res.values()) {
                    if ("throw".equals(o.status)) {
                        m.throwAny++;
                        Object name = o.thrown != null ? o.thrown.get("name") : null;
                        m.exceptions.merge(Py.truthy(name) ? Py.str(name) : "?", 1, Integer::sum);
                        m.probesBeforeThrow.add(o.trace.size());
                        if (o.trace.size() < STATUS_THROWN_EARLY) m.earlyThrow++;
                        break;
                    }
                }
                if (statuses.contains("timeout")) m.timeout++;
                if (statuses.contains("resource")) m.resource++;
                if (statuses.contains("fallback")) m.fallback++;
                if (statuses.contains("crash")) m.crash++;
                switch (v.kind) {
                    case Compare.HARD -> m.hard++;
                    case Compare.JAVA_TYPES -> m.javaTypes++;
                    case Compare.SOFT -> m.soft++;
                    case Compare.EQUAL -> m.equal++;
                    case Compare.DISCARD, Compare.SKIP -> m.discard++;
                    default -> throw new IllegalStateException("unknown verdict kind " + v.kind);
                }
                m.bytes.add(((Number) stats.get("bytes")).intValue());
                m.statements.add(((Number) stats.get("statements")).intValue());
                m.sources.add(s.sourcePlain());
                if (v.isFinding()) {
                    String sig = v.signature();
                    Map<String, Object> f = findings.computeIfAbsent(sig, k -> {
                        Map<String, Object> nf = new LinkedHashMap<>();
                        nf.put("signature", sig);
                        nf.put("kind", v.kind);
                        nf.put("count", 0);
                        nf.put("focus", null);
                        nf.put("example", id);
                        nf.put("verdict", v.toJson());
                        nf.put("source", s.source());
                        return nf;
                    });
                    f.put("count", (Integer) f.get("count") + 1);
                    findingFocus.computeIfAbsent(sig, k -> new LinkedHashMap<>()).merge(focus, 1, Integer::sum);
                }
                if (!c.quiet && (i + 1) % 200 == 0) {
                    c.err.println("... " + (i + 1) + "/" + samples.size() + " (" + Fmt.f((i + 1) / ((System.nanoTime() - t0) / 1e9), 1) + "/s)");
                }
            }
            if (c.coverage) write(out.resolve("coverage.json"), Json.pyDumps(client.coverageReport(), 1, false));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (own) client.stop();
        }

        Map<String, Object> table = new LinkedHashMap<>();
        for (var e : metrics.entrySet()) {
            String focus = e.getKey();
            Acc m = e.getValue();
            double n = Math.max(1, m.samples);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", Strategies.TEMPLATES.containsKey(focus) ? "template" : "generator");
            row.put("samples", m.samples);
            row.put("fired", m.fired);
            row.put("usageTotal", usage.getOrDefault(focus, 0));
            row.put("syntaxBothRate", m.syntaxBoth / n);
            row.put("earlyThrowRate", m.earlyThrow / n);
            row.put("throwRate", m.throwAny / n);
            row.put("meanProbesBeforeThrow", m.probesBeforeThrow.isEmpty() ? null : mean(m.probesBeforeThrow));
            row.put("timeouts", m.timeout);
            row.put("resource", m.resource);
            row.put("fallback", m.fallback);
            row.put("crash", m.crash);
            row.put("hard", m.hard);
            row.put("javaTypes", m.javaTypes);
            row.put("soft", m.soft);
            row.put("equal", m.equal);
            row.put("discard", m.discard);
            row.put("meanBytes", m.bytes.isEmpty() ? 0.0 : mean(m.bytes));
            row.put("meanStatements", m.statements.isEmpty() ? 0.0 : mean(m.statements));
            row.put("duplicateRate", m.bytes.isEmpty() ? 0.0 : 1 - (double) m.sources.size() / m.bytes.size());
            Map<String, Integer> exc = new LinkedHashMap<>();
            for (var x : Fmt.mostCommon(m.exceptions, 6)) exc.put(x.getKey(), x.getValue());
            row.put("exceptions", exc);
            table.put(focus, row);
        }
        Map<String, Object> metricsJson = new LinkedHashMap<>();
        metricsJson.put("runId", rid);
        metricsJson.put("languageVersion", c.languageVersion);
        metricsJson.put("samples", samples.size());
        metricsJson.put("generatorUsage", usage);
        metricsJson.put("perFocus", table);
        write(out.resolve("metrics.json"), Json.pyDumps(metricsJson, 1, false));
        List<Map<String, Object>> fl = new ArrayList<>(findings.values());
        fl.sort(Comparator.<Map<String, Object>>comparingInt(f -> -(Integer) f.get("count"))
                .thenComparing(f -> (String) f.get("kind"), Py.ORDER));
        for (Map<String, Object> f : fl) f.put("focus", findingFocus.get((String) f.get("signature")));
        write(out.resolve("findings.json"), Json.pyDumps(fl, 1, false));
        return out;
    }

    public static Path export(Path outRoot) {
        return export(outRoot, new ExportConfig());
    }

    // ------------------------------------------------------------------------------- table

    @SuppressWarnings("unchecked")
    public static void printTable(Path metricsPath, Integer worst, PrintStream out) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(Files.readString(metricsPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> perFocus = (Map<String, Object>) m.get("perFocus");
        List<Map.Entry<String, Object>> rows = new ArrayList<>(perFocus.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Object>>comparingDouble(kv -> {
            Map<String, Object> r = (Map<String, Object>) kv.getValue();
            return -(Fmt.num(r.get("syntaxBothRate")) + Fmt.num(r.get("earlyThrowRate")) + Fmt.num(r.get("crash")));
        }).thenComparing(Map.Entry::getKey, Py.ORDER));
        if (worst != null && worst != 0) {      // rows[:worst]
            int end = worst > 0 ? Math.min(worst, rows.size()) : Math.max(0, rows.size() + worst);
            rows = rows.subList(0, end);
        }
        out.println(Fmt.ljust("focus", 30) + " " + Fmt.ljust("kind", 9) + " " + Fmt.rjust("n", 3) + " " + Fmt.rjust("fired", 5) + " "
                + Fmt.rjust("syntax", 7) + " " + Fmt.rjust("early", 7) + " " + Fmt.rjust("throw", 7) + " " + Fmt.rjust("hard", 4) + " "
                + Fmt.rjust("jtype", 5) + " " + Fmt.rjust("dup", 5) + " " + Fmt.rjust("bytes", 6) + "  top exceptions");
        for (var kv : rows) {
            Map<String, Object> r = (Map<String, Object>) kv.getValue();
            List<String> exc = new ArrayList<>();
            for (var x : ((Map<String, Object>) r.get("exceptions")).entrySet()) {
                if (exc.size() == 3) break;
                exc.add(x.getKey() + "x" + Py.str(x.getValue()));
            }
            out.println(Fmt.ljust(kv.getKey(), 30) + " " + Fmt.ljust(r.get("kind"), 9) + " " + Fmt.rjust(r.get("samples"), 3) + " "
                    + Fmt.rjust(r.get("fired"), 5) + " " + Fmt.rjust(Fmt.pct(Fmt.num(r.get("syntaxBothRate")), 0), 7) + " "
                    + Fmt.rjust(Fmt.pct(Fmt.num(r.get("earlyThrowRate")), 0), 7) + " "
                    + Fmt.rjust(Fmt.pct(Fmt.num(r.get("throwRate")), 0), 7) + " " + Fmt.rjust(r.get("hard"), 4) + " "
                    + Fmt.rjust(r.get("javaTypes"), 5) + " " + Fmt.rjust(Fmt.pct(Fmt.num(r.get("duplicateRate")), 0), 5) + " "
                    + Fmt.rjust(Fmt.f(Fmt.num(r.get("meanBytes")), 0), 6) + "  " + String.join(", ", exc));
        }
        Map<String, Object> usage = (Map<String, Object>) m.get("generatorUsage");
        Strategies.registered();
        List<String> never = new ArrayList<>();
        for (String g : Strategies.REGISTRY.keySet()) {
            Object u = usage.get(g);
            if (u == null || Fmt.num(u) == 0) never.add(g);
        }
        if (!never.isEmpty()) out.println("\nnever fired anywhere: " + String.join(", ", never));
    }
}
