"""Corpus minimisation (oxpecker.cmin, afl-cmin's choice): the kept entries alone must cover
what the whole corpus covers, and the choice must not depend on the order entries were found in."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from oxpecker import HARNESS_BIN
from oxpecker.cmin import minimise
from oxpecker.fuzz import Corpus, fuzz
from oxpecker.harness.client import HarnessClient
from oxpecker.il.options import GenOptions

OPTIONS = GenOptions(max_statements=5, max_depth=2)
STEPS = 3


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
        assert not s["undecodable"] and not s["excluded"]
        assert 0 < s["kept"] <= entries
        assert json.loads((tmp_path / f"min-{name}" / "cmin.json").read_text())["kept"] == s["kept"]
        # the kept entries alone, replayed in a fresh JVM, reach every probe the whole corpus reached
        assert _replay_coverage(tmp_path / f"min-{name}", tmp_path / f"{name}.exec") == s["covered_probes"]
        kept[name] = {e.id for e in Corpus(tmp_path / f"min-{name}").entries}
    # the smallest entry per probe does not depend on the order (ties of equal size aside)
    assert len(kept["forward"] ^ kept["reversed"]) <= max(2, entries // 10)
