package dev.oxpecker.shrink;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The statement shrinker with fake oracles (no harness). */
class SourceShrinkTest {

    @Test
    void unitsEndWhereTheBraceDepthReturnsToZeroAndCommentsAttachForward() {
        String src = "// a\nvar x = 1;\nfunction f() {\n  var s = \"}\";\n  return s;\n}\n\n// trailing";
        assertEquals(List.of(List.of("// a", "var x = 1;"), List.of("function f() {", "  var s = \"}\";", "  return s;", "}"),
                List.of("", "// trailing")), SourceShrink.splitStatements(src));
    }

    @Test
    void bracesInStringsTemplatesAndCommentsDoNotCount() {
        assertEquals(0, SourceShrink.depthDelta("var a = '{', b = \"\\\"{\", c = `{`; // {"));
        assertEquals(1, SourceShrink.depthDelta("if (x) { // }"));
    }

    @Test
    void shrinksToTheStatementsTheOracleNeeds() {
        String src = "var a = 1;\nvar b = 2;\nfunction f() {\n  var c = 3;\n  return a;\n}\nvar d = f();\n__probe('d', d);\n";
        String got = SourceShrink.shrinkSource(src, s -> s.contains("__probe('d'"));
        assertEquals("__probe('d', d);\n", got);
        String kept = SourceShrink.shrinkSource(src, s -> s.contains("return a;") && s.contains("var a"));
        assertEquals("var a = 1;\nfunction f() {\n  return a;\n}\n", kept);
    }

    @Test
    void theBudgetBoundsOracleCalls() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("var v").append(i).append(" = ").append(i).append(";\n");
        int[] calls = {0};
        SourceShrink.shrinkSource(sb.toString(), s -> { calls[0]++; return false; }, 10);
        assertEquals(10, calls[0]);
    }

    @Test
    void nothingRemovableReturnsTheSourceWithoutComments() {
        String src = "// header\nvar a = 1;\n";
        assertEquals("var a = 1;\n", SourceShrink.shrinkSource(src, s -> s.contains("var a = 1;")));
        // an oracle that always holds empties the program (the last unit goes line by line)
        assertEquals("\n", SourceShrink.shrinkSource("x;\n", s -> true));
    }
}
