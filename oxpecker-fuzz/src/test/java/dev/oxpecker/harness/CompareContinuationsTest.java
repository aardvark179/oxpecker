package dev.oxpecker.harness;

import org.junit.jupiter.api.Test;

import static dev.oxpecker.harness.Compare.EQUAL;
import static dev.oxpecker.harness.Compare.HARD;
import static dev.oxpecker.harness.Compare.SKIP;
import static dev.oxpecker.harness.Compare.compare;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Continuation outcomes that differ by construction between the modes are skipped, not reported. */
class CompareContinuationsTest {

    @Test
    void unsupportedInBothModesIsSkipped() {
        Outcome a = Outcome.of("interpreted", "unsupported").reason("continuations").trace("\"step\" | 1").continuations("pause(\"x\")");
        Outcome b = Outcome.of("compiled", "unsupported").reason("continuations");
        assertEquals(SKIP, compare(a, b).kind);
        Outcome c = Outcome.of("compiled", "ok").value("1");
        assertEquals(HARD, compare(a, c).kind);            // one mode finished, the other could not: still a finding
    }

    /** A pause inside the program's own try/catch: the interpreter records it, the compiled mode's
     * refusal is swallowed by the catch and both modes go on to throw or finish (2026-10-27
     * campaign). Same class as both-unsupported. */
    @Test
    void compiledRefusalCaughtByTheProgramIsSkipped() {
        Outcome a = Outcome.of("interpreted", "throw").trace("\"step\" | 1").continuations("pause(\"in-try\")");
        Outcome b = Outcome.of("compiled", "throw").trace("\"step\" | 1");
        assertEquals(SKIP, compare(a, b).kind);
        Outcome c = Outcome.of("compiled", "throw").trace("\"step\" | 1").continuations("pause(\"in-try\")");
        assertEquals(EQUAL, compare(a, c).kind);
    }
}
