"""Corpus minimisation, as afl-cmin does it: replay every entry of a corpus once against the
current Rhino and harness, and keep, for every coverage probe any entry reaches, the smallest
entry that reaches it. The union of those entries covers everything the whole corpus covers.

The choice does not depend on the order the entries were found in. Keeping "the entries that add
coverage" while replaying in the original order would keep nearly all of them: each one was added
because it added coverage at that point. Size is the length of the entry's program text.

Each run reports every probe it hit as one bitmap per class (`coverage_hits`, about 5 KB for
Rhino's 43k probes), and the choice is made here: entries in order of size, each kept if it hits a
probe no smaller entry hit. That is the smallest entry per probe, ties going to the earlier entry.
No state lives in the JVM, so a restart (a program the watchdog had to kill) costs only that run.
Entries that time out are dropped, as the fuzz loop no longer adds them: a hang's children
inherit the runaway.
The kept entries are then run once more in a fresh JVM to write the new corpus's coverage.exec,
which also checks that they alone reach what the whole corpus reached: all but a few probes
(about 0.15 % on a small corpus) whose hits depend on timing or on what the JVM ran before.
"""

from __future__ import annotations

import base64
import json
import sys
import time
from pathlib import Path
from typing import Any, Iterator

from .fuzz import SLOW, Corpus, Entry, Lineage, continue_lineage, decode, replay
from .harness.client import RunResult
from .il.options import GenOptions

Bits = dict[str, int]          # class name -> probe bitmap (bit i = probe i)


def run_hits(run: RunResult) -> Bits:
    """Every probe a run hit in any mode."""
    hits: Bits = {}
    for o in run.results.values():
        for cls, b64 in (o.hitProbes or {}).items():
            hits[cls] = hits.get(cls, 0) | int.from_bytes(base64.b64decode(b64), "little")
    return hits


def choose(sized: list[tuple[int, int, Bits]]) -> dict[int, int]:
    """afl-cmin's choice over (size, entry id, hits): the entries that are the smallest to hit at
    least one probe, with how many probes each is the smallest for. Ties go to the earlier entry
    in `sized`."""
    covered: Bits = {}
    wins: dict[int, int] = {}
    for _, eid, hits in sorted(sized, key=lambda t: t[0]):
        new = 0
        for cls, bits in hits.items():
            fresh = bits & ~covered.get(cls, 0)
            if fresh:
                new += fresh.bit_count()
                covered[cls] = covered.get(cls, 0) | fresh
        if new:
            wins[eid] = new
    return wins


def minimise(source_dir: Path, dest_dir: Path, *, client, options: GenOptions = GenOptions(), steps: int = 5,
             language_version: int = 200, quiet: bool = False) -> dict[str, Any]:
    """Writes the minimised corpus (corpus.jsonl, coverage.exec, cmin.json) to `dest_dir`, which
    must not hold a corpus yet. `client` is a coverage-enabled HarnessClient."""
    src = Corpus(source_dir)
    dest_dir = Path(dest_dir)
    if (dest_dir / "corpus.jsonl").exists():
        raise FileExistsError(f"{dest_dir} already holds a corpus")
    t0 = time.time()
    undecodable: list[int] = []
    restarted: list[int] = []                     # entries whose run restarted the JVM
    timed_out: list[int] = []                     # entries that hang now: dropped, as the fuzz loop does
    replay_ms: dict[int, float] = {}              # entry id -> time to rebuild its program from its choices
    sources: dict[int, tuple[str, str]] = {}      # entry id -> (program text, template)
    sized: list[tuple[int, int, Bits]] = []
    from_parent = 0
    for i, (e, lineage, ms, rebuilt) in enumerate(_lineages_depth_first(src, options, steps)):
        replay_ms[e.id] = ms
        from_parent += rebuilt
        if lineage is None:
            undecodable.append(e.id)
            continue
        sources[e.id] = (lineage.source, lineage.template)
        run = client.run(lineage.source, language_version=language_version, seed=1,
                         coverage_bucket=lineage.template, coverage_hits=True)
        if run.restarted:
            restarted.append(e.id)
        if any(o.status in SLOW for o in run.results.values()):
            timed_out.append(e.id)
            continue
        sized.append((len(lineage.source), e.id, run_hits(run)))
        if not quiet and (i + 1) % 250 == 0:
            print(f"  {i + 1}/{len(src.entries)} ({time.time() - t0:.0f}s, rebuilding "
                  f"{sum(replay_ms.values()) / 1000:.0f}s of it)", file=sys.stderr)
    sized.sort(key=lambda t: t[1])                # ties to the earlier entry, whatever the visiting order
    wins = choose(sized)
    chosen_probes = sum(wins.values())
    # coverage.exec of the new corpus: the kept entries alone, in a fresh JVM
    client.restart()
    dest = Corpus(dest_dir)
    for e in src.entries:
        if e.id in wins:
            dest.add(e)
            source, template = sources[e.id]
            client.run(source, language_version=language_version, seed=1, coverage_bucket=template)
    covered = client.coverage_save(str(dest.exec))
    summary = {"source": str(source_dir), "entries": len(src.entries), "kept": len(dest.entries),
               "undecodable": undecodable, "restarted": restarted, "timed_out": timed_out,
               "probes_chosen": chosen_probes, "covered_probes": covered, "probes_not_reached": chosen_probes - covered,
               "seconds": round(time.time() - t0, 1), "rebuild": _rebuild_stats(src, replay_ms),
               "rebuilt_from_parent": from_parent,
               "wins": {str(k): v for k, v in sorted(wins.items(), key=lambda kv: -kv[1])}}
    (dest_dir / "cmin.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return summary


def _lineages_depth_first(src: Corpus, options: GenOptions, steps: int
                          ) -> Iterator[tuple[Entry, Lineage | None, float, bool]]:
    """Every entry's lineage, (entry, lineage or None if it no longer decodes, ms to build it,
    whether it was built from its parent's), visiting the lineage tree depth first: a child is
    built from its parent's lineage by replaying only its own round (continue_lineage), as the
    fuzz loop does on a cache miss, since a full replay costs ~0.23 s per mutation step. In corpus
    order a parent is usually thousands of entries back (on the seeded three-hour corpus 7 % of
    children are within 512 of it), so a cache would have to hold thousands of lineages (~0.3 MB
    each); depth first holds only the ancestors of the entry being built."""
    children: dict[int, list[Entry]] = {}
    roots: list[Entry] = []
    for e in src.entries:
        if e.parent is not None and e.parent != e.id and e.parent in src.by_id:
            children.setdefault(e.parent, []).append(e)
        else:
            roots.append(e)
    stack: list[tuple[Entry, tuple[Lineage, Entry] | None]] = [(e, None) for e in reversed(roots)]
    while stack:
        e, parent = stack.pop()
        t = time.perf_counter()
        lineage = None
        rebuilt = False
        choices = decode(e.choices)
        if choices is not None:
            cap = e.cap if e.cap is not None else steps
            if parent is not None:
                parent_choices = decode(parent[1].choices)
                if parent_choices is not None:
                    lineage = continue_lineage(parent[0], parent_choices, choices, options, cap)
                    rebuilt = lineage is not None
            if lineage is None:
                lineage = replay(choices, options, cap)
        yield e, lineage, (time.perf_counter() - t) * 1000, rebuilt
        below = (lineage, e) if lineage is not None else None
        stack.extend((c, below) for c in reversed(children.get(e.id, ())))


def _rebuild_stats(src: Corpus, replay_ms: dict[int, float]) -> dict[str, Any]:
    """How long rebuilding entries took (from the parent's lineage where possible), overall and
    by lineage depth (steps): the cost the fuzz loop pays on every LineageCache miss."""
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
