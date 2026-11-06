package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CapabilityProbeTest {

    @Test
    void saveWritesWhatPythonWrote(@TempDir Path tmp) throws IOException {
        Map<String, Object> table = CapabilityProbe.load();
        assertNotNull(table, "oxpecker/environment/rhino-capabilities.json");
        Path out = tmp.resolve("caps.json");
        CapabilityProbe.save(table, out);
        assertEquals(Files.readString(CapabilityProbe.cache()), Files.readString(out));
        assertNull(CapabilityProbe.load(tmp.resolve("missing.json")));
    }

    @Test
    void tablesSkipAccessorsSymbolsAndExcludedGlobals() {
        Map<String, Object> table = Map.of(
                "protos", Map.of("Array", List.of("push/1", "length:acc", "Symbol(Symbol.iterator)/0", "x="),
                        "J", List.of("a/1"), "Empty", List.of("y:acc")),
                "statics", Map.of("Math", List.of("max/2", "PI="), "print", List.of("p/0")));
        assertEquals(Map.of("Array", List.of(new CapabilityProbe.Member("push", 1))), CapabilityProbe.methodTable(table));
        assertEquals(Map.of("Math", List.of(new CapabilityProbe.Member("max", 2))), CapabilityProbe.staticTable(table));
        assertEquals(Map.of(), CapabilityProbe.methodTable(Map.of()));
    }

    @Test
    void theSyntaxTableKeepsItsOrder() {
        assertEquals("let", CapabilityProbe.SYNTAX.keySet().iterator().next());
        assertEquals(54, CapabilityProbe.SYNTAX.size());
        assertEquals("#!x\n1", CapabilityProbe.SYNTAX.get("hashbang"));
        assertEquals("/\\u{1F600}/u.test('x')", CapabilityProbe.SYNTAX.get("regexp-unicode"));
    }

    @Test
    @Tag("slow")
    @SuppressWarnings("unchecked")
    void theProbeRunsThroughTheHarness() {
        assumeTrue(Files.exists(Oxpecker.harnessBin()), "harness not built");
        try (HarnessClient c = new HarnessClient(true).start()) {
            Map<String, Object> table = CapabilityProbe.runProbe(c);
            assertTrue(((Map<String, Object>) table.get("protos")).containsKey("Array"));
            Map<String, Map<String, Object>> syntax = (Map<String, Map<String, Object>>) table.get("syntax");
            assertEquals(CapabilityProbe.SYNTAX.keySet(), syntax.keySet());
            assertEquals(Boolean.TRUE, syntax.get("let").get("supported"));
            assertEquals("equal", syntax.get("let").get("verdict"));
            assertEquals(250L, table.get("languageVersion"));
            assertNotNull(table.get("rhino"));
            assertFalse(CapabilityProbe.methodTable(table).isEmpty());
        }
    }
}
