"""Corpus minimisation (oxpecker.cmin, afl-cmin's choice): the kept entries alone must cover
what the whole corpus covers, and the choice must not depend on the order entries were found in."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from oxpecker import HARNESS_BIN
from oxpecker.cmin import choose, minimise
from oxpecker.fuzz import Corpus, fuzz
from oxpecker.harness.client import HarnessClient
from oxpecker.il.options import GenOptions

OPTIONS = GenOptions(max_statements=5, max_depth=2)
STEPS = 3


def test_choose_keeps_the_smallest_entry_per_probe_whatever_the_order():
    # (size, id, {class: bitmap}): 1 covers probes 0-1, 2 covers 1-2, 3 (big) covers 0-3, 4 duplicates 1
    sized = [(30, 3, {"A": 0b1111}), (10, 1, {"A": 0b0011}), (20, 2, {"A": 0b0110}), (10, 4, {"A": 0b0011})]
    assert choose(sized) == {1: 2, 2: 1, 3: 1}                     # 3 kept only for probe 3; 4 ties with 1, earlier wins
    assert set(choose(list(reversed(sized)))) == {4, 2, 3}         # reversed, the tie goes to 4 instead
    assert choose([(5, 9, {"A": 0b1}), (5, 8, {"B": 0b1})]) == {9: 1, 8: 1}   # classes are separate probe spaces
    assert choose([]) == {}


def _coverage_client() -> HarnessClient:
    if not HARNESS_BIN.exists():
        pytest.skip("harness not built: cd harness-java && ./gradlew fatJar")
    c = HarnessClient(quiet=True, coverage=True)
    c.start()
    if not c.info().get("coverage"):
        c.stop()
        pytest.skip("coverage agent not attached")
    return c


def _replay_coverage(corpus_dir: Path, exec_path: Path) -> int:
    """Probes covered by replaying every entry of a corpus in a fresh JVM."""
    corpus = Corpus(corpus_dir)
    c = _coverage_client()
    try:
        for i in range(len(corpus.entries)):
            lineage = corpus.replay(i, OPTIONS, STEPS)
            c.run(lineage.source, seed=1, coverage_bucket=lineage.template)
        return c.coverage_save(str(exec_path))
    finally:
        c.stop()


def _reversed_copy(src: Path, dest: Path) -> None:
    dest.mkdir()
    lines = (src / "corpus.jsonl").read_text(encoding="utf-8").splitlines()
    (dest / "corpus.jsonl").write_text("\n".join(reversed(lines)) + "\n", encoding="utf-8")


@pytest.mark.slow
def test_kept_entries_cover_what_the_corpus_covers_whatever_the_order(tmp_path: Path):
    c = _coverage_client()
    try:
        fuzz(corpus_dir=tmp_path / "found", options=OPTIONS, iterations=60, steps=STEPS, seed=3, client=c,
             findings_dir=tmp_path / "findings", shrink_budget=5, source_shrink_budget=0, quiet=True)
    finally:
        c.stop()
    entries = len(Corpus(tmp_path / "found").entries)
    assert entries >= 10

    kept = {}
    for name, src in (("forward", tmp_path / "found"), ("reversed", tmp_path / "reversed")):
        if name == "reversed":
            _reversed_copy(tmp_path / "found", src)
        c = _coverage_client()
        try:
            s = minimise(src, tmp_path / f"min-{name}", client=c, options=OPTIONS, steps=STEPS, quiet=True)
        finally:
            c.stop()
        assert not s["undecodable"]
        # the kept entries' own re-run reaches what they were chosen on, but for a few probes that
        # depend on timing or on what the JVM ran before (one-time initialisation, the budget observer)
        assert s["covered_probes"] >= s["probes_chosen"] * 0.995, s
        assert 0 < s["kept"] <= entries
        assert json.loads((tmp_path / f"min-{name}" / "cmin.json").read_text())["kept"] == s["kept"]
        # replaying the new corpus as the fuzz loop would reaches the same, within that noise
        assert _replay_coverage(tmp_path / f"min-{name}", tmp_path / f"{name}.exec") >= s["probes_chosen"] * 0.995
        kept[name] = {e.id for e in Corpus(tmp_path / f"min-{name}").entries}
    # the smallest entry per probe does not depend on the order (ties of equal size aside)
    assert len(kept["forward"] ^ kept["reversed"]) <= max(2, entries // 10)
