"""One harness process per mode: the guide (reference, sole coverage source) and comparators."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from oxpecker import DEFAULT_HARNESS, HARNESS_BIN, harness_bin, harness_libs
from oxpecker.fuzz import Corpus, check_guide
from oxpecker.harness.client import HarnessClient, HarnessError, HarnessInfo, Outcome, RunResult, describe_harness
from oxpecker.harness.compare import EQUAL, HARD, SKIP, compare_run
from oxpecker.harness.ensemble import COMPARATOR, GUIDE, Ensemble, HarnessSpec, parse_target, specs_for

REGRESSIONS = Path(__file__).parent / "regressions"
AGENT = harness_libs(DEFAULT_HARNESS) / "jacocoagent.jar"
needs_harness = pytest.mark.skipif(not HARNESS_BIN.exists(), reason="harness not built")


# ------------------------------------------------------------------------------- without a JVM
def test_specs_put_the_guide_first_and_the_rest_as_comparators():
    specs = specs_for(("interpreted", "compiled", "debugger"), guide="compiled")
    assert [(s.name, s.role) for s in specs] == [("compiled", GUIDE), ("interpreted", COMPARATOR),
                                                 ("debugger", COMPARATOR)]
    with pytest.raises(ValueError):
        specs_for(("interpreted",), guide="compiled")


def test_each_mode_can_name_its_harness():
    other = "rhino1.9" if DEFAULT_HARNESS != "rhino1.9" else "rhino2.0"
    assert parse_target("interpreted") == (DEFAULT_HARNESS, "interpreted")
    assert parse_target(f"{other}:compiled") == (other, "compiled")
    specs = specs_for(["interpreted", f"{other}:interpreted"], guide=f"{DEFAULT_HARNESS}:interpreted")
    assert [(s.name, s.role, s.harness, s.mode) for s in specs] == [
        ("interpreted", GUIDE, DEFAULT_HARNESS, "interpreted"), (f"{other}:interpreted", COMPARATOR, other, "interpreted")]
    assert specs[1].harness_bin == str(harness_bin(other))
    assert [s.name for s in specs_for(["compiled"], f"{other}:interpreted", include_guide=True)] == \
        [f"{other}:interpreted", "compiled"]
    with pytest.raises(ValueError, match="unknown harness 'rhino0.1'.*rhino1.9, rhino2.0"):
        specs_for(["rhino0.1:interpreted"], "rhino0.1:interpreted")


def test_an_ensemble_needs_exactly_one_guide():
    with pytest.raises(ValueError):
        Ensemble([HarnessSpec("a", COMPARATOR, "interpreted")])
    with pytest.raises(ValueError):
        Ensemble([HarnessSpec("a", GUIDE, "interpreted"), HarnessSpec("b", GUIDE, "compiled")])


def test_only_the_guide_client_gets_coverage():
    e = Ensemble.from_modes(coverage=True)
    assert {n: c.coverage for n, c in e.clients.items()} == {"interpreted": True, "compiled": False}


def test_oracle_role_is_reserved():
    with pytest.raises(NotImplementedError):
        HarnessSpec("v8", "oracle", "interpreted")


def test_harness_info_must_declare_name_and_modes():
    d = HarnessInfo.from_json({"protocol": 1, "name": "Rhino", "version": "2.0", "modes": ["interpreted"],
                               "capabilities": ["javaTypes"]})
    assert (d.label, d.modes, d.mode, d.capabilities) == ("Rhino 2.0", ("interpreted",), None, ("javaTypes",))
    with pytest.raises(HarnessError, match="name"):
        HarnessInfo.from_json({"protocol": 1, "rhino": "2.0", "modes": ["interpreted"]})


def _run(guide: str, **outcomes: Outcome) -> RunResult:
    for name, o in outcomes.items():
        o.harness = name
    return RunResult("x", dict(outcomes), guide=guide)


def test_the_verdict_reads_the_same_whichever_mode_is_the_guide():
    i = Outcome(mode="interpreted", status="ok", value="1")
    c = Outcome(mode="compiled", status="ok", value="2")
    by_interpreted = compare_run(_run("interpreted", interpreted=i, compiled=c))
    by_compiled = compare_run(_run("compiled", compiled=c, interpreted=i))
    assert by_interpreted.kind == by_compiled.kind == HARD
    assert by_interpreted.signature() == by_compiled.signature()
    assert by_interpreted.against == "compiled" and by_compiled.against == "interpreted"


def test_pairs_and_pair_class():
    a = Outcome(mode="interpreted", status="ok", value="1", engine="rhino 2.0")
    b = Outcome(mode="compiled", status="ok", value="1", engine="rhino 2.0")
    c = Outcome(mode="interpreted", status="ok", value="2", engine="rhino 1.9.2")
    v = compare_run(_run("new", new=a, compiled=b, old=c))
    assert {n: p.kind for n, p in v.pairs.items()} == {"compiled": EQUAL, "old": HARD}
    assert v.against == "old" and v.pair_class == "engine:rhino 1.9.2|rhino 2.0"
    assert v.signature().startswith("hard@engine:rhino 1.9.2|rhino 2.0:")
    assert v.pairs["compiled"].pair_class == "mode"


def test_sides_from_different_engines_are_labelled_by_harness():
    a = Outcome(mode="interpreted", status="ok", value="1", engine="Rhino2.0 2.0")
    b = Outcome(mode="interpreted", status="crash", engine="Rhino1.9 1.9.2",
                engineException={"clazz": "java.lang.NullPointerException", "message": "x"})
    v = compare_run(_run("new", new=a, old=b))
    assert v.reasons == ["old crashed: java.lang.NullPointerException: x"]
    assert v.coarse_signature().endswith(":old crashed: java.lang.NullPointerException")


def test_the_same_mode_pausing_in_two_engines_is_compared_not_skipped():
    a = Outcome(mode="interpreted", status="ok", value="3", continuations=["pause(1)", "end"], engine="Rhino2.0 2.0")
    b = Outcome(mode="interpreted", status="ok", value="3", continuations=["pause(1)", "end"], engine="Rhino1.9 1.9.2")
    assert compare_run(_run("new", new=a, old=b)).kind == EQUAL
    b.continuations = ["pause(1)", "abandoned"]
    assert compare_run(_run("new", new=a, old=b)).kind == HARD


def test_fields_outside_a_harness_capabilities_are_not_compared():
    a = Outcome(mode="interpreted", status="ok", value="1", javaTrace=["x"], valueJavaTypes="Integer")
    b = Outcome(mode="interpreted", status="ok", value="1", engine="other", capabilities=[])
    assert compare_run(_run("a", a=a, b=b)).kind == EQUAL
    b.capabilities = None   # all fields
    assert compare_run(_run("a", a=a, b=b)).kind == HARD


def test_a_guide_alone_is_skipped():
    assert compare_run(_run("interpreted", interpreted=Outcome(mode="interpreted", status="ok"))).kind == SKIP


def test_resuming_coverage_of_another_guide_is_refused(tmp_path: Path):
    corpus = Corpus(tmp_path)
    interp = {"guide": "interpreted", "harnesses": [{"name": "interpreted", "role": GUIDE, "mode": "interpreted",
                                                     "engine": "rhino 2.0"}]}
    compiled = {"guide": "compiled", "harnesses": [{"name": "compiled", "role": GUIDE, "mode": "compiled",
                                                    "engine": "rhino 2.0"}]}
    check_guide(corpus, interp)                       # no record yet: adopted
    corpus.exec.write_bytes(b"exec")
    check_guide(corpus, interp)                       # same guide: fine
    with pytest.raises(RuntimeError, match="mode changed"):
        check_guide(corpus, compiled)
    assert corpus.exec.exists()
    check_guide(corpus, compiled, reset_coverage=True)
    assert not corpus.exec.exists()
    assert json.loads(corpus.harnesses.read_text())["guide"] == "compiled"


# ------------------------------------------------------------------------------- against the harness
@needs_harness
def test_the_harness_declares_its_name_and_modes():
    d = describe_harness()
    assert d.name in ("Rhino1.9", "Rhino2.0") and {"interpreted", "compiled", "debugger"} <= set(d.modes) and d.mode is None


@needs_harness
def test_a_mode_the_harness_does_not_declare_is_refused_before_starting():
    e = Ensemble([HarnessSpec("x", GUIDE, "bytecode-v2")])
    with pytest.raises(HarnessError, match="does not support mode 'bytecode-v2'; it declares .*interpreted"):
        e.start()
    assert all(c.proc is None for c in e.clients.values())


@needs_harness
def test_a_pinned_harness_refuses_other_modes():
    with HarnessClient(quiet=True, mode="compiled") as c:
        info = c.info()
        assert info["mode"] == "compiled" and info["protocol"] == 1 and "javaTypes" in info["capabilities"]
        assert list(c.run("1").results) == ["compiled"]
        with pytest.raises(HarnessError, match="pinned"):
            c.run("1", modes=("interpreted",))


@needs_harness
def test_results_are_keyed_by_harness_and_comparators_can_be_left_out():
    with Ensemble.from_modes(("interpreted", "compiled", "debugger")) as e:
        run = e.run("var x = 'a'; x + 'b'")
        assert list(run.results) == ["interpreted", "compiled", "debugger"] and run.guide == "interpreted"
        assert run.results["compiled"].role == COMPARATOR and run.results["compiled"].compiledActually is True
        assert run.results["compiled"].engine.startswith("Rhino") and e.declared["compiled"].mode == "compiled"
        assert compare_run(run).kind == EQUAL
        assert list(e.run("1", comparators=()).results) == ["interpreted"]
        assert list(e.run("1", comparators=["debugger"]).results) == ["interpreted", "debugger"]


@needs_harness
@pytest.mark.parametrize("path", sorted(REGRESSIONS.glob("*.js"))[:6], ids=lambda p: p.name)
def test_a_compiled_guide_signs_findings_like_an_interpreted_one(harness, path: Path):
    src = path.read_text()
    with Ensemble.from_modes(guide="compiled") as e:
        assert compare_run(e.run(src, source_name=path.name)).signature() == \
            compare_run(harness.run(src, source_name=path.name)).signature()


@pytest.mark.skipif(not (HARNESS_BIN.exists() and AGENT.exists()), reason="harness/agent not built")
def test_coverage_comes_from_the_guide_alone():
    with Ensemble.from_modes(guide="compiled", coverage=True) as e:
        if not e.info().get("coverage"):
            pytest.skip("coverage agent not attached")
        assert e.info()["harnesses"]["interpreted"]["coverage"] is False
        run = e.run("var s = [1, 2].map(function (x) { return x * 2; }).join();", coverage_new_ids=True)
        assert run.results["compiled"].newProbes and run.results["compiled"].newProbeIds
        assert run.results["interpreted"].newProbes is None
        assert {b for b in e.coverage_report()["buckets"] if "|" in b} == {"*|compiled"}


def _built(name: str) -> bool:
    return (harness_libs(name) / "oxpecker-harness-all.jar").exists()


@pytest.mark.skipif(not (_built("rhino1.9") and _built("rhino2.0")), reason="both Rhino harnesses must be built")
def test_two_rhino_versions_side_by_side():
    with Ensemble(specs_for(["rhino2.0:interpreted", "rhino1.9:interpreted"], "rhino2.0:interpreted")) as e:
        names = {s.harness: s.name for s in e.specs}
        assert {n: d.name for n, d in e.declared.items()} == {names["rhino2.0"]: "Rhino2.0", names["rhino1.9"]: "Rhino1.9"}
        run = e.run("var a = [3, 1, 2].sort(); a.join() + typeof globalThis")
        v = compare_run(run)
        assert v.kind == EQUAL and v.pairs[names["rhino1.9"]].pair_class.startswith("engine:Rhino1.9 1.9.2")
        assert run.results[names["rhino1.9"]].capabilities == ["javaTypes", "javaTrace", "continuations"]
        assert run.results[names["rhino2.0"]].capabilities == ["javaTypes", "javaTrace", "continuations"]
        paused = e.run("function f() { var a = __pause(1); return a + __pause(2); } f();")
        assert paused.results[names["rhino2.0"]].continuations == ["pause(1)", "pause(2)", "end"]
        assert compare_run(paused).kind == EQUAL
