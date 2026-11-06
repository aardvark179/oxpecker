package dev.oxpecker.harness;

import com.google.gson.JsonObject;
import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client's side of the protocol against a fake harness (a shell script), no JVM or Rhino. */
class HarnessClientTest {
    static final String FAKE = """
            #!/bin/sh
            LOG="$(dirname "$0")/requests.log"
            INFO='{"protocol":1,"name":"Fake","version":"0","modes":["interpreted","compiled"],"capabilities":[]}'
            if [ "$1" = "--info" ]; then echo "$INFO"; exit 0; fi
            echo "ARGS $*" >> "$LOG"
            MODE=""; if [ "$2" = "--mode" ]; then MODE="$3"; fi
            while IFS= read -r line; do
              echo "$line" >> "$LOG"
              case "$line" in
                *'"cmd":"exit"'*) echo '{"ok":true}'; exit 0;;
                *'"cmd":"info"'*) echo '{"info":'"$INFO"'}';;
                *'"cmd":"coverage-save"'*) echo '{"coveredProbes":42}';;
                *'"cmd":"coverage"'*) echo '{"error":"coverage is off"}';;
                *die*) exit 1;;
                *garbage*) echo 'not json';;
                *refuse*) echo '{"error":"harness pinned to compiled"}';;
                *watchdog*) echo '{"id":"x","results":{"interpreted":{"mode":"interpreted","status":"timeout","reason":"watchdog"}}}';;
                *oom*) echo '{"id":"x","restart":true,"results":{"interpreted":{"mode":"interpreted","status":"crash"}}}';;
                *) if [ -n "$MODE" ]; then echo '{"id":"x","results":{"'"$MODE"'":{"mode":"'"$MODE"'","status":"ok","value":"1"}}}'; continue; fi
                   echo '{"id":"x","results":{"interpreted":{"mode":"interpreted","status":"ok","value":"1","futureField":[1]},"compiled":{"mode":"compiled","status":"ok","value":"1","ms":3,"newProbeIds":{"C":[1,2]}}}}';;
              esac
            done
            """;

    Path bin;
    Path log;

    @BeforeEach
    void fake(@TempDir Path dir) throws IOException {
        bin = dir.resolve("harness-fake");
        log = dir.resolve("requests.log");
        Files.writeString(bin, FAKE);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    HarnessClient client() {
        return new HarnessClient(bin, null, true, false);
    }

    List<String> requests() throws IOException {
        return Files.readAllLines(log);
    }

    @Test
    void sendsTheRequestTheHarnessExpectsAndReadsTheOutcomes() throws IOException {
        try (HarnessClient c = client().start()) {
            RunResult r = c.run("var x = 'é';", RunOptions.DEFAULT.withId("abc").withCoverageRequire(Map.of("C", List.of(1))));
            assertEquals(List.of("interpreted", "compiled"), List.copyOf(r.results().keySet()));
            assertEquals("1", r.results().get("interpreted").value);
            assertEquals(3, r.results().get("compiled").ms);
            assertEquals(Map.of("C", List.of(1, 2)), r.results().get("compiled").newProbeIds);
            assertFalse(r.restarted());
            assertEquals("abc", r.id());
        }
        List<String> reqs = requests();
        assertEquals("ARGS --serve", reqs.get(0));
        JsonObject req = Json.parse(reqs.get(1)).getAsJsonObject();
        assertEquals(List.of("id", "source", "modes", "languageVersion", "strict", "instructionBudget", "wallMs", "seed",
                "sourceName", "continuations", "coverageBucket", "coverageRequire"), List.copyOf(req.keySet()));
        assertEquals(Json.parse("{\"id\":\"abc\",\"source\":\"var x = 'é';\",\"modes\":[\"interpreted\",\"compiled\"],"
                + "\"languageVersion\":200,\"strict\":false,\"instructionBudget\":5000000,\"wallMs\":5000,\"seed\":1,"
                + "\"sourceName\":\"case.js\",\"continuations\":false,\"coverageBucket\":null,\"coverageRequire\":{\"C\":[1]}}"), req);
        assertTrue(reqs.get(1).contains("'é'"), "non-ASCII and quotes are sent as is");
        assertEquals("{\"cmd\":\"exit\"}", reqs.get(2));
    }

    @Test
    void continuationsAreAutoDetectedAndAPinnedModeIsPassed() throws IOException {
        try (HarnessClient c = new HarnessClient(bin, "compiled", true, false).start()) {
            c.run("__pause(1)", RunOptions.DEFAULT.withCoverageNewIds(true).withCoverageHits(true));
        }
        List<String> reqs = requests();
        assertEquals("ARGS --serve --mode compiled", reqs.get(0));
        JsonObject req = Json.parse(reqs.get(1)).getAsJsonObject();
        assertTrue(req.get("continuations").getAsBoolean());
        assertEquals("[\"compiled\"]", req.get("modes").toString());
        assertTrue(req.get("coverageNewIds").getAsBoolean() && req.get("coverageHits").getAsBoolean());
    }

    @Test
    void aDeadJvmIsReportedAsACrashAndRestarted() {
        try (HarnessClient c = client().start()) {
            RunResult r = c.run("die");
            assertTrue(r.restarted());
            for (Outcome o : r.results().values()) {
                assertEquals("crash", o.status);
                assertEquals("jvm-died", o.reason);
                assertEquals(Map.of("clazz", "JVM", "message", "harness died (no response)", "stack", ""), o.engineException);
            }
            assertEquals(1, c.restarts());
            assertEquals("1", c.run("ok").results().get("interpreted").value);   // the restarted JVM answers
        }
    }

    @Test
    void aBadResponseIsACrashToo() {
        try (HarnessClient c = client().start()) {
            Outcome o = c.run("garbage").results().get("compiled");
            assertEquals("bad response from harness: 'not json\\n'", o.engineException.get("message"));
        }
    }

    @Test
    void modesNotRunAreTimeoutsAfterAWatchdogElseCrashes() {
        try (HarnessClient c = client().start()) {
            RunResult w = c.run("watchdog");
            assertEquals("not-run-after-watchdog", w.results().get("compiled").reason);
            assertEquals("timeout", w.results().get("compiled").status);
            RunResult oom = c.run("oom");
            assertTrue(oom.restarted());
            assertEquals("not-run", oom.results().get("compiled").reason);
            assertEquals("crash", oom.results().get("compiled").status);
        }
    }

    @Test
    void harnessErrorsAndCommands() {
        try (HarnessClient c = client().start()) {
            HarnessError e = assertThrows(HarnessError.class, () -> c.run("refuse"));
            assertEquals("harness pinned to compiled", e.getMessage());
            assertEquals(42, c.coverageSave("/tmp/x.exec"));
            assertEquals("coverage is off", assertThrows(HarnessError.class, c::coverageReport).getMessage());
            assertEquals("Fake 0", c.harnessInfo().label());
            assertThrows(IllegalArgumentException.class, () -> c.run("1", RunOptions.DEFAULT.withComparators("x")));
        }
        assertEquals(List.of("interpreted", "compiled"), HarnessClient.describeHarness(bin).modes());
        assertThrows(HarnessError.class, () -> HarnessClient.describeHarness(bin.resolveSibling("none")));
    }

    @Test
    void anEnsembleRunsOneProcessPerModeAndSendsCoverageToTheGuideOnly() throws IOException {
        List<HarnessSpec> specs = List.of(new HarnessSpec("interpreted", Ensemble.GUIDE, "interpreted", "fake", bin.toString()),
                new HarnessSpec("compiled", Ensemble.COMPARATOR, "compiled", "fake", bin.toString()));
        try (Ensemble e = new Ensemble(specs, false, true).start()) {
            RunResult r = e.run("1", RunOptions.DEFAULT.withCoverageBucket("b").withCoverageNewIds(true).withId("same"));
            assertEquals(List.of("interpreted", "compiled"), List.copyOf(r.results().keySet()));
            assertEquals("interpreted", r.guide());
            Outcome c = r.results().get("compiled");
            assertEquals(List.of("compiled", Ensemble.COMPARATOR, "Fake 0"), List.of(c.harness, c.role, c.engine));
            assertEquals(List.of(), c.capabilities);
            assertEquals(List.of("interpreted"), List.copyOf(e.run("1", RunOptions.DEFAULT.withComparators(List.of())).results().keySet()));
            assertThrows(IllegalArgumentException.class, () -> e.run("1", RunOptions.DEFAULT.withComparators("nope")));
            assertThrows(IllegalArgumentException.class,
                    () -> e.run("1", RunOptions.DEFAULT.withComparators("compiled").withModes("compiled")));
            assertEquals("interpreted", e.info().get("guide"));
            assertEquals(Map.of("name", "interpreted", "role", Ensemble.GUIDE, "harness", "fake", "mode", "interpreted",
                    "engine", "Fake 0"), ((List<?>) e.describe().get("harnesses")).get(0));
        }
        List<String> reqs = requests();
        assertTrue(reqs.contains("ARGS --serve --mode interpreted") && reqs.contains("ARGS --serve --mode compiled"));
        List<String> runs = reqs.stream().filter(l -> l.contains("\"id\":\"same\"")).toList();
        assertEquals(2, runs.size());
        assertEquals(1, runs.stream().filter(l -> l.contains("\"coverageBucket\":\"b\"") && l.contains("\"coverageNewIds\":true")).count());
        assertEquals(1, runs.stream().filter(l -> l.contains("\"coverageBucket\":null") && !l.contains("coverageNewIds")).count());
        assertTrue(Oxpecker.availableHarnesses().containsAll(List.of("rhino1.9", "rhino2.0")));
    }

    @Test
    void aModeTheFakeDoesNotDeclareIsRefused() {
        Ensemble e = new Ensemble(List.of(new HarnessSpec("x", Ensemble.GUIDE, "debugger", "fake", bin.toString())));
        HarnessError err = assertThrows(HarnessError.class, e::start);
        assertTrue(err.getMessage().startsWith("harness 'x': Fake (" + bin + ") does not support mode 'debugger'; it declares interpreted, compiled"),
                err.getMessage());
    }
}
