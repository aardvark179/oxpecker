"""Whether slow corpus entries breed the timeouts that slow a campaign down: runs a sample of a
corpus's entries and a few extensions of each (as the fuzz loop extends a parent), and reports
the children's timeout rate and run time by the parent's own run time. A watchdog timeout (a
native builtin the instruction budget cannot interrupt) costs the wall limit plus a JVM restart.

Usage: .venv/bin/python scripts/entry_speed.py CORPUS_DIR [SAMPLE] [CHILDREN] [SEED]
       (defaults 150 entries, 4 children each, seed 1; results also in CORPUS_DIR/../entry_speed.json)
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from random import Random

from oxpecker.cmin import _lineages_depth_first
from oxpecker.fuzz import Corpus, decode, extend
from oxpecker.harness.client import HarnessClient
from oxpecker.il.options import GenOptions

STEPS = 5
BUCKETS = [(0, 50), (50, 200), (200, 1000), (1000, 10 ** 9)]


def measure(client: HarnessClient, source: str, template: str) -> dict:
    run = client.run(source, seed=1, coverage_bucket=template)
    outs = list(run.results.values())
    return {"ms": max(o.ms or 0 for o in outs),
            "timeout": next((o.reason or "timeout" for o in outs if o.status == "timeout"), None),
            "restarted": run.restarted}


def main(corpus_dir: str, sample: int = 150, children: int = 4, seed: int = 1) -> None:
    corpus = Corpus(Path(corpus_dir))
    rng = Random(seed)
    chosen = {e.id for e in rng.sample(corpus.entries, min(sample, len(corpus.entries)))}
    options = GenOptions()
    rows = []
    t0 = time.time()
    with HarnessClient(quiet=True, coverage=True) as client:
        for e, lineage, _, _ in _lineages_depth_first(corpus, options, STEPS):
            if e.id not in chosen or lineage is None:
                continue
            row = {"id": e.id, "depth": len(e.steps), "lines": len(lineage.source.splitlines()),
                   **measure(client, lineage.source, lineage.template), "children": []}
            for _ in range(children):
                drawn = extend(decode(e.choices), options, STEPS, rng, lineage)
                if drawn is not None:
                    row["children"].append(measure(client, drawn[0].source, drawn[0].template))
            rows.append(row)
            if len(rows) % 25 == 0:
                print(f"  {len(rows)}/{len(chosen)} ({time.time() - t0:.0f}s)", file=sys.stderr)
    (Path(corpus_dir).parent / "entry_speed.json").write_text(json.dumps(rows), encoding="utf-8")

    print(f"{'parent ms':>12} {'parents':>7} {'children':>8} {'timeouts':>9} {'watchdog':>9} {'child ms':>9} {'time share':>10}")
    total = sum(c["ms"] for r in rows for c in r["children"]) or 1
    for lo, hi in BUCKETS:
        rs = [r for r in rows if lo <= r["ms"] < hi]
        cs = [c for r in rs for c in r["children"]]
        if not cs:
            continue
        to = sum(c["timeout"] is not None for c in cs)
        wd = sum(c["timeout"] == "watchdog" for c in cs)
        ms = sum(c["ms"] for c in cs)
        label = f"{lo}-{hi if hi < 10 ** 9 else ''}"
        print(f"{label:>12} {len(rs):>7} {len(cs):>8} {to / len(cs):>8.0%} {wd / len(cs):>8.0%} "
              f"{ms / len(cs):>7.0f}ms {ms / total:>9.0%}")
    worst = sorted(rows, key=lambda r: -sum(c["timeout"] is not None for c in r["children"]))[:10]
    print("parents with the most timed-out children: id, own ms, own timeout, children timed out")
    for r in worst:
        print(f"  {r['id']:>6} {r['ms']:>6}ms {str(r['timeout']):>12} "
              f"{sum(c['timeout'] is not None for c in r['children'])}/{len(r['children'])}")


if __name__ == "__main__":
    a = sys.argv[1:]
    main(a[0], *(int(x) for x in a[1:]))
