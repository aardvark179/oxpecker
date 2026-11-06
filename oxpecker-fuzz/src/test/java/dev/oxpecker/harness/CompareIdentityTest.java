package dev.oxpecker.harness;

import org.junit.jupiter.api.Test;

import static dev.oxpecker.harness.Compare.EQUAL;
import static dev.oxpecker.harness.Compare.HARD;
import static dev.oxpecker.harness.Compare.SKIP;
import static dev.oxpecker.harness.Compare.canonIdentity;
import static dev.oxpecker.harness.Compare.compare;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Java identity hashes in printed values are not a divergence: {@code JavaProbe@3bde59e5} differs
 * between any two runs (found by the first ten-minute campaign, 2026-10-26). */
class CompareIdentityTest {

    @Test
    void identityHashIsCanonicalised() {
        assertEquals("x dev.oxpecker.harness.JavaProbe@ y", canonIdentity("x dev.oxpecker.harness.JavaProbe@3bde59e5 y"));
        assertEquals("@0Object<Array.prototype>{}", canonIdentity("@0Object<Array.prototype>{}"));   // canon's own @N ids stay
        assertNull(canonIdentity(null));
    }

    @Test
    void outcomesDifferingOnlyByIdentityHashAreEqual() {
        Outcome a = Outcome.of("interpreted", "ok").value("dev.oxpecker.harness.JavaProbe@1a2b").trace("\"p\" | java.lang.Object@ff");
        Outcome b = Outcome.of("compiled", "ok").value("dev.oxpecker.harness.JavaProbe@c3d4").trace("\"p\" | java.lang.Object@01");
        assertEquals(EQUAL, compare(a, b).kind);
        Outcome c = Outcome.of("compiled", "ok").value("dev.oxpecker.harness.Other@c3d4").trace("\"p\" | java.lang.Object@01");
        assertEquals(HARD, compare(a, c).kind);
    }

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

    @Test
    void javaTraceIdentityHashesAreCanonicalisedToo() {
        Outcome a = Outcome.of("interpreted", "ok").javaTrace("typeOf(\"dev.oxpecker.harness.JavaProbe@ef2de\") -> \"String\"");
        Outcome b = Outcome.of("compiled", "ok").javaTrace("typeOf(\"dev.oxpecker.harness.JavaProbe@a7df\") -> \"String\"");
        assertEquals(EQUAL, compare(a, b).kind);
    }

    @Test
    void arrayHashesAndConcatenatedTextAreMasked() {
        // `J.intArray(0) + ""` prints the JVM array descriptor; `J + "x"` glues text onto the hash.
        assertEquals("\"[I@\"", canonIdentity("\"[I@6e6b878\""));
        assertEquals("[[Ljava.lang.String;@", canonIdentity("[[Ljava.lang.String;@1a2b"));
        assertEquals("dev.oxpecker.harness.JavaProbe@x", canonIdentity("dev.oxpecker.harness.JavaProbe@13bc3ddcx"));
    }
}
