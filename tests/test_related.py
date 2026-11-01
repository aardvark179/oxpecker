"""Labelling new findings with the regressions they probably connect to (oxpecker.related). The
three-hour 1.9.2 campaign of 2026-10-29 recorded three known bugs as new because they surfaced
with other values or another symptom than their regression files; these cases mirror them."""

from oxpecker.harness.client import Outcome, RunResult
from oxpecker.harness.compare import compare_run
from oxpecker.related import Fingerprint, KnownIndex, run_frames, run_messages, source_tokens


def _run(interpreted: Outcome, compiled: Outcome) -> RunResult:
    return RunResult(id="t", results={"interpreted": interpreted, "compiled": compiled})


def _fp(name, source, interpreted=None, compiled=None):
    run = _run(interpreted or Outcome(mode="interpreted", status="ok", value="1"),
               compiled or Outcome(mode="compiled", status="ok", value="1"))
    return Fingerprint.of(name, source, run, compare_run(run))


def test_tokens_drop_generated_names_comments_strings_and_add_classes():
    t = source_tokens('// [super_property_update]\nvar o43 = { m() { super.__proto__ <<= 0; } }; var s = "super";\n'
                      'var ta = new Float64Array(n30); f(a, ...p20);')
    assert {"super", "__proto__", "<<=", "<compound-assign>", "Float64Array", "<TypedArray>", "...", "new"} <= t
    assert not {"o43", "n30", "p20", "a", "f", "var", "super_property_update"} & t
    assert "<logical-assign>" in source_tokens("x ||= 1") and "<compound-assign>" not in source_tokens("x ||= 1")
    assert "<compound-assign>" not in source_tokens("x => x === y")


def test_messages_and_frames_are_masked_and_rhino_only():
    crash = Outcome(mode="compiled", status="crash", engineException={
        "clazz": "java.lang.IllegalStateException", "message": "bad local variable type: 0 at index: 7",
        "stack": "org.mozilla.javascript.optimizer.Codegen.x(Codegen.java:12)\n"
                 "org.mozilla.javascript.gen.script_1._c_script_0(t.js:4)\n"
                 "org.mozilla.javascript.Context.compile(Context.java:9)\n"})
    thrown = Outcome(mode="interpreted", status="throw", thrown={"name": "TypeError", "message": "Cannot convert BigInt to a number"})
    run = _run(thrown, crash)
    assert run_messages(run) == {"java.lang.IllegalStateException: bad local variable type: # at index: #",
                                 "TypeError: Cannot convert BigInt to a number"}
    assert run_frames(run) == {"org.mozilla.javascript.optimizer.Codegen.x", "org.mozilla.javascript.Context.compile"}


def test_shared_message_relates_across_symptoms():
    # the regression shows the bug as a thrown value; the finding as a missing trace step
    bigint = Outcome(mode="compiled", status="throw", thrown={"name": "TypeError", "message": "Cannot convert BigInt to a number"})
    regression = _fp("compiled-bigint-local.js", "function f() { var b = -1n; return b; } f();", compiled=bigint)
    other = _fp("compiled-increment-overflow.js", "var n = 2147483647; n++;",
                compiled=Outcome(mode="compiled", status="ok", value="-2147483648"))
    finding = _fp("finding", "var f32 = function () { v36 = 7n; v38 = -v36; }; f32(); Object.defineProperty(u, 'x', {});",
                  interpreted=Outcome(mode="interpreted", status="throw", trace=['"step" | 1'],
                                      thrown={"name": "TypeError", "message": "Expected argument of type object"}),
                  compiled=bigint)
    related = KnownIndex(entries=[regression, other]).related(finding)
    assert [r.name for r in related] == ["compiled-bigint-local.js"]
    assert "message" in related[0].shared[0]


def test_rare_constructs_outrank_common_ones():
    wrong_receiver = _fp("super-compound-assign.js", "var t = { __proto__: new Int8Array(2), m() { super.length += 1; } };")
    writeback = _fp("super-logical-assign.js", "var o = { __proto__: [5], m() { super.__proto__ ||= 5; } };")
    loop = _fp("loop.js", "for (var i in o) {}")
    index = KnownIndex(entries=[wrong_receiver, writeback, loop])
    finding = _fp("finding", "var o31 = new Float64Array(47); for (var i in o31) {}\n"
                             "var o43 = { __proto__: o31, m() { super.__proto__ <<= 0; } }; o43.m();")
    assert index.related(finding)[0].name == "super-compound-assign.js"
    assert "loop.js" not in [r.name for r in index.related(finding)]
