package dev.oxpecker.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.oxpecker.Oxpecker;
import dev.oxpecker.corpora.Test262;
import dev.oxpecker.related.Fingerprint;
import dev.oxpecker.related.KnownIndex;
import dev.oxpecker.related.Related;
import dev.oxpecker.shrink.SourceShrink;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Port parity: what the Python port's scripts/parity/export_harness.py (removed with it)
 * dumped from the Python implementation for fixed inputs, reproduced by the Java port.
 */
class HarnessParityTest {
    static JsonObject P;

    @BeforeAll
    static void load() throws IOException {
        try (var in = HarnessParityTest.class.getResourceAsStream("/parity/harness.json")) {
            assertNotNull(in, "parity/harness.json (recorded from the Python port; see git history)");
            P = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static String s(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) out.add(s(x));
        return out;
    }

    static void assertVerdict(JsonObject want, Verdict got, String where) {
        assertEquals(s(want.get("kind")), got.kind, where + " kind");
        assertEquals(s(want.get("pretty")), got.pretty(), where + " pretty");
        assertEquals(s(want.get("signature")), got.signature(), where + " signature");
        assertEquals(s(want.get("coarse")), got.coarseSignature(), where + " coarse");
        assertEquals(want.get("json"), got.toJson(), where + " to_json");
        JsonObject pairs = want.getAsJsonObject("pairs");
        if (pairs == null) return;
        assertEquals(pairs.keySet(), got.pairs.keySet(), where + " pairs");
        for (String n : pairs.keySet()) assertVerdict(pairs.getAsJsonObject(n), got.pairs.get(n), where + " pair " + n);
    }

    static Fingerprint fingerprintOf(JsonObject r) {
        RunResult run = RunResult.fromJson(r.getAsJsonObject("run"));
        String src = s(r.get("source"));
        return Fingerprint.of(s(r.get("name")), src == null ? "" : src, run, Compare.compareRun(run));
    }

    @TestFactory
    Stream<DynamicTest> runs() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonElement e : P.getAsJsonArray("runs")) {
            JsonObject r = e.getAsJsonObject();
            String name = s(r.get("name"));
            out.add(DynamicTest.dynamicTest(name, () -> {
                JsonObject rj = r.getAsJsonObject("run");
                RunResult run = RunResult.fromJson(rj);
                assertEquals(rj, run.toJson(), "RunResult round trip");
                JsonObject vs = r.getAsJsonObject("verdicts");
                assertVerdict(vs.getAsJsonObject("default"), Compare.compareRun(run), "compare_run");
                assertVerdict(vs.getAsJsonObject("strictJavaTypes"), Compare.compareRun(run, null, true, false), "strict_java_types");
                assertVerdict(vs.getAsJsonObject("strictMessages"), Compare.compareRun(run, null, false, true), "strict_messages");
                assertVerdict(vs.getAsJsonObject("referenceCompiled"), Compare.compareRun(run, "compiled", false, false), "reference=compiled");
                for (JsonElement pe : r.getAsJsonArray("pairs")) {
                    JsonObject p = pe.getAsJsonObject();
                    Outcome a = run.results().get(s(p.get("a"))), b = run.results().get(s(p.get("b")));
                    String w = "compare(" + s(p.get("a")) + ", " + s(p.get("b")) + ")";
                    assertVerdict(p.getAsJsonObject("verdict"), Compare.compare(a, b), w);
                    assertVerdict(p.getAsJsonObject("strictJavaTypes"), Compare.compare(a, b, true, false), w + " strict_java_types");
                    assertVerdict(p.getAsJsonObject("strictMessages"), Compare.compare(a, b, false, true), w + " strict_messages");
                }
                JsonObject fw = r.getAsJsonObject("fingerprint");
                Fingerprint fp = fingerprintOf(r);
                assertEquals(s(fw.get("signature")), fp.signature());
                assertEquals(s(fw.get("coarse")), fp.coarse());
                assertEquals(strings(fw.get("messages")), Py.sorted(fp.messages()), "messages");
                assertEquals(strings(fw.get("frames")), Py.sorted(fp.frames()), "frames");
                assertEquals(strings(fw.get("tokens")), Py.sorted(fp.tokens()), "tokens");
            }));
        }
        return out.stream();
    }

    /** The Java client against the same harness gives the outcomes (ms aside) and verdicts the
     * Python client recorded. */
    @Test
    @org.junit.jupiter.api.Tag("slow")
    void theJavaClientReproducesThePythonRuns() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Oxpecker.harnessLibs("rhino1.9").resolve("oxpecker-harness-all.jar")),
                "rhino1.9 harness not built");
        try (HarnessClient c = new HarnessClient(Oxpecker.harnessBin("rhino1.9"), null, true, false).start()) {
            int n = 0;
            for (JsonElement e : P.getAsJsonArray("runs")) {
                JsonObject r = e.getAsJsonObject();
                String name = s(r.get("name"));
                if (!name.startsWith("rhino1.9/")) continue;
                RunResult want = RunResult.fromJson(r.getAsJsonObject("run"));
                RunResult got = c.run(s(r.get("source")), RunOptions.DEFAULT.withSourceName(Path.of(name).getFileName().toString())
                        .withId(want.id()));
                for (String m : want.results().keySet()) {
                    JsonObject a = want.results().get(m).toJson(), b = got.results().get(m).toJson();
                    a.remove("ms");
                    b.remove("ms");
                    if (a.has("engineException") && a.get("engineException").isJsonObject()) {   // stacks carry generated class names
                        a.getAsJsonObject("engineException").remove("stack");
                        b.getAsJsonObject("engineException").remove("stack");
                    }
                    assertEquals(a, b, name + " " + m);
                }
                assertEquals(s(r.getAsJsonObject("verdicts").getAsJsonObject("default").get("signature")),
                        Compare.compareRun(got).signature(), name);
                n++;
            }
            assertEquals(46, n);
        }
    }

    @Test
    void syntheticVerdicts() {
        for (JsonElement e : P.getAsJsonArray("verdicts")) {
            JsonObject w = e.getAsJsonObject();
            Verdict v = new Verdict(s(w.get("kind")), strings(w.get("reasons")), strings(w.get("javaTypeDiff")));
            v.pairClass = s(w.get("pairClass"));
            String where = w.get("reasons") + " " + w.get("javaTypeDiff") + " " + v.pairClass + " " + v.kind;
            assertEquals(s(w.get("signature")), v.signature(), where);
            assertEquals(s(w.get("coarse")), v.coarseSignature(), where);
            assertEquals(s(w.get("pretty")), v.pretty(), where);
        }
    }

    @Test
    void canonIdentity() {
        for (JsonElement e : P.getAsJsonArray("canon")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(s(a.get(1)), Compare.canonIdentity(s(a.get(0))));
        }
    }

    @Test
    void sourceTokens() {
        for (JsonElement e : P.getAsJsonArray("tokens")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(strings(a.get(1)), Py.sorted(dev.oxpecker.related.Fingerprints.sourceTokens(s(a.get(0)))));
        }
    }

    /** Python breaks ties among equally rare tokens in set order, so the token list after a
     * "source" score is compared up to the score only. */
    static List<String> comparable(List<String> shared) {
        List<String> out = new ArrayList<>();
        for (String x : shared) out.add(x.startsWith("source ") ? x.substring(0, x.indexOf(" (")) : x);
        return out;
    }

    @Test
    void related() throws IOException {
        JsonObject rel = P.getAsJsonArray("related").get(0).getAsJsonObject();
        Map<String, JsonObject> byName = new HashMap<>();
        for (JsonElement e : P.getAsJsonArray("runs")) byName.put(s(e.getAsJsonObject().get("name")), e.getAsJsonObject());
        KnownIndex index = new KnownIndex();
        for (String n : strings(rel.get("index"))) {
            JsonObject r = byName.get(n);
            RunResult run = RunResult.fromJson(r.getAsJsonObject("run"));
            Verdict v = Compare.compareRun(run);
            index.signatures.add(v.signature());
            index.entries.add(Fingerprint.of(Path.of(n).getFileName().toString(), s(r.get("source")), run, v));
        }
        Path master = Oxpecker.root().resolve("tests/regressions/master-only");
        try (Stream<Path> files = Files.list(master)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".js")).sorted(Py.PATH_ORDER).toList()) {
                index.entries.add(Fingerprint.of("master-only/" + p.getFileName(), Files.readString(p), null, null));
            }
        }
        index.reindex();
        assertEquals(strings(rel.get("signatures")), Py.sorted(index.signatures));
        for (JsonElement qe : rel.getAsJsonArray("queries")) {
            JsonObject q = qe.getAsJsonObject();
            String name = s(q.get("name"));
            Fingerprint fp = fingerprintOf(byName.get(name));
            check(q.getAsJsonArray("related"), index.related(fp), name, true);
            check(q.getAsJsonArray("relatedLoose"), index.related(fp, 10, 0.1), name + " (loose)", false);
        }
    }

    private static void check(JsonArray want, List<Related> got, String where, boolean str) {
        assertEquals(want.size(), got.size(), where + ": " + want + " vs " + got);
        for (int i = 0; i < want.size(); i++) {
            JsonArray w = want.get(i).getAsJsonArray();
            Related g = got.get(i);
            assertEquals(s(w.get(0)), g.name(), where);
            assertEquals(w.get(1).getAsDouble(), g.score(), where);
            assertEquals(comparable(strings(w.get(2))), comparable(g.shared()), where);
            if (str && !s(w.get(3)).contains("source ")) assertEquals(s(w.get(3)), g.toString(), where);
        }
    }

    @Test
    void shrink() {
        for (JsonElement e : P.getAsJsonArray("shrink")) {
            JsonObject c = e.getAsJsonObject();
            String src = s(c.get("source")), needle = s(c.get("needle"));
            int[] calls = {0};
            String got = SourceShrink.shrinkSource(src, x -> { calls[0]++; return x.contains(needle); }, c.get("budget").getAsInt());
            String where = Py.head(src, 60) + " budget " + c.get("budget");
            assertEquals(s(c.get("result")), got, where);
            assertEquals(c.get("calls").getAsInt(), calls[0], where + " oracle calls");
            assertEquals(c.get("units"), Json.toTree(SourceShrink.splitStatements(src)), where + " units");
        }
    }

    @Test
    void test262Metadata(@TempDir Path tmp) throws IOException {
        for (JsonElement e : P.getAsJsonArray("frontmatter")) {
            JsonArray a = e.getAsJsonArray();
            JsonObject w = a.get(1).getAsJsonObject();
            Test262.Meta m = Test262.parseFrontmatter(s(a.get(0)));
            assertEquals(strings(w.get("includes")), m.includes(), s(a.get(0)));
            assertEquals(strings(w.get("flags")), m.flags(), s(a.get(0)));
            assertEquals(strings(w.get("features")), m.features(), s(a.get(0)));
            assertEquals(w.get("negative"), Json.toTree(m.negative()), s(a.get(0)));
        }
        JsonObject props = P.getAsJsonObject("properties");
        Path f = tmp.resolve("test262.properties");
        Files.writeString(f, s(props.get("text")));
        Test262.Properties p = Test262.parseProperties(f);
        assertEquals(strings(props.get("excluded")), Py.sorted(p.excluded()));
        assertEquals(props.get("failing"), Json.toTree(p.failing()));
        Map<String, List<String>> known = new java.util.LinkedHashMap<>();
        Test262.knownDivergences(f).forEach((k, v) -> known.put(k, Py.sorted(v)));
        assertEquals(props.get("known"), Json.toTree(known));
        for (JsonElement e : P.getAsJsonArray("modeTags")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(strings(a.get(1)), Py.sorted(Test262.modeTags(s(a.get(0)))));
        }
    }

    @Test
    void capabilityTables() {
        Map<String, Object> table = CapabilityProbe.load();
        assertNotNull(table);
        assertEquals(P.get("methodTable"), members(CapabilityProbe.methodTable(table)));
        assertEquals(P.get("staticTable"), members(CapabilityProbe.staticTable(table)));
        assertEquals(P.get("syntax"), Json.toTree(CapabilityProbe.SYNTAX));
        assertEquals(List.copyOf(P.getAsJsonObject("syntax").keySet()), List.copyOf(CapabilityProbe.SYNTAX.keySet()));
        assertEquals(strings(P.get("excludedGlobals")), Py.sorted(CapabilityProbe.EXCLUDED_GLOBALS));
    }

    static JsonElement members(Map<String, List<CapabilityProbe.Member>> t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        t.forEach((k, v) -> m.put(k, v.stream().map(x -> List.of(x.name(), x.arity())).toList()));
        return Json.toTree(m);
    }

    @Test
    void pythonFormatting() {
        for (JsonElement e : P.getAsJsonArray("repr")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(s(a.get(1)), Py.repr(Json.toJava(a.get(0))), a.get(0).toString());
        }
        for (JsonElement e : P.getAsJsonArray("floats")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(s(a.get(1)), Py.floatRepr(a.get(0).getAsDouble()));
        }
        JsonArray d = P.getAsJsonArray("dumps");
        assertEquals(s(d.get(1)), Json.pyDumps(d.get(0), 1, true));
        for (JsonElement e : P.getAsJsonArray("splitlines")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(strings(a.get(1)), Py.splitlines(s(a.get(0))), Objects.toString(a.get(0)));
        }
    }
}
