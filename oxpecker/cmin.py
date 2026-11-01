"""Corpus minimisation, as afl-cmin does it: replay every entry of a corpus once against the
current Rhino and harness, and keep, for every coverage probe any entry reaches, the smallest
entry that reaches it. The union of those entries covers everything the whole corpus covers.

The choice does not depend on the order the entries were found in. Keeping "the entries that add
coverage" while replaying in the original order would keep nearly all of them: each one was added
because it added coverage at that point. Size is the length of the entry's program text.

The per-probe bookkeeping lives in the harness JVM (Coverage.cminResult): a run reaches tens of
thousands of probes, too many to ship to Python for every entry. A JVM restart (a program the
watchdog had to kill) loses that state, so the pass starts over without the entry that caused it.
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

from .fuzz import Corpus
from .il.options import GenOptions

MAX_PASSES = 5


def minimise(source_dir: Path, dest_dir: Path, *, client, options: GenOptions = GenOptions(), steps: int = 5,
             language_version: int = 200, quiet: bool = False) -> dict[str, Any]:
    """Writes the minimised corpus (corpus.jsonl, coverage.exec, cmin.json) to `dest_dir`, which
    must not hold a corpus yet. `client` is a coverage-enabled HarnessClient in a fresh JVM."""
    src = Corpus(source_dir)
    dest_dir = Path(dest_dir)
    if (dest_dir / "corpus.jsonl").exists():
        raise FileExistsError(f"{dest_dir} already holds a corpus")
    t0 = time.time()
    undecodable: list[int] = []
    sources: dict[int, tuple[str, str]] = {}      # entry id -> (program text, template), once rebuilt
    replay_ms: dict[int, float] = {}              # entry id -> time to rebuild its program from its choices
    excluded: list[int] = []                      # entries whose run restarted the JVM
    for attempt in range(1, MAX_PASSES + 1):
        restarts_before = client.restarts
        culprit = None
        # Rebuild and run each entry in turn (not all rebuilt first): rebuilding a deep lineage is
        # the slow part, and progress should show from the first entry.
        for n, (i, e) in enumerate(enumerate(src.entries), 1):
            if e.id in excluded or e.id in undecodable:
                continue
            if e.id not in sources:
                t = time.perf_counter()
                lineage = src.replay(i, options, steps)
                replay_ms[e.id] = (time.perf_counter() - t) * 1000
                if lineage is None:
                    undecodable.append(e.id)
                    continue
                sources[e.id] = (lineage.source, lineage.template)
            source, template = sources[e.id]
            client.run(source, language_version=language_version, seed=1, coverage_bucket=template,
                       cmin_key=str(e.id), cmin_size=len(source))
            if client.restarts != restarts_before:
                culprit = e.id
                break
            if not quiet and n % 250 == 0:
                print(f"  {n}/{len(src.entries)} ({time.time() - t0:.0f}s; rebuilding "
                      f"{sum(replay_ms.values()) / 1000:.0f}s so far)", file=sys.stderr)
        if culprit is None:
            break
        excluded.append(culprit)
        if not quiet:      # the client already restarted the JVM: the next pass starts clean
            print(f"  entry {culprit} restarted the JVM; pass {attempt + 1} without it", file=sys.stderr)
    else:
        raise RuntimeError(f"cmin: the JVM restarted in {MAX_PASSES} passes (excluded {excluded})")
    wins = {int(k): v for k, v in client.cmin_result().items()}
    dest = Corpus(dest_dir)
    for e in src.entries:
        if e.id in wins:
            dest.add(e)
    covered = client.coverage_save(str(dest.exec))
    summary = {"source": str(source_dir), "entries": len(src.entries), "kept": len(dest.entries),
               "undecodable": undecodable, "excluded": excluded, "covered_probes": covered,
               "seconds": round(time.time() - t0, 1), "rebuild": _rebuild_stats(src, replay_ms),
               "wins": {str(k): v for k, v in sorted(wins.items(), key=lambda kv: -kv[1])}}
    (dest_dir / "cmin.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return summary


def _rebuild_stats(src: Corpus, replay_ms: dict[int, float]) -> dict[str, Any]:
    """How long rebuilding entries from their choices took, overall and by lineage depth (steps):
    the cost the fuzz loop pays on every LineageCache miss."""
    if not replay_ms:
        return {}
    ms = sorted(replay_ms.values())
    by_depth: dict[int, list[float]] = {}
    for e in src.entries:
        if e.id in replay_ms:
            by_depth.setdefault(len(e.steps), []).append(replay_ms[e.id])
    return {"seconds": round(sum(ms) / 1000, 1), "mean_ms": round(sum(ms) / len(ms), 1),
            "median_ms": round(ms[len(ms) // 2], 1), "p90_ms": round(ms[int(len(ms) * 0.9)], 1),
            "max_ms": round(ms[-1], 1),
            "by_depth": {str(d): {"n": len(v), "mean_ms": round(sum(v) / len(v), 1)} for d, v in sorted(by_depth.items())}}
