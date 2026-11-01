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
    sources: dict[int, tuple[str, str]] = {}      # entry id -> (program text, template)
    for i, e in enumerate(src.entries):
        lineage = src.replay(i, options, steps)
        if lineage is None:
            undecodable.append(e.id)
        else:
            sources[e.id] = (lineage.source, lineage.template)
    excluded: list[int] = []        # entries whose run restarted the JVM
    for attempt in range(1, MAX_PASSES + 1):
        restarts_before = client.restarts
        culprit = None
        for n, (eid, (source, template)) in enumerate(sources.items(), 1):
            client.run(source, language_version=language_version, seed=1, coverage_bucket=template,
                       cmin_key=str(eid), cmin_size=len(source))
            if client.restarts != restarts_before:
                culprit = eid
                break
            if not quiet and n % 500 == 0:
                print(f"  {n}/{len(sources)} replayed ({time.time() - t0:.0f}s)", file=sys.stderr)
        if culprit is None:
            break
        excluded.append(culprit)
        del sources[culprit]
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
               "seconds": round(time.time() - t0, 1),
               "wins": {str(k): v for k, v in sorted(wins.items(), key=lambda kv: -kv[1])}}
    (dest_dir / "cmin.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return summary
