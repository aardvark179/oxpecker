package dev.oxpecker.il;

import static dev.oxpecker.il.Builder.fn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The builder/analysis/lift parts of tests/il/test_shell_api.py (the generator parts wait for the strategies). */
class ShellApiTest {
    @Test void handBuiltIlThroughEveryStep() {
        Builder b = new Builder();
        int n = b.declare(b.loadInt(1));
        b.update(n, b.loadInt(2), BinaryOperator.ADD);
        assertEquals(List.of("var n1 = 1;", "n1 += 2;"), List.of(Lifter.js(b).split("\n")));
        assertTrue(Analysis.analyse(b.code).types()[n].is(Type.NUMBER));      // integer at first, number after `+= 2`
        String text = Analysis.explain(b.code);
        assertTrue(Program.dump(b.code).contains("Declare"));
        assertTrue(text.contains("v1: integer [top_var]"));
        assertEquals(text, Analysis.analyse(b.code).explain());
    }

    @Test void explainReportsTheRhinoModel() {
        Builder b = new Builder();
        try (var f = b.plainFunction(fn().named(true))) {
            int n = b.declare(b.loadInt(1));
            b.reassign(n, b.binary(n, b.loadInt(2), BinaryOperator.MUL));
        }
        String text = Analysis.explain(b.code);
        assertTrue(text.contains("activation: none (optimizer runs)") && text.contains("number-var"), text);
    }
}
