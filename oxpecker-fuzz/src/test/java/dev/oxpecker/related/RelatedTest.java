package dev.oxpecker.related;

import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.oxpecker.related.Fingerprints.runFrames;
import static dev.oxpecker.related.Fingerprints.runMessages;
import static dev.oxpecker.related.Fingerprints.sourceTokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Labelling new findings with the regressions they probably connect to. The three-hour 1.9.2
 * campaign of 2026-10-29 recorded three known bugs as new because they surfaced with other values
 * or another symptom than their regression files; these cases mirror them. */
class RelatedTest {

    static RunResult run(Outcome interpreted, Outcome compiled) {
        Map<String, Outcome> rs = new LinkedHashMap<>();
        rs.put("interpreted", interpreted);
        rs.put("compiled", compiled);
        return new RunResult("t", rs);
    }

    static Fingerprint fp(String name, String source, Outcome interpreted, Outcome compiled) {
        RunResult r = run(interpreted != null ? interpreted : Outcome.of("interpreted", "ok").value("1"),
                compiled != null ? compiled : Outcome.of("compiled", "ok").value("1"));
        return Fingerprint.of(name, source, r, Compare.compareRun(r));
    }

    static Fingerprint fp(String name, String source) {
        return fp(name, source, null, null);
    }

    @Test
    void tokensDropGeneratedNamesCommentsStringsAndAddClasses() {
        Set<String> t = sourceTokens("// [super_property_update]\nvar o43 = { m() { super.__proto__ <<= 0; } }; var s = \"super\";\n"
                + "var ta = new Float64Array(n30); f(a, ...p20);");
        assertTrue(t.containsAll(Set.of("super", "__proto__", "<<=", "<compound-assign>", "Float64Array", "<TypedArray>", "...", "new")), t.toString());
        for (String x : List.of("o43", "n30", "p20", "a", "f", "var", "super_property_update")) assertFalse(t.contains(x), x);
        assertTrue(sourceTokens("x ||= 1").contains("<logical-assign>"));
        assertFalse(sourceTokens("x ||= 1").contains("<compound-assign>"));
        assertFalse(sourceTokens("x => x === y").contains("<compound-assign>"));
    }

    @Test
    void messagesAndFramesAreMaskedAndRhinoOnly() {
        Outcome crash = Outcome.of("compiled", "crash").engineException(Map.of(
                "clazz", "java.lang.IllegalStateException", "message", "bad local variable type: 0 at index: 7",
                "stack", "org.mozilla.javascript.optimizer.Codegen.x(Codegen.java:12)\n"
                        + "org.mozilla.javascript.gen.script_1._c_script_0(t.js:4)\n"
                        + "org.mozilla.javascript.Context.compile(Context.java:9)\n"));
        Outcome thrown = Outcome.of("interpreted", "throw").thrown(Map.of("name", "TypeError", "message", "Cannot convert BigInt to a number"));
        RunResult r = run(thrown, crash);
        assertEquals(Set.of("java.lang.IllegalStateException: bad local variable type: # at index: #",
                "TypeError: Cannot convert BigInt to a number"), runMessages(r));
        assertEquals(Set.of("org.mozilla.javascript.optimizer.Codegen.x", "org.mozilla.javascript.Context.compile"), runFrames(r));
    }

    @Test
    void sharedMessageRelatesAcrossSymptoms() {
        // the regression shows the bug as a thrown value; the finding as a missing trace step
        Outcome bigint = Outcome.of("compiled", "throw").thrown(Map.of("name", "TypeError", "message", "Cannot convert BigInt to a number"));
        Fingerprint regression = fp("compiled-bigint-local.js", "function f() { var b = -1n; return b; } f();", null, bigint);
        Fingerprint other = fp("compiled-increment-overflow.js", "var n = 2147483647; n++;", null,
                Outcome.of("compiled", "ok").value("-2147483648"));
        Fingerprint finding = fp("finding", "var f32 = function () { v36 = 7n; v38 = -v36; }; f32(); Object.defineProperty(u, 'x', {});",
                Outcome.of("interpreted", "throw").trace("\"step\" | 1")
                        .thrown(Map.of("name", "TypeError", "message", "Expected argument of type object")),
                bigint);
        List<Related> related = new KnownIndex(List.of(regression, other)).related(finding);
        assertEquals(List.of("compiled-bigint-local.js"), related.stream().map(Related::name).toList());
        assertTrue(related.get(0).shared().get(0).contains("message"));
    }

    @Test
    void rareConstructsOutrankCommonOnes() {
        Fingerprint wrongReceiver = fp("super-compound-assign.js", "var t = { __proto__: new Int8Array(2), m() { super.length += 1; } };");
        Fingerprint writeback = fp("super-logical-assign.js", "var o = { __proto__: [5], m() { super.__proto__ ||= 5; } };");
        Fingerprint loop = fp("loop.js", "for (var i in o) {}");
        KnownIndex index = new KnownIndex(List.of(wrongReceiver, writeback, loop));
        Fingerprint finding = fp("finding", "var o31 = new Float64Array(47); for (var i in o31) {}\n"
                + "var o43 = { __proto__: o31, m() { super.__proto__ <<= 0; } }; o43.m();");
        assertEquals("super-compound-assign.js", index.related(finding).get(0).name());
        assertFalse(index.related(finding).stream().map(Related::name).toList().contains("loop.js"));
    }
}
