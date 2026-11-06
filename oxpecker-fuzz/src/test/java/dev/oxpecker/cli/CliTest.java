package dev.oxpecker.cli;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Strategies;
import dev.oxpecker.util.Json;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gen and list commands (tests/il/test_cli.py): cases are written deterministically from
 * seeds and the listing names every registered generator. Plus the argparse behaviours the CLI
 * relies on, and one run of bin/oxpecker itself. */
class CliTest {

    /** Main.run with captured stdout and stderr. */
    record Ran(int rc, String out, String err) {}

    static Ran main(String... argv) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int rc;
        try (PrintStream out = new PrintStream(o, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(e, true, StandardCharsets.UTF_8)) {
            rc = Main.run(argv, out, err);
        }
        return new Ran(rc, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void genWritesTheFourFilesPerCaseDeterministically(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a"), b = tmp.resolve("b");
        GenOptions options = GenOptions.builder().maxStatements(6).build();
        List<Path> paths = Main.writeCases(a, 3, 11, options);
        Main.writeCases(b, 3, 11, options);
        assertEquals(List.of("case_0000.js", "case_0001.js", "case_0002.js"),
                paths.stream().map(p -> p.getFileName().toString()).toList());
        for (int k = 0; k < 3; k++) {
            String stem = String.format("case_%04d", k);
            for (String suffix : List.of(".js", ".plain.js", ".il", ".meta.json")) {
                assertEquals(read(a.resolve(stem + suffix)), read(b.resolve(stem + suffix)), stem + suffix);
            }
            Map<String, Object> meta = Json.parseObject(read(a.resolve(stem + ".meta.json")));
            assertEquals(11L + k, meta.get("seed"));
            assertTrue(Strategies.TEMPLATES.containsKey((String) meta.get("template")));
            // case k is generate(seed + k): the shell and the CLI agree
            assertEquals(Strategies.generate(11 + k, options).source(), read(a.resolve(stem + ".js")));
            assertTrue(read(a.resolve(stem + ".js")).contains("__probe"));
            assertFalse(read(a.resolve(stem + ".plain.js")).contains("__probe"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void genCommandRestrictsToATemplateAndFamily(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out");
        Ran r = main("gen", "-o", out.toString(), "-n", "2", "--seed", "3", "--template", "plain", "--family", "values",
                "--family", "operations", "--without", "eval", "--max-statements", "5");
        assertEquals(0, r.rc(), r.err());
        assertTrue(r.out().startsWith("2 programs in " + out + " ("), r.out());
        assertTrue(r.out().strip().endsWith("seeds 3..4"), r.out());
        Set<String> allowed = Strategies.REGISTRY.values().stream()
                .filter(s -> s.family().equals("values") || s.family().equals("operations"))
                .map(Strategies.GeneratorSpec::name).collect(Collectors.toSet());
        for (int k = 0; k < 2; k++) {
            Map<String, Object> meta = Json.parseObject(read(out.resolve(String.format("case_%04d.meta.json", k))));
            assertEquals("plain", meta.get("template"));
            assertTrue(allowed.containsAll((List<String>) meta.get("generators")), meta.toString());
            Map<String, Object> opts = (Map<String, Object>) meta.get("options");
            assertFalse(((List<String>) opts.get("toggles")).contains("eval"));
            assertEquals(List.of("operations", "values"), opts.get("families"));
            assertEquals(5L, opts.get("max_statements"));
        }
    }

    @Test
    void genEvolveListsTheMutationSteps(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out");
        assertEquals(0, main("gen", "-o", out.toString(), "-n", "2", "--evolve", "3", "--max-statements", "5").rc());
        Map<String, Object> meta = Json.parseObject(read(out.resolve("case_0000.meta.json")));
        assertTrue(meta.get("mutations") instanceof List<?> l && l.size() <= 3, meta.toString());
    }

    @Test
    void listNamesEveryGeneratorAndTemplate() {
        Ran r = main("list");
        assertEquals(0, r.rc());
        Strategies.registered();
        for (String name : Strategies.REGISTRY.keySet()) assertTrue(r.out().contains(name), name);
        assertTrue(r.out().startsWith("\n[values]\n"), r.out());
        r = main("list", "--templates");
        assertEquals(0, r.rc());
        for (String name : Strategies.TEMPLATES.keySet()) assertTrue(r.out().contains(name), name);
        assertTrue(r.out().contains(Fmt.ljust("plain", 28) + " weight   6  "), r.out());
    }

    @Test
    void listFiltersByRepeatedFamily() {
        Ran r = main("list", "--family", "control", "--family", "values");
        assertEquals(0, r.rc());
        assertEquals(List.of("[values]", "[control]"),
                r.out().lines().filter(l -> l.startsWith("[")).toList());
    }

    // ------------------------------------------------------------------------------ argparse

    @Test
    void helpIsTheModuleDocstringAndExitsZero() {
        Ran r = main("--help");
        assertEquals(0, r.rc());
        assertTrue(r.out().startsWith("usage: oxpecker [-h]"), r.out());
        assertTrue(r.out().contains("\nCommand-line interface.\n\n  oxpecker run out/ tests/regressions/"), r.out());
        assertTrue(r.out().contains("\npositional arguments:\n  {run,corpus,diff,gen,fuzz,shrink,cmin,related,export,metrics,list,probe}\n"
                + "    run                 run .js files or directories through the harness\n"), r.out());
        assertTrue(r.out().endsWith("\noptions:\n  -h, --help            show this help message and exit\n"), r.out());
    }

    @Test
    void usageErrorsExitTwo() {
        Ran r = main();
        assertEquals(2, r.rc());
        assertTrue(r.err().endsWith("oxpecker: error: the following arguments are required: cmd\n"), r.err());
        r = main("gen");
        assertEquals(2, r.rc());
        assertTrue(r.err().startsWith("usage: oxpecker gen [-h] -o OUT [-n COUNT]"), r.err());
        assertTrue(r.err().endsWith("oxpecker gen: error: the following arguments are required: -o/--out\n"), r.err());
        r = main("gen", "-o", "x", "-n", "q");
        assertEquals(2, r.rc());
        assertTrue(r.err().endsWith("oxpecker gen: error: argument -n/--count: invalid int value: 'q'\n"), r.err());
        r = main("corpus", "xx");
        assertTrue(r.err().endsWith("error: argument corpus: invalid choice: 'xx' (choose from 'test262', 'rhino-jstests', 'rhino-mozilla')\n"), r.err());
        r = main("list", "extra");
        assertEquals(2, r.rc());
        assertTrue(r.err().endsWith("oxpecker: error: unrecognized arguments: extra\n"), r.err());
        r = main("fuzz", "--s", "3");
        assertTrue(r.err().endsWith("error: ambiguous option: --s could match --seconds, --steps, --seed, --shrink-budget, "
                + "--source-shrink-budget, --strict\n"), r.err());
        r = main("run");
        assertTrue(r.err().endsWith("oxpecker run: error: the following arguments are required: paths\n"), r.err());
    }

    @Test
    void optionsParseTheWayArgparseDid() {
        ArgParse.Ns ns = Main.parser().parse(List.of("fuzz", "-o", "c", "--seco=1.5", "-n7", "--family", "a", "--family=b",
                "--seed", "-3", "-q"), System.out, System.err);
        assertEquals("c", ns.str("corpus"));
        assertEquals(1.5, ns.dbl("seconds"));
        assertEquals(7, ns.integer("iterations"));
        assertEquals(List.of("a", "b"), ns.list("family"));
        assertEquals(-3, ns.integer("seed"));
        assertTrue(ns.bool("quiet"));
        assertEquals("interpreted,compiled", ns.str("modes"));
        assertEquals("interpreted", ns.str("guide"));
        assertEquals(0.2, ns.dbl("p_new"));
        assertEquals(20, ns.integer("max_lineage_depth"));
        // corpus's parser-level defaults win over the shared run options'
        ns = Main.parser().parse(List.of("corpus", "test262"), System.out, System.err);
        assertEquals(250, ns.integer("language_version"));
        assertFalse(ns.bool("strict"));
        ns = Main.parser().parse(List.of("run", "a.js", "b.js", "-v"), System.out, System.err);
        assertEquals(List.of("a.js", "b.js"), ns.list("paths"));
        assertEquals(200, ns.integer("language_version"));
    }

    @Test
    void metricsPrintsTheQualityTable(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("metrics.json"), """
                {"runId": "r", "languageVersion": 200, "samples": 2, "generatorUsage": {"integer": 3},
                 "perFocus": {
                  "plain": {"kind": "template", "samples": 1, "fired": 1, "syntaxBothRate": 0.0, "earlyThrowRate": 0.0,
                            "throwRate": 1.0, "crash": 0, "hard": 0, "javaTypes": 1, "duplicateRate": 0.0, "meanBytes": 1234.5,
                            "exceptions": {"TypeError": 1}},
                  "integer": {"kind": "generator", "samples": 1, "fired": 1, "syntaxBothRate": 1.0, "earlyThrowRate": 0.0,
                            "throwRate": 0.125, "crash": 0, "hard": 0, "javaTypes": 0, "duplicateRate": 0.0, "meanBytes": 10.0,
                            "exceptions": {}}}}
                """);
        Ran r = main("metrics", tmp.toString());
        assertEquals(0, r.rc(), r.err());
        List<String> lines = r.out().lines().toList();
        assertEquals("focus                          kind        n fired  syntax   early   throw hard jtype   dup  bytes  top exceptions",
                lines.get(0));
        assertEquals("integer                        generator   1     1    100%      0%     12%    0     0    0%     10  ", lines.get(1));
        assertEquals("plain                          template    1     1      0%      0%    100%    0     1    0%   1234  TypeErrorx1",
                lines.get(2));
        assertTrue(lines.get(4).startsWith("never fired anywhere: "), r.out());
        r = main("metrics", tmp.toString(), "--worst", "1");
        assertEquals(2, r.out().lines().takeWhile(l -> !l.isEmpty()).count());
    }

    // ------------------------------------------------------------------------------ the launcher

    @Test
    @Tag("slow")
    void binOxpeckerRunsTheJar(@TempDir Path tmp) throws Exception {
        Path launcher = Oxpecker.root().resolve("bin").resolve("oxpecker");
        Process p = new ProcessBuilder(launcher.toString(), "gen", "-n", "2", "-o", tmp.toString(), "--seed", "1")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
        assertTrue(output.startsWith("2 programs in " + tmp + " ("), output);
        assertTrue(output.strip().endsWith("seeds 1..2"), output);
        assertEquals(Strategies.generate(1, GenOptions.DEFAULT).source(), read(tmp.resolve("case_0000.js")));
    }
}
