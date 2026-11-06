package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Py;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dev.oxpecker.harness.Compare.DISCARD;
import static dev.oxpecker.harness.Compare.EQUAL;
import static dev.oxpecker.harness.Compare.HARD;
import static dev.oxpecker.harness.Compare.JAVA_TYPES;
import static dev.oxpecker.harness.Compare.SKIP;
import static dev.oxpecker.harness.Compare.compareRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Harness self-tests (brief §5.8), driven through the Java client. */
@Tag("slow")
class HarnessTest {
    static final Path REGRESSIONS = Oxpecker.root().resolve("tests/regressions");
    static final Path SEEDS = Oxpecker.root().resolve("tests/seeds");

    /** The session fixture of tests/conftest.py: an interpreted/compiled Ensemble on the default
     * harness (OXPECKER_COVERAGE=1 attaches JaCoCo). */
    static Ensemble harness;

    @BeforeAll
    static void start() {
        assumeTrue(Files.exists(Oxpecker.harnessBin()), "no launcher " + Oxpecker.harnessBin());
        String cov = System.getenv("OXPECKER_COVERAGE");
        harness = Ensemble.fromModes(cov != null && !cov.isEmpty(), true).start();
    }

    @AfterAll
    static void stop() {
        if (harness != null) harness.stop();
    }

    static RunResult run(String src, RunOptions o) {
        return harness.run(src, o);
    }

    static RunResult run(String src) {
        return harness.run(src);
    }

    @Test
    void info() {
        Map<String, Object> info = harness.info();
        assertTrue(Set.of("Rhino1.9", "Rhino2.0").contains(info.get("name")));
        assertTrue(((List<?>) info.get("modes")).contains("compiled"));
    }

    @Test
    void selfConsistency() {
        String src = "var a = [1, , 3]; var o = {x: -0, get g() { return 1; }}; __probe('p', a, o, 0.1 + 0.2, 'a' + 1);\nMath.random() + Date.now();";
        RunResult r1 = run(src, RunOptions.DEFAULT.withSeed(5));
        RunResult r2 = run(src, RunOptions.DEFAULT.withSeed(5));
        for (String m : r1.results().keySet()) {
            Outcome a = r1.results().get(m), b = r2.results().get(m);
            assertEquals(List.of(a.status, String.valueOf(a.value), a.trace, a.output),
                         List.of(b.status, String.valueOf(b.value), b.trace, b.output));
            assertEquals(a.traceJavaTypes, b.traceJavaTypes);
        }
        assertTrue(Set.of(EQUAL, JAVA_TYPES).contains(compareRun(r1).kind));
    }

    @Test
    void isolationCanary() {
        run("globalThis.leak = 1; this.leak2 = 2; Object.prototype.polluted = 1;");
        RunResult r = run("__probe('p', typeof leak, typeof leak2, 'polluted' in {}); leak;");
        for (Outcome o : r.results().values()) {
            assertEquals("throw", o.status);
            assertEquals("ReferenceError", o.thrown.get("name"));
            assertEquals(List.of("\"p\" | \"undefined\" | \"undefined\" | false"), o.trace);
        }
    }

    @Test
    void fallbackDetection() {
        // A single function body far beyond the 64 KB JVM method limit.
        String body = IntStream.range(0, 20000).mapToObj(i -> "  x = x + " + i + " * y;").collect(Collectors.joining("\n"));
        String src = "function big(x, y) {\n" + body + "\n  return x;\n}\nbig(1, 2);";
        RunResult r = run(src, RunOptions.DEFAULT.withWallMs(30000));
        assertEquals("fallback", r.results().get("compiled").status);
        assertEquals(Boolean.FALSE, r.results().get("compiled").compiledActually);
        assertEquals("ok", r.results().get("interpreted").status);
        assertEquals(SKIP, compareRun(r).kind);
    }

    @Test
    void smallScriptsAreReallyCompiled() {
        RunResult r = run("1 + 1");
        assertEquals(Boolean.TRUE, r.results().get("compiled").compiledActually);
        assertEquals(Boolean.FALSE, r.results().get("interpreted").compiledActually);
    }

    @Test
    void syntaxErrorInBoth() {
        RunResult r = run("var = ;");
        assertTrue(r.results().values().stream().allMatch(o -> o.status.equals("syntax")));
        assertEquals(EQUAL, compareRun(r).kind);
    }

    @Test
    void budgetTimeoutInBoth() {
        RunResult r = run("while (true) {}", RunOptions.DEFAULT.withWallMs(1000).withInstructionBudget(200000));
        assertTrue(r.results().values().stream().allMatch(o -> o.status.equals("timeout")));
        assertEquals(DISCARD, compareRun(r).kind);
    }

    @Test
    void stackOverflowIsResource() {
        RunResult r = run("function f() { return f() + 1; } f();");
        assertTrue(r.results().values().stream().allMatch(o -> o.status.equals("resource")));
    }

    @Test
    void determinismStubs() {
        RunResult r = run("__probe('p', Math.random(), Date.now(), new Date().getTime(), typeof WeakRef);", RunOptions.DEFAULT.withSeed(42));
        Outcome o = r.results().get("interpreted");
        assertTrue(o.trace.equals(List.of("\"p\" | 0.6270739405881613 | 1700000000000 | 1700000000000 | \"undefined\""))
                || o.trace.get(0).startsWith("\"p\" | 0."));
        assertEquals(EQUAL, compareRun(r).kind);
    }

    /** Java representation types reach the trace. The integer literal 7 arrives at a Java host
     * function as Double from the interpreter but as Integer from compiled code: the kind of
     * interop difference the soft "java types" verdict reports. */
    @Test
    void javaTypesAreReported() {
        RunResult r = run("var s = 'x'; s += 'y'; __probe('p', s, 1.5, 7);");
        assertEquals(List.of("String | ConsString | Double | Double"), r.results().get("interpreted").traceJavaTypes);
        assertEquals(List.of("String | ConsString | Double | Integer"), r.results().get("compiled").traceJavaTypes);
        assertEquals(JAVA_TYPES, compareRun(r).kind);
        assertEquals(HARD, compareRun(r, true).kind);
    }

    @Test
    void thrownValueCanonicalised() {
        RunResult r = run("throw {a: [1, -0], b: null};");
        Outcome o = r.results().get("compiled");
        assertEquals("throw", o.status);
        assertEquals("js-value", o.thrown.get("kind"));
        assertEquals("@0Object<Object.prototype>{\"a\"{ecw}:@1Array<Array.prototype>{length=2;[1,-0]};\"b\"{ecw}:null}", o.thrown.get("value"));
    }

    @Test
    void languageVersionIsHonoured() {
        RunResult r = run("__probe('p', typeof Symbol, [1,2].includes(2));", RunOptions.DEFAULT.withLanguageVersion(250));
        assertEquals(EQUAL, compareRun(r).kind);
    }

    static Stream<Path> regressions() throws IOException {
        return js(REGRESSIONS);
    }

    static Stream<Path> seeds() throws IOException {
        return js(SEEDS);
    }

    static Stream<Path> js(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".js")).sorted(Py.PATH_ORDER).toList().stream();
        }
    }

    /** Every checked-in reproducer must still be a finding against the pinned Rhino. A
     * {@code // oxpecker-expect: <kind>} header names the expected verdict kind (default: hard). */
    @ParameterizedTest
    @MethodSource("regressions")
    void knownRegressionsAreDetected(Path path) throws IOException {
        String src = Files.readString(path);
        List<String> lines = Py.splitlines(src);
        String first = src.isEmpty() || lines.isEmpty() ? "" : lines.get(0);
        String expected = first.contains("oxpecker-expect:") ? Py.strip(first.split("oxpecker-expect:", 2)[1]) : HARD;
        Verdict v = compareRun(run(src, RunOptions.DEFAULT.withSourceName(path.getFileName().toString())));
        assertEquals(expected, v.kind, v.pretty());
    }

    /** The test262 cases Rhino marks as failing in one mode only (brief §2), adapted to run
     * standalone, must be reported as hard findings. */
    @ParameterizedTest
    @MethodSource("seeds")
    void test262KnownDivergences(Path path) throws IOException {
        RunResult r = run(Files.readString(path), RunOptions.DEFAULT.withSourceName(path.getFileName().toString()).withLanguageVersion(250));
        Verdict v = compareRun(r);
        assertEquals(HARD, v.kind, v.pretty());
    }
}
