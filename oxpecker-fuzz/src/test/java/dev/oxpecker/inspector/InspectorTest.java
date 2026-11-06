package dev.oxpecker.inspector;

import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Strategies;
import dev.oxpecker.util.Json;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inspector export writes what the UI reads (ui/lib/types.ts): a catalogue entry per
 * generator and template, one sample line per program with trace spans, metrics per focus,
 * findings by signature (tests/test_inspector.py). The export needs the harness. */
class InspectorTest {

    @Test
    void catalogueListsEveryGeneratorAndTemplateWithSource() {
        List<Map<String, Object>> cat = Inspector.catalogue();
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> e : cat) ids.add(e.get("id"));
        assertTrue(ids.containsAll(Strategies.REGISTRY.keySet()) && ids.containsAll(Strategies.TEMPLATES.keySet()));
        Map<String, Object> gen = cat.stream().filter(e -> e.get("id").equals("while_loop")).findFirst().orElseThrow();
        assertEquals("generator", gen.get("kind"));
        assertEquals("control", gen.get("family"));
        assertTrue(((String) gen.get("sourcePath")).startsWith("oxpecker-core/src/main/java/dev/oxpecker/il/generators/"),
                (String) gen.get("sourcePath"));
        String snippet = (String) gen.get("sourceSnippet");
        assertTrue(snippet.contains("generator(\"while_loop\")"), snippet);
        assertTrue(snippet.strip().endsWith("});"), snippet);
        assertFalse(snippet.contains("do_while_loop"), snippet);
        Map<String, Object> tpl = cat.stream().filter(e -> e.get("id").equals("plain")).findFirst().orElseThrow();
        assertEquals("template", tpl.get("kind"));
        assertFalse(((String) tpl.get("description")).isEmpty());
        assertTrue(((String) tpl.get("sourcePath")).matches("oxpecker-core/src/main/java/dev/oxpecker/il/Templates\\.java:\\d+"));
        // every registration is found
        for (Map<String, Object> e : cat) assertFalse(((String) e.get("sourcePath")).isEmpty(), (String) e.get("id"));
    }

    @Test
    void generatorTraceSpansFollowTheComments() {
        String src = "// [integer]\nvar n0 = 1;\n__probe(\"step\", n0);\n// [binary]\nvar n1 = n0 + 1;\n";
        assertEquals(List.of(Map.of("gen", "integer", "lines", List.of(0, 2)), Map.of("gen", "binary", "lines", List.of(3, 4))),
                Inspector.generatorTrace(src));
    }

    @Test
    @Tag("slow")
    @SuppressWarnings("unchecked")
    void exportWritesTheRunDirectory(@TempDir Path tmp) throws IOException {
        Path out;
        try (Ensemble harness = Ensemble.fromModes(false, true).start()) {
            Inspector.ExportConfig c = new Inspector.ExportConfig();
            c.perGenerator = 1;
            c.perTemplate = 1;
            c.seed = 3;
            c.options = GenOptions.builder().maxStatements(5).build();
            c.quiet = true;
            c.client = harness;
            out = Inspector.export(tmp, c);
        }
        for (String f : List.of("catalogue.json", "metrics.json", "findings.json")) assertTrue(Files.exists(out.resolve(f)), f);
        List<Map<String, Object>> samples = Files.readString(out.resolve("samples.jsonl"), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).map(Json::parseObject).toList();
        assertFalse(samples.isEmpty(), "no samples written");
        Map<String, Object> rec = samples.get(0);
        for (String key : List.of("id", "runId", "kind", "focus", "source", "sourcePlain", "seed", "template", "enabledGenerators",
                "generatorTrace", "stats", "results", "verdict")) {
            assertTrue(rec.containsKey(key), key);
        }
        assertTrue(((Map<String, Object>) rec.get("results")).keySet().containsAll(Set.of("interpreted", "compiled")));
        assertEquals((long) ((String) rec.get("source")).getBytes(StandardCharsets.UTF_8).length,
                ((Map<String, Object>) rec.get("stats")).get("bytes"));
        Map<String, Object> metrics = Json.parseObject(Files.readString(out.resolve("metrics.json"), StandardCharsets.UTF_8));
        assertEquals((long) (Strategies.REGISTRY.size() + Strategies.TEMPLATES.size()), metrics.get("samples"));
        Map<String, Object> perFocus = (Map<String, Object>) metrics.get("perFocus");
        Map<String, Object> row = (Map<String, Object>) perFocus.get("plain");
        assertEquals("template", row.get("kind"));
        assertEquals(1L, row.get("samples"));
        assertEquals(1L, row.get("fired"));
        long fired = perFocus.values().stream().mapToLong(r -> (Long) ((Map<String, Object>) r).get("fired")).sum();
        assertTrue(fired >= 0.8 * Strategies.REGISTRY.size(), "most generators should fire in their own sample: " + fired);
    }
}
