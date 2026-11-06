package dev.oxpecker.corpora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The corpus importers over a miniature Rhino checkout (the real one lives in ../rhino). */
class CorporaTest {

    static void write(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void frontmatter() {
        Test262.Meta m = Test262.parseFrontmatter("/*---\nincludes: [compareArray.js]\nflags:\n  - onlyStrict\n"
                + "negative:\n  phase: parse\n  type: SyntaxError\n---*/");
        assertEquals(List.of("compareArray.js"), m.includes());
        assertEquals(List.of("onlyStrict"), m.flags());
        assertEquals(List.of(), m.features());
        assertEquals(Map.of("phase", "parse", "type", "SyntaxError"), m.negative());
        assertNull(Test262.parseFrontmatter("1;").negative());
    }

    @Test
    void modeTags() {
        assertEquals(Set.of("interpreted", "debugger"), Test262.modeTags("{interpreted,debugger}"));
        assertEquals(Set.of("compiled"), Test262.modeTags("compiled"));
        assertEquals(Set.of(), Test262.modeTags("no modes"));
    }

    @Test
    void test262Cases(@TempDir Path rhino) throws IOException {
        Path t262 = rhino.resolve("tests/test262");
        write(t262, "harness/assert.js", "// assert");
        write(t262, "harness/sta.js", "// sta");
        write(t262, "harness/compareArray.js", "// cmp");
        write(t262, "test/language/a.js", "/*---\nincludes: [compareArray.js]\n---*/\na();");
        write(t262, "test/language/b_FIXTURE.js", "fixture");
        write(t262, "test/language/c.js", "/*---\nfeatures: [class]\n---*/");
        write(t262, "test/language/d.js", "/*---\nflags: [onlyStrict]\nnegative:\n  phase: parse\n  type: SyntaxError\n---*/");
        write(t262, "test/language/e.js", "/*---\nflags: [raw]\n---*/raw();");
        write(t262, "test/language/f.js", "/*---\nflags: [async]\n---*/");
        write(t262, "test/annexB/g.js", "g();");
        write(t262, "test/annexB/h.js", "h();");
        Path props = rhino.resolve("tests/testsrc/test262.properties");
        write(rhino, "tests/testsrc/test262.properties",
                "language 1/6\n    a.js compiled\n~annexB\n    h.js {interpreted,debugger}\n");

        List<Case> cases = Test262.cases(t262, props, null, null, true, false).toList();
        assertEquals(List.of("language/a.js", "language/a.js [strict]", "language/d.js [strict]", "language/e.js", "annexB/h.js",
                "annexB/h.js [strict]").stream().sorted().toList(), cases.stream().map(Case::id).sorted().toList());
        Case a = cases.stream().filter(c -> c.id().equals("language/a.js")).findFirst().orElseThrow();
        assertEquals(Map.of("rhinoFailing", "compiled", "knownDivergence", "compiled"), a.tags());
        assertTrue(a.source().startsWith(Test262.SHIM_262 + "\n// ---- include assert.js\n// assert\n// ---- include sta.js\n// sta\n"
                + "// ---- include compareArray.js\n// cmp\n// ---- language/a.js\n/*---"), a.source());
        Case d = cases.stream().filter(c -> c.id().equals("language/d.js [strict]")).findFirst().orElseThrow();
        assertTrue(d.strict() && d.source().startsWith("\"use strict\";\n"));
        assertEquals("parse:SyntaxError", d.tags().get("negative"));
        assertEquals("/*---\nflags: [raw]\n---*/raw();",
                cases.stream().filter(c -> c.id().equals("language/e.js")).findFirst().orElseThrow().source());
        Case h = cases.stream().filter(c -> c.id().equals("annexB/h.js")).findFirst().orElseThrow();
        assertEquals("debugger,interpreted", h.tags().get("knownDivergence"));

        assertEquals(List.of("annexB/h.js"), Test262.cases(t262, props, null, 1, false, true).map(Case::id).toList());
        assertEquals(List.of("language/a.js"), Test262.cases(t262, props, "language/a", 0, false, false).map(Case::id).toList());
        assertThrows(java.io.FileNotFoundException.class, () -> Test262.cases(rhino.resolve("none"), props, null, null, true, false));
    }

    @Test
    void rhinoJsTestsInlineLoads(@TempDir Path rhino) throws IOException {
        write(rhino, "tests/testsrc/jstests/a.js", "load(\"testsrc/assert.js\");\na();\n  load('missing.js');\n");
        write(rhino, "tests/testsrc/jstests/sub/b.jstest", "b();");
        write(rhino, "tests/testsrc/jstests/helpers.js", "skip");
        write(rhino, "tests/testsrc/jstests/c.txt", "not a test");
        write(rhino, "tests/testsrc/assert.js", "function assertEquals() {}");
        List<Case> cases = RhinoTests.jstests(rhino, null, null).toList();
        assertEquals(List.of("a.js", "sub/b.jstest"), cases.stream().map(Case::id).toList());
        assertEquals("// ---- load testsrc/assert.js\nfunction assertEquals() {}\na();\n// oxpecker: could not inline load('missing.js')",
                cases.get(0).source());
        assertEquals(Map.of("corpus", "rhino-jstests"), cases.get(0).tags());
        assertEquals(List.of("sub/b.jstest"), RhinoTests.jstests(rhino, "sub", 5).map(Case::id).toList());
    }

    @Test
    void rhinoMozillaShellChain(@TempDir Path rhino) throws IOException {
        Path m = rhino.resolve("tests/testsrc/tests");
        write(m, "shell.js", "// root shell");
        write(m, "ecma/shell.js", "// suite shell");
        write(m, "ecma/Array/t.js", "t();");
        write(m, "e4x/x/u.js", "skip");
        write(m, "top.js", "skip");
        List<Case> cases = RhinoTests.mozilla(rhino, null, null).toList();
        assertEquals(List.of("ecma/Array/t.js"), cases.stream().map(Case::id).toList());
        assertEquals("// ---- shell.js\n// root shell\n// ---- ecma/shell.js\n// suite shell\n// ---- ecma/Array/t.js\nt();\n\n"
                + "if (typeof jsTestDriverEnd === 'function') { try { jsTestDriverEnd(); } catch (e) { print('driver-end threw ' + e); } }",
                cases.get(0).source());
        assertEquals(Map.of("corpus", "rhino-mozilla", "suite", "ecma"), cases.get(0).tags());
    }
}
