package dev.oxpecker.cli;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.corpora.Case;
import dev.oxpecker.corpora.RhinoTests;
import dev.oxpecker.corpora.Test262;
import dev.oxpecker.fuzz.Cmin;
import dev.oxpecker.fuzz.Findings;
import dev.oxpecker.fuzz.Fuzz;
import dev.oxpecker.harness.CapabilityProbe;
import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.harness.HarnessClient;
import dev.oxpecker.harness.HarnessSpec;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Instruction;
import dev.oxpecker.il.Mutate;
import dev.oxpecker.il.Program;
import dev.oxpecker.il.Strategies;
import dev.oxpecker.il.Type;
import dev.oxpecker.inspector.Inspector;
import dev.oxpecker.related.Fingerprint;
import dev.oxpecker.related.KnownIndex;
import dev.oxpecker.related.Related;
import dev.oxpecker.shrink.SourceShrink;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;

import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The command-line interface (oxpecker/cli.py): {@code bin/oxpecker <command> ...}. Arguments are
 * parsed the way argparse did ({@link ArgParse}); {@link #run} is {@code main(argv)} with the
 * output streams passed in, returning the exit code.
 */
public final class Main {
    private Main() {}

    /** The module docstring: the top-level help. */
    public static final String DOC = """
            Command-line interface.

              oxpecker run out/ tests/regressions/ run existing .js files through the harness
              oxpecker diff case.js                where the guide's and a comparator's outcomes first differ, per field
              oxpecker corpus test262|rhino-jstests|rhino-mozilla [--filter PREFIX] [--limit N]   replay a corpus
              oxpecker probe                       refresh the Rhino capability table

              oxpecker gen -o out/ -n 50 [--seed 1]  write N generated programs (.js, .plain.js, .il) to a directory
              oxpecker gen -o out/ -n 50 --run     ... and run them through the harness
              oxpecker gen -o out/ -n 50 --evolve 4  ... each case the end of a 4-step mutation lineage
              oxpecker fuzz -o runs/c1 --seconds 600  the loop: coverage corpus of lineages, findings shrunk and recorded
              oxpecker shrink finding.js           statement-level shrinking of a reproducer (writes finding.min.js)
              oxpecker cmin runs/c1/corpus -o runs/c2/corpus   minimise a corpus (smallest entry per probe) to seed the next run
              oxpecker related findings/          which checked-in regressions each finding probably connects to
              oxpecker export -o ui/data --per-generator 20   sample every generator/template for the UI; oxpecker metrics DIR
              oxpecker list [--family F] [--templates]   the registered generators (name, family, weight, doc)

            Each mode runs in its own harness process. The guide (--guide, default interpreted) is the
            reference every other mode is compared against and the only process coverage is gathered from.
            A mode can name the harness (Rhino version) that runs it: --modes interpreted,rhino1.9:interpreted.

            """;

    /** {@code raise SystemExit(message)}: the message to stderr, exit status 1. */
    static final class SystemExit extends RuntimeException {
        SystemExit(String message) {
            super(message, null, false, false);
        }
    }

    public static void main(String[] argv) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int rc = run(argv, out, err);
        out.flush();
        err.flush();
        System.exit(rc);
    }

    /** {@code main(argv)}: parses, runs the command, returns its exit status. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        ArgParse.Ns args;
        try {
            args = parser().parse(Arrays.asList(argv), out, err);
        } catch (ArgParse.Exit e) {
            return e.code;
        }
        Cli cli = new Cli(args, out, err);
        try {
            return switch (args.str("cmd")) {
                case "run" -> cli.cmdRun();
                case "corpus" -> cli.cmdCorpus();
                case "diff" -> cli.cmdDiff();
                case "gen" -> cli.cmdGen();
                case "fuzz" -> cli.cmdFuzz();
                case "shrink" -> cli.cmdShrink();
                case "cmin" -> cli.cmdCmin();
                case "related" -> cli.cmdRelated();
                case "export" -> cli.cmdExport();
                case "metrics" -> cli.cmdMetrics();
                case "list" -> cli.cmdList();
                case "probe" -> cli.cmdProbe();
                default -> throw new IllegalStateException(args.str("cmd"));
            };
        } catch (SystemExit e) {
            err.println(e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            // an uncaught exception: Python printed the traceback and exited 1
            err.println("Traceback (most recent call last):");
            e.printStackTrace(err);
            return 1;
        } finally {
            out.flush();
            err.flush();
        }
    }

    // ----------------------------------------------------------------------------- parser

    private static void addHarnessOptions(ArgParse.Parser p) {
        p.add("--modes").dflt("interpreted,compiled")
                .help("[harness:]mode targets to run, one harness process each, e.g. "
                        + "interpreted,rhino1.9:interpreted (harnesses: bin/harness-*, default $OXPECKER_HARNESS or rhino2.0)");
        p.add("--guide").dflt("interpreted")
                .help("[harness:]mode of the guide: the reference for comparisons and the only process coverage is "
                        + "gathered from (added to --modes if missing)");
    }

    private static void addRunOptions(ArgParse.Parser p) {
        addHarnessOptions(p);
        p.add("--language-version").type("int").dflt(200).help("Rhino language version: 200=ES6, 250=ECMASCRIPT");
        p.add("--wall-ms").type("int").dflt(5000);
        p.add("--strict-java-types").storeTrue()
                .help("treat Integer-vs-Double style Java representation differences as hard findings");
        p.add("-v", "--verbose").storeTrue().help("print every case, not only findings");
        p.add("--coverage").storeTrue()
                .help("attach JaCoCo to the harness and report which Rhino code was reached (per template bucket)");
    }

    static ArgParse.Parser parser() {
        ArgParse.Parser ap = new ArgParse.Parser("oxpecker", DOC);
        ArgParse.Subparsers sub = ap.addSubparsers("cmd", true);

        ArgParse.Parser r = sub.add("run", "run .js files or directories through the harness");
        r.add("paths").nargs("+");
        r.add("--strict").storeTrue().help("prepend \"use strict\"");
        addRunOptions(r);

        ArgParse.Parser c = sub.add("corpus", "replay a corpus (test262) through the harness");
        c.add("corpus").choices("test262", "rhino-jstests", "rhino-mozilla");
        c.add("--filter").help("path prefix under test262/test, e.g. language/statements/try");
        c.add("--limit").type("int").help("stop after N test files");
        c.add("--only-known").storeTrue().help("only files test262.properties tags per mode");
        c.add("--known-divergences").storeTrue().help("list those files and exit");
        c.add("--no-strict-variants").storeTrue().help("run only the sloppy variant");
        c.add("-o", "--out").help("directory for results.jsonl and finding sources");
        addRunOptions(c);
        c.setDefaults("language_version", 250, "strict", false);

        ArgParse.Parser d = sub.add("diff", "run one file and show where the modes first differ, per field");
        d.add("path");
        d.add("--strict").storeTrue();
        d.add("--context").type("int").dflt(60).help("characters of context around the first difference");
        d.add("--against").help("the comparator to show against the guide (default: the first)");
        addRunOptions(d);

        ArgParse.Parser g = sub.add("gen", "generate programs into a directory (and optionally run them)");
        g.add("-o", "--out").required().help("output directory");
        g.add("-n", "--count").type("int").dflt(20);
        g.add("--seed").type("int").dflt(0).help("seed of the first case; case k uses seed+k");
        g.add("--template").help("one template only (see `oxpecker list --templates`)");
        g.add("--family").append().help("restrict the pools to a family (repeatable)");
        g.add("--generator").append().help("restrict the pools to a generator (repeatable)");
        g.add("--without").append().help("disable a toggle: let, generators, for-of, destructuring, spread, continuations, with, eval");
        g.add("--max-statements").type("int");
        g.add("--max-depth").type("int");
        g.add("--strict").storeTrue().help("generate strict-mode programs");
        g.add("--evolve").type("int").dflt(0).metavar("K").help("write the end of a K-step mutation lineage per case");
        g.add("--run").storeTrue().help("run the written cases through the harness");
        g.add("--hard-only").storeTrue()
                .help("with --run, do not report soft or java-types (Integer vs Double) findings; only hard ones");
        addRunOptions(g);

        ArgParse.Parser fz = sub.add("fuzz", "the differential-testing loop: lineages, a coverage corpus, shrunk findings");
        fz.add("-o", "--corpus").required().help("corpus directory (corpus.jsonl, coverage.exec); resumed if present");
        fz.add("-n", "--iterations").type("int").help("stop after N runs");
        fz.add("--seconds").type("float").help("stop after this many seconds");
        fz.add("--steps").type("int").dflt(5).help("mutation steps per round at most: a fresh lineage or one extension (Fuzzilli: 5)");
        fz.add("--max-lineage-depth").type("int").dflt(20).help("safety cap on a lineage's depth (mutation steps) over all extension rounds");
        fz.add("--p-new").type("float").dflt(0.2).help("probability of a fresh lineage instead of extending a corpus entry");
        fz.add("--seed").type("int");
        fz.add("--shrink-budget").type("int").dflt(150).help("harness runs Hypothesis may spend shrinking one finding");
        fz.add("--compact-budget").type("int").dflt(0).help("runs spent shrinking each new corpus entry while it still reaches its probes (0 = off)");
        fz.add("--source-shrink-budget").type("int").dflt(300).help("runs spent on statement-level shrinking of each finding after Hypothesis (0 = off)");
        fz.add("--findings-dir").help("where findings are recorded (default tests/findings)");
        fz.add("--ignore-known").storeTrue().help("shrink and record findings whose signature a regression already has");
        fz.add("--hard-only").storeTrue().help("do not record soft or java-types (Integer vs Double) findings; only hard ones");
        fz.add("--family").append();
        fz.add("--generator").append();
        fz.add("--without").append().help("disable a toggle");
        fz.add("--max-statements").type("int");
        fz.add("--max-depth").type("int");
        fz.add("--strict").storeTrue();
        fz.add("--language-version").type("int").dflt(200);
        fz.add("--reset-coverage").storeTrue()
                .help("discard the corpus's coverage.exec if another guide recorded it (otherwise resuming is refused)");
        addHarnessOptions(fz);
        fz.add("-q", "--quiet").storeTrue();

        ArgParse.Parser sh = sub.add("shrink", "statement-level shrinking of a reproducer while its coarse verdict holds");
        sh.add("path");
        sh.add("-o", "--out").help("output file (default <stem>.min.js next to the input)");
        sh.add("--budget").type("int").dflt(300).help("harness runs to spend");
        sh.add("--strict").storeTrue();
        sh.add("--language-version").type("int").dflt(200);
        sh.add("--wall-ms").type("int").dflt(5000);
        addHarnessOptions(sh);

        ArgParse.Parser cm = sub.add("cmin", "minimise a corpus: the smallest entry per coverage probe (afl-cmin)");
        cm.add("corpus").help("corpus directory to minimise");
        cm.add("-o", "--out").required().help("new corpus directory");
        cm.add("--language-version").type("int").dflt(200);
        cm.add("-q", "--quiet").storeTrue();

        ArgParse.Parser rl = sub.add("related", "which checked-in regressions each finding probably connects to");
        rl.add("paths").nargs("+");
        rl.add("--limit").type("int").dflt(3);
        rl.add("--language-version").type("int").dflt(200);

        ArgParse.Parser ex = sub.add("export", "sample every generator and template, run them, write a run directory for the UI");
        ex.add("-o", "--out").dflt("ui/data").help("run directories go under here (default ui/data)");
        ex.add("--per-generator").type("int").dflt(20);
        ex.add("--per-template").type("int").dflt(20);
        ex.add("--seed").type("int").dflt(0);
        ex.add("--worst").type("int").help("print only the N worst rows of the quality table");
        ex.add("--coverage").storeTrue().help("attach JaCoCo and write coverage.json per focus");
        ex.add("--family").append();
        ex.add("--generator").append();
        ex.add("--without").append().help("disable a toggle");
        ex.add("--max-statements").type("int");
        ex.add("--max-depth").type("int");
        ex.add("--strict").storeTrue();
        ex.add("--language-version").type("int").dflt(200);
        addHarnessOptions(ex);

        ArgParse.Parser me = sub.add("metrics", "print the quality table of an exported run");
        me.add("path").help("the run directory (ui/data/<run-id>)");
        me.add("--worst").type("int");

        ArgParse.Parser ls = sub.add("list", "list the registered generators (or templates)");
        ls.add("--family").append();
        ls.add("--templates").storeTrue();

        ArgParse.Parser pr = sub.add("probe", "probe Rhino's builtins/syntax and refresh the environment model");
        pr.add("--language-version").type("int").dflt(250);
        return ap;
    }

    // ----------------------------------------------------------------------------- generating

    /** {@code _gen_options(args)}. */
    static GenOptions genOptions(ArgParse.Ns a) {
        GenOptions.Builder b = GenOptions.builder().strict(a.bool("strict"));
        if (a.integer("max_statements") != null) b.maxStatements(a.integer("max_statements"));   // min_statements follows it when smaller
        if (a.integer("max_depth") != null) b.maxDepth(a.integer("max_depth"));
        if (a.list("family") != null && !a.list("family").isEmpty()) b.families(new HashSet<>(a.list("family")));
        if (a.list("generator") != null && !a.list("generator").isEmpty()) b.generators(new HashSet<>(a.list("generator")));
        if (a.list("without") != null && !a.list("without").isEmpty()) {
            Set<String> t = new HashSet<>(GenOptions.ALL_TOGGLES);
            a.list("without").forEach(t::remove);
            b.toggles(t);
        }
        Integer lv = a.integer("language_version");
        if (lv != null && lv != 0) b.languageVersion(lv);
        return b.build();
    }

    /** A drawn case: a generated program, or the end of a mutation lineage. */
    private record Drawn(String source, String plainSource, List<Instruction> code, String template, List<String> steps) {}

    /**
     * Generates {@code count} programs from {@code seed} and writes case_NNNN.js (probed),
     * .plain.js, .il and .meta.json into {@code out}. Returns the .js paths. Case k is
     * {@code generate(seed + k)}, or with {@code evolve} the end of {@code lineage(seed + k,
     * steps=evolve)} (the steps are listed in the meta).
     */
    public static List<Path> writeCases(Path out, int count, long seed, GenOptions options, String template, int evolve) {
        Strategies.registered();
        var programs = evolve == 0 ? Strategies.programs(options, template) : null;
        var lineages = evolve != 0 ? Mutate.evolved(options, evolve, null, null) : null;
        List<Path> paths = new ArrayList<>();
        try {
            Files.createDirectories(out);
            for (int k = 0; k < count; k++) {
                Drawn gen;
                if (programs != null) {
                    Strategies.Generated g = Strategies.draw(programs, seed + k);
                    gen = new Drawn(g.source(), g.plainSource(), g.code(), g.template(), List.of());
                } else {
                    Mutate.Lineage l = Strategies.draw(lineages, seed + k);
                    gen = new Drawn(l.source(), l.plainSource(), l.code(), l.template(), l.stepNames());
                }
                String stem = String.format("case_%04d", k);
                Path js = out.resolve(stem + ".js");
                Files.writeString(js, gen.source(), StandardCharsets.UTF_8);
                Files.writeString(out.resolve(stem + ".plain.js"), gen.plainSource(), StandardCharsets.UTF_8);
                Files.writeString(out.resolve(stem + ".il"), Program.dump(gen.code()) + "\n", StandardCharsets.UTF_8);
                Set<String> used = new TreeSet<>(Py.ORDER);
                for (Instruction i : gen.code()) if (i.gen() != null && !i.gen().isEmpty()) used.add(i.gen());
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("strict", options.strict());
                opts.put("max_statements", options.maxStatements());
                opts.put("max_depth", options.maxDepth());
                opts.put("toggles", Py.sorted(options.toggles()));
                opts.put("families", Py.sorted(options.families() != null ? options.families() : Set.of()));
                opts.put("generators", Py.sorted(options.generators() != null ? options.generators() : Set.of()));
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("seed", seed + k);
                meta.put("template", gen.template());
                meta.put("generators", new ArrayList<>(used));
                meta.put("instructions", gen.code().size());
                meta.put("mutations", gen.steps());
                meta.put("options", opts);
                Files.writeString(out.resolve(stem + ".meta.json"), Json.pyDumps(meta, 1, false), StandardCharsets.UTF_8);
                paths.add(js);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return paths;
    }

    public static List<Path> writeCases(Path out, int count, long seed, GenOptions options) {
        return writeCases(out, count, seed, options, null, 0);
    }

    // ----------------------------------------------------------------------------- helpers

    static String stem(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(0, dot) : name;
    }

    static String suffix(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    /** {@code _js_files}: the .js files of each directory (sorted, not .plain.js), and .js paths as given. */
    static List<Path> jsFiles(List<Path> paths) {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> {
                        String n = f.getFileName().toString();
                        return n.endsWith(".js") && !n.endsWith(".plain.js");
                    }).sorted(Py.PATH_ORDER).forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (suffix(p).equals(".js")) {
                files.add(p);
            }
        }
        return files;
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String joinCounts(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (String k : Py.sorted(counts.keySet())) parts.add(k + "=" + counts.get(k));
        return String.join(", ", parts);
    }

    static <K> void bump(Map<K, Integer> m, K k) {
        m.merge(k, 1, Integer::sum);
    }

    // ----------------------------------------------------------------------------- commands

    /** The commands, over one parsed namespace and the output streams. */
    static final class Cli {
        final ArgParse.Ns args;
        final PrintStream out, err;

        Cli(ArgParse.Ns args, PrintStream out, PrintStream err) {
            this.args = args;
            this.out = out;
            this.err = err;
        }

        /** The harness processes {@code --modes} and {@code --guide} ask for, each a
         * {@code [harness:]mode}: the guide is added to the modes if missing, and every other target
         * is a comparator. */
        List<HarnessSpec> specs() {
            List<String> modes = Arrays.stream(args.str("modes").split(",", -1)).filter(m -> !m.isEmpty()).toList();
            try {
                return Ensemble.specsFor(modes, args.str("guide"), true);
            } catch (IllegalArgumentException e) {
                throw new SystemExit("oxpecker: " + e.getMessage());
            }
        }

        Ensemble ensemble(boolean coverage) {
            return new Ensemble(specs(), coverage, true);
        }

        // ------------------------------------------------------------------------- running

        record Rec(Verdict verdict) {}

        Map<String, Rec> runPaths(List<Path> paths) {
            List<Path> files = jsFiles(paths);
            if (files.isEmpty()) {
                err.println("no .js files found");
                return Map.of();
            }
            boolean coverage = args.bool("coverage");
            Ensemble client = ensemble(coverage);
            List<String> names = client.names();
            boolean hardOnly = args.bool("hard_only");
            Map<String, Integer> counts = new TreeMap<>();
            Map<String, Map<String, Integer>> statusCounts = new LinkedHashMap<>();
            for (String m : names) statusCounts.put(m, new TreeMap<>());
            Map<String, Rec> results = new LinkedHashMap<>();
            long t0 = System.nanoTime();
            try (Ensemble c = client.start()) {
                for (Path f : files) {
                    String src = read(f);
                    RunResult run = c.run(src, RunOptions.DEFAULT.withLanguageVersion(args.integer("language_version"))
                            .withStrict(args.bool("strict")).withId(stem(f)).withSourceName(f.getFileName().toString())
                            .withWallMs(args.integer("wall_ms")));
                    Verdict v = Compare.compareRun(run, args.bool("strict_java_types"));
                    bump(counts, v.kind);
                    for (var e : run.results().entrySet()) bump(statusCounts.computeIfAbsent(e.getKey(), k -> new TreeMap<>()), e.getValue().status);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", stem(f));
                    rec.put("file", f.toString());
                    rec.put("verdict", v.toJson());
                    rec.put("results", run.toJson().get("results"));
                    results.put(stem(f), new Rec(v));
                    write(f.resolveSibling(stem(f) + ".result.json"), Json.pyDumps(rec, 2, false));
                    String line = Fmt.ljust(f.getFileName().toString(), 28) + " " + v.pretty();
                    if (v.isFinding() && !(hardOnly && !v.kind.equals(Compare.HARD))) {
                        out.println(line);
                    } else if (args.bool("verbose")) {
                        out.println(line);
                    }
                }
                if (coverage) {
                    Map<String, Object> cov = c.coverageReport();
                    Path first = paths.get(0);
                    Path outDir = Files.isDirectory(first) ? first : parentOf(first);
                    write(outDir.resolve("coverage.json"), Json.pyDumps(cov, 1, false));
                    printCoverage(cov);
                }
            }
            double elapsed = (System.nanoTime() - t0) / 1e9;
            out.println("\n" + files.size() + " cases in " + Fmt.f(elapsed, 1) + "s (" + Fmt.f(files.size() / Math.max(elapsed, 1e-9), 1)
                    + "/s), JVM restarts: " + client.restarts());
            out.println("verdicts: " + joinCounts(counts));
            Map<String, Integer> sigs = new LinkedHashMap<>();
            for (Rec r : results.values()) {
                String k = r.verdict().kind;
                if (k.equals(Compare.HARD) || (!hardOnly && k.equals(Compare.JAVA_TYPES))) bump(sigs, r.verdict().signature());
            }
            if (!sigs.isEmpty()) {
                out.println("unique finding signatures:");
                for (var e : Fmt.mostCommon(sigs, null)) out.println("  " + Fmt.rjust(e.getValue(), 4) + "  " + e.getKey());
            }
            for (String m : names) out.println("  " + Fmt.ljust(m, 12) + " " + joinCounts(statusCounts.get(m)));
            return results;
        }

        /** {@code p.parent}: "." for a bare file name. */
        static Path parentOf(Path p) {
            return p.getParent() != null ? p.getParent() : Path.of("");
        }

        @SuppressWarnings("unchecked")
        void printCoverage(Map<String, Object> cov) {
            if (!Py.truthy(cov.get("enabled"))) {
                out.println("coverage: agent not attached (is OXPECKER_COVERAGE=1 reaching bin/harness?)");
                return;
            }
            out.println("\nRhino coverage: " + Py.str(cov.get("coveredProbes")) + "/" + Py.str(cov.get("totalProbes")) + " probes");
            List<Object> zeros6 = List.of(0L, 0L, 0L, 0L, 0L, 0L), zeros2 = List.of(0L, 0L);
            for (var e : ((Map<String, Object>) cov.get("buckets")).entrySet()) {
                Map<String, Object> rep = (Map<String, Object>) e.getValue();
                Map<String, Object> pk = (Map<String, Object>) rep.get("packages");
                List<Object> opt = (List<Object>) pk.getOrDefault("org.mozilla.javascript.optimizer", zeros6);
                List<Object> core = (List<Object>) pk.getOrDefault("org.mozilla.javascript", zeros6);
                List<Object> interp = (List<Object>) ((Map<String, Object>) rep.get("classes")).getOrDefault("org.mozilla.javascript.Interpreter", zeros2);
                out.println("  " + Fmt.ljust(e.getKey(), 40) + " optimizer methods " + Fmt.rjust(opt.get(0), 4) + "/" + Fmt.ljust(opt.get(1), 4)
                        + " branches " + Fmt.rjust(opt.get(2), 5) + "/" + Fmt.ljust(opt.get(3), 5) + "  "
                        + "core methods " + Fmt.rjust(core.get(0), 5) + "/" + Fmt.ljust(core.get(1), 5) + "  Interpreter "
                        + Fmt.rjust(interp.get(0), 3) + "/" + Py.str(interp.get(1)));
            }
        }

        int cmdRun() {
            List<Path> paths = args.list("paths").stream().map(Path::of).toList();
            Map<String, Rec> results = runPaths(paths);
            long hard = results.values().stream().filter(r -> r.verdict().kind.equals(Compare.HARD)).count();
            return hard > 0 ? 1 : 0;
        }

        // ------------------------------------------------------------------------- corpora

        int cmdCorpus() {
            if (args.bool("known_divergences")) {
                Map<String, Set<String>> kd = Test262.knownDivergences();
                for (String p : Py.sorted(kd.keySet())) out.println(Fmt.ljust(String.join(",", Py.sorted(kd.get(p))), 22) + " " + p);
                return 0;
            }
            Path outDir = args.str("out") != null ? Path.of(args.str("out")) : null;
            try {
                if (outDir != null) Files.createDirectories(outDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Ensemble client = ensemble(false);
            List<String> names = client.names();
            Map<String, Integer> counts = new TreeMap<>();
            Map<String, Map<String, Integer>> statusCounts = new LinkedHashMap<>();
            for (String m : names) statusCounts.put(m, new TreeMap<>());
            Map<String, Integer> sigs = new LinkedHashMap<>();
            int n = 0;
            long t0 = System.nanoTime();
            String corpus = args.str("corpus");
            Stream<Case> it;
            try {
                it = switch (corpus) {
                    case "test262" -> Test262.cases(args.str("filter"), args.integer("limit"), !args.bool("no_strict_variants"),
                            args.bool("only_known"));
                    case "rhino-jstests" -> RhinoTests.jstests(args.str("filter"), args.integer("limit"));
                    default -> RhinoTests.mozilla(args.str("filter"), args.integer("limit"));
                };
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
            BufferedWriter log = null;
            try (Ensemble c = client.start(); Stream<Case> cases = it) {
                if (outDir != null) log = Files.newBufferedWriter(outDir.resolve("results.jsonl"), StandardCharsets.UTF_8);
                for (Iterator<Case> iter = cases.iterator(); iter.hasNext(); ) {
                    Case cs = iter.next();
                    n++;
                    RunResult run = c.run(cs.source(), RunOptions.DEFAULT.withLanguageVersion(args.integer("language_version"))
                            .withId(cs.id()).withSourceName(cs.path()).withStrict(cs.strict() && !corpus.equals("test262"))
                            .withWallMs(args.integer("wall_ms")));
                    Verdict v = Compare.compareRun(run, args.bool("strict_java_types"));
                    bump(counts, v.kind);
                    for (var e : run.results().entrySet()) bump(statusCounts.computeIfAbsent(e.getKey(), k -> new TreeMap<>()), e.getValue().status);
                    if (log != null) {
                        Map<String, Object> rec = new LinkedHashMap<>();
                        rec.put("id", cs.id());
                        rec.put("path", cs.path());
                        rec.put("strict", cs.strict());
                        rec.put("tags", cs.tags());
                        rec.put("verdict", v.toJson());
                        rec.put("results", run.toJson().get("results"));
                        log.write(Json.pyDumps(rec, null, false) + "\n");
                    }
                    if (v.kind.equals(Compare.HARD) || v.kind.equals(Compare.JAVA_TYPES)) bump(sigs, v.signature());
                    String known = cs.tags().get("knownDivergence");
                    if (v.isFinding() || Py.truthy(known) || args.bool("verbose")) {
                        String flag = Py.truthy(known) ? "  (rhino: fails in " + known + ")" : "";
                        out.println(Fmt.ljust(cs.id(), 70) + " " + Py.head(v.pretty(), 110) + flag);
                        if (v.isFinding() && outDir != null) {
                            String name = cs.path().replace("/", "__") + (cs.strict() ? "--strict" : "") + ".js";
                            write(outDir.resolve(name), cs.source());
                        }
                    }
                    if (n % 500 == 0) err.println("... " + n + " cases, " + Fmt.f(n / ((System.nanoTime() - t0) / 1e9), 1) + "/s");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                if (log != null) {
                    try {
                        log.close();
                    } catch (IOException ignored) {
                        // as Python's log.close()
                    }
                }
            }
            double elapsed = (System.nanoTime() - t0) / 1e9;
            out.println("\n" + n + " cases in " + Fmt.f(elapsed, 1) + "s (" + Fmt.f(n / Math.max(elapsed, 1e-9), 1) + "/s), JVM restarts: "
                    + client.restarts());
            out.println("verdicts: " + joinCounts(counts));
            for (String m : names) out.println("  " + Fmt.ljust(m, 12) + " " + joinCounts(statusCounts.get(m)));
            if (!sigs.isEmpty()) {
                out.println("unique finding signatures:");
                for (var e : Fmt.mostCommon(sigs, 30)) out.println("  " + Fmt.rjust(e.getValue(), 5) + "  " + e.getKey());
            }
            return 0;
        }

        // ------------------------------------------------------------------------- diff

        static final List<String> DIFF_FIELDS = List.of("status", "compiledActually", "value", "valueJavaTypes", "thrown", "trace",
                "traceJavaTypes", "javaTrace", "javaTraceTypes", "continuations", "output", "soft", "reason");

        static Object field(Outcome o, String f) {
            return switch (f) {
                case "status" -> o.status;
                case "compiledActually" -> o.compiledActually;
                case "value" -> o.value;
                case "valueJavaTypes" -> o.valueJavaTypes;
                case "thrown" -> o.thrown;
                case "trace" -> o.trace;
                case "traceJavaTypes" -> o.traceJavaTypes;
                case "javaTrace" -> o.javaTrace;
                case "javaTraceTypes" -> o.javaTraceTypes;
                case "continuations" -> o.continuations;
                case "output" -> o.output;
                case "soft" -> o.soft;
                case "reason" -> o.reason;
                default -> throw new IllegalArgumentException(f);
            };
        }

        /** Runs one file and shows, per field, where the guide's and a comparator's outcomes first differ. */
        int cmdDiff() {
            Path path = Path.of(args.str("path"));
            Ensemble client = ensemble(false);
            String against = Py.truthy(args.str("against")) ? args.str("against")
                    : (!client.comparators().isEmpty() ? client.comparators().get(0) : null);
            if (!client.comparators().contains(against)) {
                err.println("--against must name a comparator: one of " + Py.repr(client.comparators()));
                return 2;
            }
            RunResult run;
            try (Ensemble c = client.start()) {
                run = c.run(read(path), RunOptions.DEFAULT.withLanguageVersion(args.integer("language_version"))
                        .withStrict(args.bool("strict")).withId(stem(path)).withSourceName(path.getFileName().toString())
                        .withWallMs(args.integer("wall_ms")));
            }
            String[] modes = {client.guide(), against};
            Outcome a = run.results().get(client.guide()), b = run.results().get(against);
            int ctx = args.integer("context");
            out.println("verdict: " + Compare.compareRun(run, args.bool("strict_java_types")).pretty() + "\n");
            for (String fieldName : DIFF_FIELDS) {
                Object x = field(a, fieldName), y = field(b, fieldName);
                if (Py.eq(x, y)) continue;
                if (x instanceof List<?> xl && y instanceof List<?> yl) {
                    boolean shown = false;
                    for (int i = 0; i < Math.min(xl.size(), yl.size()); i++) {
                        if (!Py.eq(xl.get(i), yl.get(i))) {
                            showDiff(fieldName + "[" + i + "]", Py.str(xl.get(i)), Py.str(yl.get(i)), modes, ctx);
                            shown = true;
                            break;
                        }
                    }
                    if (!shown) {
                        out.println(fieldName + ": length " + xl.size() + " vs " + yl.size());
                        List<?> extra = xl.size() > yl.size() ? xl.subList(yl.size(), xl.size()) : yl.subList(xl.size(), yl.size());
                        out.println("  extra in " + (xl.size() > yl.size() ? modes[0] : modes[1]) + ": " + Py.head(Py.str(extra.get(0)), 200));
                    }
                } else {
                    showDiff(fieldName, x instanceof Map ? Json.pyDumps(x, null, false) : Py.str(x),
                            y instanceof Map ? Json.pyDumps(y, null, false) : Py.str(y), modes, ctx);
                }
            }
            return 0;
        }

        void showDiff(String label, String x, String y, String[] modes, int ctx) {
            int[] xs = x.codePoints().toArray(), ys = y.codePoints().toArray();
            int i = Math.min(xs.length, ys.length);
            for (int k = 0; k < Math.min(xs.length, ys.length); k++) {
                if (xs[k] != ys[k]) {
                    i = k;
                    break;
                }
            }
            int lo = Math.max(0, i - ctx);
            out.println(label + ": first difference at char " + i);
            out.println("  " + Fmt.ljust(modes[0], 12) + " …" + Py.slice(x, lo, i) + "▶" + Py.slice(x, i, i + ctx) + "…");
            out.println("  " + Fmt.ljust(modes[1], 12) + " …" + Py.slice(y, lo, i) + "▶" + Py.slice(y, i, i + ctx) + "…");
        }

        // ------------------------------------------------------------------------- probe

        /** Refreshes the Rhino capability table (builtins, per-snippet syntax support). */
        @SuppressWarnings("unchecked")
        int cmdProbe() {
            int lv = args.integer("language_version");
            Map<String, Object> table;
            try (HarnessClient client = new HarnessClient(true).start()) {
                table = CapabilityProbe.runProbe(client, lv);
            }
            CapabilityProbe.save(table);
            Map<String, List<CapabilityProbe.Member>> protos = CapabilityProbe.methodTable(table);
            Map<String, List<CapabilityProbe.Member>> statics = CapabilityProbe.staticTable(table);
            out.println("rhino " + Py.str(table.get("rhino")) + ", language version " + lv);
            Object globals = table.get("globals");
            int nGlobals = globals instanceof Map<?, ?> m ? m.size() : ((java.util.Collection<?>) globals).size();
            out.println(nGlobals + " globals, " + protos.size() + " prototypes with " + protos.values().stream().mapToInt(List::size).sum()
                    + " methods, " + statics.values().stream().mapToInt(List::size).sum() + " static functions on " + statics.size() + " owners");
            Map<String, Map<String, Object>> syntax = (Map<String, Map<String, Object>>) table.get("syntax");
            List<String> unsupported = new ArrayList<>();
            for (String k : Py.sorted(syntax.keySet())) if (!Py.truthy(syntax.get(k).get("supported"))) unsupported.add(k);
            out.println("syntax unsupported: " + String.join(", ", unsupported));
            for (String k : Py.sorted(syntax.keySet())) {
                Map<String, Object> v = syntax.get(k);
                if (!"equal".equals(v.get("verdict")) || "crash".equals(v.get("interpreted")) || "crash".equals(v.get("compiled"))) {
                    out.println("  !! " + Fmt.ljust(k, 22) + " interpreted=" + Py.str(v.get("interpreted")) + " compiled="
                            + Py.str(v.get("compiled")) + "  " + Py.head(Py.str(v.get("detail")), 120));
                }
            }
            out.println("written to " + CapabilityProbe.cache());
            return 0;
        }

        // ------------------------------------------------------------------------- generating

        int cmdGen() {
            GenOptions options = genOptions(args);
            Path outDir = Path.of(args.str("out"));
            long t0 = System.nanoTime();
            int seed = args.integer("seed");
            List<Path> paths = writeCases(outDir, args.integer("count"), seed, options, args.str("template"), args.integer("evolve"));
            out.println(paths.size() + " programs in " + outDir + " (" + Fmt.f((System.nanoTime() - t0) / 1e9, 1) + "s), seeds "
                    + seed + ".." + (seed + paths.size() - 1));
            if (!args.bool("run")) return 0;
            Map<String, Rec> results = runPaths(List.of(outDir));
            long hard = results.values().stream().filter(r -> r.verdict().kind.equals(Compare.HARD)).count();
            return hard > 0 ? 1 : 0;
        }

        int cmdList() {
            Strategies.registered();
            if (args.bool("templates")) {
                for (String name : Py.sorted(Strategies.TEMPLATES.keySet())) {
                    Strategies.TemplateSpec t = Strategies.TEMPLATES.get(name);
                    out.println(Fmt.ljust(name, 28) + " weight " + Fmt.rjust(t.weight(), 3) + "  " + firstLine(t.doc()));
                }
                return 0;
            }
            List<Strategies.GeneratorSpec> specs = new ArrayList<>(Strategies.REGISTRY.values());
            specs.sort(Comparator.<Strategies.GeneratorSpec>comparingInt(s -> Strategies.FAMILY_ORDER.contains(s.family())
                            ? Strategies.FAMILY_ORDER.indexOf(s.family()) : 99)
                    .thenComparingInt(Strategies.GeneratorSpec::rank)
                    .thenComparing(Strategies.GeneratorSpec::name, Py.ORDER));
            List<String> families = args.list("family");
            String family = null;
            for (Strategies.GeneratorSpec spec : specs) {
                if (families != null && !families.isEmpty() && !families.contains(spec.family())) continue;
                if (!spec.family().equals(family)) {
                    family = spec.family();
                    out.println("\n[" + family + "]");
                }
                String ctxName = contextName(spec.context());
                String ctx = ctxName.equals("JAVASCRIPT") ? "" : " ctx=" + ctxName;
                String inputs = spec.inputs().isEmpty() ? "" : " inputs=" + String.join(",", spec.inputs().stream().map(Type::toString).toList());
                out.println("  " + Fmt.ljust(spec.name(), 30) + " w" + Fmt.ljust(spec.weight(), 3) + ctx + inputs + "  " + firstLine(spec.doc()));
            }
            return 0;
        }

        // ------------------------------------------------------------------------- shrinking

        /** Statement-level delta debugging of a reproducer while its coarse verdict signature holds. */
        int cmdShrink() {
            Path path = Path.of(args.str("path"));
            String source = read(path);
            String small;
            Verdict fin;
            try (Ensemble client = ensemble(false).start()) {
                RunOptions ro = RunOptions.DEFAULT.withLanguageVersion(args.integer("language_version")).withStrict(args.bool("strict"))
                        .withSeed(1).withWallMs(args.integer("wall_ms"));
                java.util.function.Function<String, Verdict> verdictOf = src -> Compare.compareRun(client.run(src, ro));
                Verdict v = verdictOf.apply(source);
                if (!v.isFinding()) {
                    err.println("not a finding: " + v.pretty());
                    return 1;
                }
                String coarse = v.coarseSignature();
                err.println("keeping: " + coarse);
                small = SourceShrink.shrinkSource(source, src -> verdictOf.apply(src).coarseSignature().equals(coarse), args.integer("budget"));
                fin = verdictOf.apply(small);
            }
            Path o = args.str("out") == null ? path.resolveSibling(stem(path) + ".min.js") : Path.of(args.str("out"));
            write(o, small);
            out.println(Py.splitlines(source).size() + " -> " + Py.splitlines(small).size() + " lines: " + o + "\n" + fin.pretty());
            return 0;
        }

        /** Minimise a corpus the way afl-cmin does (Cmin). */
        @SuppressWarnings("unchecked")
        int cmdCmin() {
            Map<String, Object> s;
            try (HarnessClient client = new HarnessClient(Oxpecker.harnessBin(), null, true, true).start()) {
                if (!Py.truthy(client.info().get("coverage"))) {
                    err.println("coverage agent not attached: the harness build must include jacocoagent.jar");
                    return 2;
                }
                s = Cmin.minimise(Path.of(args.str("corpus")), Path.of(args.str("out")), client, GenOptions.DEFAULT, 5,
                        args.integer("language_version"), args.bool("quiet"), err);
            }
            Map<String, Object> r = (Map<String, Object>) s.get("rebuild");
            out.println(Py.str(s.get("entries")) + " -> " + Py.str(s.get("kept")) + " entries; probes: " + Py.str(s.get("probes_chosen"))
                    + " chosen on, " + Py.str(s.get("covered_probes")) + " covered by the kept entries alone; " + Py.str(s.get("seconds"))
                    + "s, " + Py.str(r.getOrDefault("seconds", 0)) + "s of it rebuilding (median " + Py.str(r.get("median_ms")) + " ms, p90 "
                    + Py.str(r.get("p90_ms")) + " ms); " + ((List<?>) s.get("undecodable")).size() + " no longer decode, "
                    + ((List<?>) s.get("restarted")).size() + " restarted the JVM: " + args.str("out"));
            return 0;
        }

        /** Which checked-in regressions each finding probably connects to (related). */
        int cmdRelated() {
            List<Path> files = jsFiles(args.list("paths").stream().map(Path::of).toList());
            try (HarnessClient client = new HarnessClient(true).start()) {
                KnownIndex index = KnownIndex.knownIndex(client);
                for (Path f : files) {
                    String src = read(f);
                    RunResult run = client.run(src, RunOptions.DEFAULT.withLanguageVersion(args.integer("language_version"))
                            .withSourceName(f.getFileName().toString()));
                    Verdict v = Compare.compareRun(run);
                    out.println(f + "\n  " + Py.head(v.pretty(), 160) + (index.signatures.contains(v.signature()) ? "  [known signature]" : ""));
                    List<Related> related = index.related(Fingerprint.of(f.getFileName().toString(), src, run, v), args.integer("limit"), 0.5);
                    for (Related r : related) out.println("  related: " + r);
                    if (related.isEmpty()) out.println("  related: none");
                }
            }
            return 0;
        }

        // ------------------------------------------------------------------------- inspector

        int cmdExport() {
            GenOptions options = genOptions(args);
            Inspector.ExportConfig ec = new Inspector.ExportConfig();
            ec.perGenerator = args.integer("per_generator");
            ec.perTemplate = args.integer("per_template");
            ec.seed = args.integer("seed");
            ec.options = options;
            ec.languageVersion = args.integer("language_version");
            ec.coverage = args.bool("coverage");
            ec.modes = specs().stream().map(HarnessSpec::name).toList();
            ec.guide = args.str("guide");
            ec.err = err;
            Path o = Inspector.export(Path.of(args.str("out")), ec);
            out.println("exported to " + o + "\n");
            Inspector.printTable(o.resolve("metrics.json"), args.integer("worst"), out);
            return 0;
        }

        int cmdMetrics() {
            Inspector.printTable(Path.of(args.str("path")).resolve("metrics.json"), args.integer("worst"), out);
            return 0;
        }

        // ------------------------------------------------------------------------- fuzz

        int cmdFuzz() {
            GenOptions options = genOptions(args);
            Map<String, Object> summary;
            try (Ensemble client = ensemble(true).start()) {
                if (!Py.truthy(client.info().get("coverage"))) {
                    err.println("coverage agent not attached: the harness build must include jacocoagent.jar");
                    return 2;
                }
                KnownIndex index = KnownIndex.knownIndex(client);
                Fuzz.Config c = new Fuzz.Config();
                c.corpusDir = Path.of(args.str("corpus"));
                c.options = options;
                c.iterations = args.integer("iterations");
                c.seconds = args.dbl("seconds");
                c.steps = args.integer("steps");
                c.pNew = args.dbl("p_new");
                c.seed = args.integer("seed") != null ? (long) args.integer("seed") : null;
                c.known = !args.bool("ignore_known") ? index.signatures : new HashSet<>();
                c.findingsDir = args.str("findings_dir") != null ? Path.of(args.str("findings_dir")) : Findings.defaultDir();
                c.shrinkBudget = args.integer("shrink_budget");
                c.compactBudget = args.integer("compact_budget");
                c.sourceShrinkBudget = args.integer("source_shrink_budget");
                c.languageVersion = args.integer("language_version");
                c.client = client;
                c.quiet = args.bool("quiet");
                c.maxDepth = args.integer("max_lineage_depth");
                c.hardOnly = args.bool("hard_only");
                c.resetCoverage = args.bool("reset_coverage");
                c.err = err;
                try {
                    summary = Fuzz.fuzz(c);
                } catch (IllegalStateException e) {
                    err.println(e.getMessage());
                    return 2;
                }
            }
            Map<String, Object> shown = new LinkedHashMap<>(summary);
            shown.remove("signatures");
            out.println(Json.pyDumps(shown, 1, false));
            @SuppressWarnings("unchecked")
            Map<String, Integer> sigs = (Map<String, Integer>) summary.get("signatures");
            if (sigs != null && !sigs.isEmpty()) {
                out.println("hard signatures seen:");
                for (var e : Fmt.mostCommon(sigs, null)) out.println("  " + Fmt.rjust(e.getValue(), 4) + "  " + e.getKey());
            }
            return 0;
        }
    }

    /** {@code doc.strip().splitlines()[0] if doc else ''}. */
    static String firstLine(String doc) {
        if (doc == null || doc.isEmpty()) return "";
        List<String> lines = Py.splitlines(Py.strip(doc));
        return lines.isEmpty() ? "" : lines.get(0);
    }

    /** {@code spec.context.name}: JAVASCRIPT, or the flags joined with |. */
    public static String contextName(int ctx) {
        String s = dev.oxpecker.il.Context.str(ctx);
        return s.startsWith("Context.") ? s.substring("Context.".length()) : s;
    }
}
