package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.oxpecker.Oxpecker.DEFAULT_HARNESS;
import static dev.oxpecker.harness.Compare.EQUAL;
import static dev.oxpecker.harness.Compare.HARD;
import static dev.oxpecker.harness.Compare.SKIP;
import static dev.oxpecker.harness.Compare.compareRun;
import static dev.oxpecker.harness.Ensemble.COMPARATOR;
import static dev.oxpecker.harness.Ensemble.GUIDE;
import static dev.oxpecker.harness.Runs.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** One harness process per mode: the guide (reference, sole coverage source) and comparators. */
class EnsembleTest {
    static final Path REGRESSIONS = Oxpecker.root().resolve("tests/regressions");
    static final Path AGENT = Oxpecker.harnessLibs(DEFAULT_HARNESS).resolve("jacocoagent.jar");

    // ------------------------------------------------------------------------------- without a JVM

    @Test
    void specsPutTheGuideFirstAndTheRestAsComparators() {
        List<HarnessSpec> specs = Ensemble.specsFor(List.of("interpreted", "compiled", "debugger"), "compiled");
        assertEquals(List.of(List.of("compiled", GUIDE), List.of("interpreted", COMPARATOR), List.of("debugger", COMPARATOR)),
                specs.stream().map(s -> List.of(s.name(), s.role())).toList());
        assertThrows(IllegalArgumentException.class, () -> Ensemble.specsFor(List.of("interpreted"), "compiled"));
    }

    @Test
    void eachModeCanNameItsHarness() {
        String other = !DEFAULT_HARNESS.equals("rhino1.9") ? "rhino1.9" : "rhino2.0";
        assertEquals(new Ensemble.Target(DEFAULT_HARNESS, "interpreted"), Ensemble.parseTarget("interpreted"));
        assertEquals(new Ensemble.Target(other, "compiled"), Ensemble.parseTarget(other + ":compiled"));
        List<HarnessSpec> specs = Ensemble.specsFor(List.of("interpreted", other + ":interpreted"), DEFAULT_HARNESS + ":interpreted");
        assertEquals(List.of(List.of("interpreted", GUIDE, DEFAULT_HARNESS, "interpreted"),
                             List.of(other + ":interpreted", COMPARATOR, other, "interpreted")),
                specs.stream().map(s -> List.of(s.name(), s.role(), s.harness(), s.mode())).toList());
        assertEquals(Oxpecker.harnessBin(other).toString(), specs.get(1).harnessBin());
        assertEquals(List.of(other + ":interpreted", "compiled"),
                Ensemble.specsFor(List.of("compiled"), other + ":interpreted", true).stream().map(HarnessSpec::name).toList());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ensemble.specsFor(List.of("rhino0.1:interpreted"), "rhino0.1:interpreted"));
        assertTrue(e.getMessage().matches("unknown harness 'rhino0.1'.*rhino1.9, rhino2.0.*"), e.getMessage());
    }

    @Test
    void anEnsembleNeedsExactlyOneGuide() {
        assertThrows(IllegalArgumentException.class, () -> new Ensemble(List.of(new HarnessSpec("a", COMPARATOR, "interpreted"))));
        assertThrows(IllegalArgumentException.class, () -> new Ensemble(List.of(new HarnessSpec("a", GUIDE, "interpreted"),
                new HarnessSpec("b", GUIDE, "compiled"))));
    }

    @Test
    void onlyTheGuideClientGetsCoverage() {
        Ensemble e = Ensemble.fromModes(true, true);
        Map<String, Boolean> cov = new LinkedHashMap<>();
        e.clients().forEach((n, c) -> cov.put(n, c.coverage()));
        assertEquals(Map.of("interpreted", true, "compiled", false), cov);
    }

    @Test
    void oracleRoleIsReserved() {
        assertThrows(UnsupportedOperationException.class, () -> new HarnessSpec("v8", "oracle", "interpreted"));
    }

    @Test
    void harnessInfoMustDeclareNameAndModes() {
        HarnessInfo d = HarnessInfo.fromJson(Map.of("protocol", 1L, "name", "Rhino", "version", "2.0",
                "modes", List.of("interpreted"), "capabilities", List.of("javaTypes")));
        assertEquals("Rhino 2.0", d.label());
        assertEquals(List.of("interpreted"), d.modes());
        assertNull(d.mode());
        assertEquals(List.of("javaTypes"), d.capabilities());
        HarnessError e = assertThrows(HarnessError.class,
                () -> HarnessInfo.fromJson(Map.of("protocol", 1L, "rhino", "2.0", "modes", List.of("interpreted"))));
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    void theVerdictReadsTheSameWhicheverModeIsTheGuide() {
        Outcome i = Outcome.of("interpreted", "ok").value("1");
        Outcome c = Outcome.of("compiled", "ok").value("2");
        Verdict byInterpreted = compareRun(run("interpreted", "interpreted", i, "compiled", c));
        Verdict byCompiled = compareRun(run("compiled", "compiled", c, "interpreted", i));
        assertEquals(HARD, byInterpreted.kind);
        assertEquals(HARD, byCompiled.kind);
        assertEquals(byInterpreted.signature(), byCompiled.signature());
        assertEquals("compiled", byInterpreted.against);
        assertEquals("interpreted", byCompiled.against);
    }

    @Test
    void pairsAndPairClass() {
        Outcome a = Outcome.of("interpreted", "ok").value("1").engine("rhino 2.0");
        Outcome b = Outcome.of("compiled", "ok").value("1").engine("rhino 2.0");
        Outcome c = Outcome.of("interpreted", "ok").value("2").engine("rhino 1.9.2");
        Verdict v = compareRun(run("new", "new", a, "compiled", b, "old", c));
        Map<String, String> kinds = new LinkedHashMap<>();
        v.pairs.forEach((n, p) -> kinds.put(n, p.kind));
        assertEquals(Map.of("compiled", EQUAL, "old", HARD), kinds);
        assertEquals("old", v.against);
        assertEquals("engine:rhino 1.9.2|rhino 2.0", v.pairClass);
        assertTrue(v.signature().startsWith("hard@engine:rhino 1.9.2|rhino 2.0:"));
        assertEquals("mode", v.pairs.get("compiled").pairClass);
    }

    @Test
    void sidesFromDifferentEnginesAreLabelledByHarness() {
        Outcome a = Outcome.of("interpreted", "ok").value("1").engine("Rhino2.0 2.0");
        Outcome b = Outcome.of("interpreted", "crash").engine("Rhino1.9 1.9.2")
                .engineException(Map.of("clazz", "java.lang.NullPointerException", "message", "x"));
        Verdict v = compareRun(run("new", "new", a, "old", b));
        assertEquals(List.of("old crashed: java.lang.NullPointerException: x"), v.reasons);
        assertTrue(v.coarseSignature().endsWith(":old crashed: java.lang.NullPointerException"));
    }

    @Test
    void theSameModePausingInTwoEnginesIsComparedNotSkipped() {
        Outcome a = Outcome.of("interpreted", "ok").value("3").continuations("pause(1)", "end").engine("Rhino2.0 2.0");
        Outcome b = Outcome.of("interpreted", "ok").value("3").continuations("pause(1)", "end").engine("Rhino1.9 1.9.2");
        assertEquals(EQUAL, compareRun(run("new", "new", a, "old", b)).kind);
        b.continuations("pause(1)", "abandoned");
        assertEquals(HARD, compareRun(run("new", "new", a, "old", b)).kind);
    }

    @Test
    void fieldsOutsideAHarnessCapabilitiesAreNotCompared() {
        Outcome a = Outcome.of("interpreted", "ok").value("1").javaTrace("x").valueJavaTypes("Integer");
        Outcome b = Outcome.of("interpreted", "ok").value("1").engine("other").capabilities(List.of());
        assertEquals(EQUAL, compareRun(run("a", "a", a, "b", b)).kind);
        b.capabilities = null;   // all fields
        assertEquals(HARD, compareRun(run("a", "a", a, "b", b)).kind);
    }

    @Test
    void aGuideAloneIsSkipped() {
        assertEquals(SKIP, compareRun(run("interpreted", "interpreted", Outcome.of("interpreted", "ok"))).kind);
    }

    @Test
    void describeMismatchNamesWhatChanged() {
        Map<String, Object> interp = Map.of("guide", "interpreted", "harnesses",
                List.of(Map.of("name", "interpreted", "role", GUIDE, "mode", "interpreted", "engine", "rhino 2.0")));
        Map<String, Object> compiled = Map.of("guide", "compiled", "harnesses",
                List.of(Map.of("name", "compiled", "role", GUIDE, "mode", "compiled", "engine", "rhino 2.0")));
        assertNull(Ensemble.describeMismatch(interp, interp));
        assertEquals("the guide's mode changed: interpreted -> compiled", Ensemble.describeMismatch(interp, compiled));
        assertEquals("the guide's mode changed: None -> interpreted", Ensemble.describeMismatch(Map.of(), interp));
    }

    // ------------------------------------------------------------------------------- against the harness

    static Stream<Path> firstRegressions() throws IOException {
        try (Stream<Path> s = Files.list(REGRESSIONS)) {
            return s.filter(p -> p.toString().endsWith(".js")).sorted(dev.oxpecker.util.Py.PATH_ORDER).limit(6).toList().stream();
        }
    }

    @Test
    @Tag("slow")
    void theHarnessDeclaresItsNameAndModes() {
        HarnessInfo d = HarnessClient.describeHarness();
        assertTrue(Set.of("Rhino1.9", "Rhino2.0").contains(d.name()));
        assertTrue(d.modes().containsAll(List.of("interpreted", "compiled", "debugger")));
        assertNull(d.mode());
    }

    @Test
    @Tag("slow")
    void aModeTheHarnessDoesNotDeclareIsRefusedBeforeStarting() {
        Ensemble e = new Ensemble(List.of(new HarnessSpec("x", GUIDE, "bytecode-v2")));
        HarnessError err = assertThrows(HarnessError.class, e::start);
        assertTrue(err.getMessage().matches("(?s).*does not support mode 'bytecode-v2'; it declares .*interpreted.*"), err.getMessage());
        assertTrue(e.clients().values().stream().noneMatch(HarnessClient::isRunning));
    }

    @Test
    @Tag("slow")
    void aPinnedHarnessRefusesOtherModes() {
        try (HarnessClient c = new HarnessClient(Oxpecker.harnessBin(), "compiled", true, false).start()) {
            Map<String, Object> info = c.info();
            assertEquals("compiled", info.get("mode"));
            assertEquals(1L, info.get("protocol"));
            assertTrue(((List<?>) info.get("capabilities")).contains("javaTypes"));
            assertEquals(List.of("compiled"), List.copyOf(c.run("1").results().keySet()));
            HarnessError e = assertThrows(HarnessError.class, () -> c.run("1", RunOptions.DEFAULT.withModes("interpreted")));
            assertTrue(e.getMessage().contains("pinned"), e.getMessage());
        }
    }

    @Test
    @Tag("slow")
    void resultsAreKeyedByHarnessAndComparatorsCanBeLeftOut() {
        try (Ensemble e = Ensemble.fromModes(List.of("interpreted", "compiled", "debugger"), "interpreted", false, true).start()) {
            RunResult run = e.run("var x = 'a'; x + 'b'");
            assertEquals(List.of("interpreted", "compiled", "debugger"), List.copyOf(run.results().keySet()));
            assertEquals("interpreted", run.guide());
            assertEquals(COMPARATOR, run.results().get("compiled").role);
            assertEquals(Boolean.TRUE, run.results().get("compiled").compiledActually);
            assertTrue(run.results().get("compiled").engine.startsWith("Rhino"));
            assertEquals("compiled", e.declared().get("compiled").mode());
            assertEquals(EQUAL, compareRun(run).kind);
            assertEquals(List.of("interpreted"), List.copyOf(e.run("1", RunOptions.DEFAULT.withComparators(List.of())).results().keySet()));
            assertEquals(List.of("interpreted", "debugger"),
                    List.copyOf(e.run("1", RunOptions.DEFAULT.withComparators("debugger")).results().keySet()));
            assertEquals(List.of("interpreted", "compiled"),
                    List.copyOf(e.run("1", RunOptions.DEFAULT.withModes("compiled")).results().keySet()));
        }
    }

    @ParameterizedTest
    @MethodSource("firstRegressions")
    @Tag("slow")
    void aCompiledGuideSignsFindingsLikeAnInterpretedOne(Path path) throws IOException {
        String src = Files.readString(path);
        RunOptions opts = RunOptions.DEFAULT.withSourceName(path.getFileName().toString());
        try (Ensemble byCompiled = Ensemble.fromModes(HarnessClient.DEFAULT_MODES, "compiled", false, true).start();
             Ensemble byInterpreted = Ensemble.fromModes(false, true).start()) {
            assertEquals(compareRun(byInterpreted.run(src, opts)).signature(), compareRun(byCompiled.run(src, opts)).signature());
        }
    }

    @Test
    @Tag("slow")
    @SuppressWarnings("unchecked")
    void coverageComesFromTheGuideAlone() {
        assumeTrue(Files.exists(AGENT), "agent not built");
        try (Ensemble e = Ensemble.fromModes(HarnessClient.DEFAULT_MODES, "compiled", true, true).start()) {
            assumeTrue(Boolean.TRUE.equals(e.info().get("coverage")), "coverage agent not attached");
            Map<String, Object> hs = (Map<String, Object>) e.info().get("harnesses");
            assertEquals(Boolean.FALSE, ((Map<String, Object>) hs.get("interpreted")).get("coverage"));
            RunResult run = e.run("var s = [1, 2].map(function (x) { return x * 2; }).join();",
                    RunOptions.DEFAULT.withCoverageNewIds(true));
            assertTrue(run.results().get("compiled").newProbes > 0);
            assertFalse(run.results().get("compiled").newProbeIds.isEmpty());
            assertNull(run.results().get("interpreted").newProbes);
            Map<String, Object> buckets = (Map<String, Object>) e.coverageReport().get("buckets");
            assertEquals(Set.of("*|compiled"), buckets.keySet().stream().filter(b -> b.contains("|")).collect(Collectors.toSet()));
        }
    }

    static boolean built(String name) {
        return Files.exists(Oxpecker.harnessLibs(name).resolve("oxpecker-harness-all.jar"));
    }

    @Test
    @Tag("slow")
    void twoRhinoVersionsSideBySide() {
        assumeTrue(built("rhino1.9") && built("rhino2.0"), "both Rhino harnesses must be built");
        try (Ensemble e = new Ensemble(Ensemble.specsFor(List.of("rhino2.0:interpreted", "rhino1.9:interpreted"),
                "rhino2.0:interpreted")).start()) {
            Map<String, String> names = new LinkedHashMap<>();
            for (HarnessSpec s : e.specs()) names.put(s.harness(), s.name());
            Map<String, String> declared = new LinkedHashMap<>();
            e.declared().forEach((n, d) -> declared.put(n, d.name()));
            assertEquals(Map.of(names.get("rhino2.0"), "Rhino2.0", names.get("rhino1.9"), "Rhino1.9"), declared);
            RunResult run = e.run("var a = [3, 1, 2].sort(); a.join() + typeof globalThis");
            Verdict v = compareRun(run);
            assertEquals(EQUAL, v.kind);
            assertTrue(v.pairs.get(names.get("rhino1.9")).pairClass.startsWith("engine:Rhino1.9 1.9.2"));
            assertEquals(List.of("javaTypes", "javaTrace", "continuations"), run.results().get(names.get("rhino1.9")).capabilities);
            assertEquals(List.of("javaTypes", "javaTrace", "continuations"), run.results().get(names.get("rhino2.0")).capabilities);
            RunResult paused = e.run("function f() { var a = __pause(1); return a + __pause(2); } f();");
            assertEquals(List.of("pause(1)", "pause(2)", "end"), paused.results().get(names.get("rhino2.0")).continuations);
            assertEquals(EQUAL, compareRun(paused).kind);
            assertNotNull(e.describe().get("harnesses"));
        }
    }
}
