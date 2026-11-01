"""What a LineageCache miss costs on a real corpus: a full replay of an entry's choices against a
rebuild from its parent's (cached) lineage, by lineage depth, checking both give the same program.

Usage: .venv/bin/python scripts/lineage_rebuild_bench.py CORPUS_DIR [PER_DEPTH]   (default 8)
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

from oxpecker.fuzz import Corpus, LineageCache, decode, replay
from oxpecker.il.options import GenOptions

STEPS = 5


def main(corpus_dir: str, per_depth: int = 8) -> None:
    corpus = Corpus(Path(corpus_dir))
    options = GenOptions()
    by_depth: dict[int, list] = {}
    for e in corpus.entries:
        if e.parent is not None and e.parent in corpus.by_id and len(by_depth.setdefault(len(e.steps), [])) < per_depth:
            by_depth[len(e.steps)].append(e)
    print(f"{'depth':>5} {'n':>3} {'full replay':>12} {'from parent':>12} {'same':>5}")
    total_full = total_inc = 0.0
    for depth, entries in sorted(by_depth.items()):
        full_ms = inc_ms = 0.0
        same = 0
        for e in entries:
            cache = LineageCache()
            cache.get(corpus.by_id[e.parent], options, STEPS, corpus.by_id)      # parent cached, untimed
            t = time.perf_counter()
            rebuilt = cache.get(e, options, STEPS, corpus.by_id)
            inc_ms += (time.perf_counter() - t) * 1000
            t = time.perf_counter()
            full = replay(decode(e.choices), options, e.cap if e.cap is not None else STEPS)
            full_ms += (time.perf_counter() - t) * 1000
            same += rebuilt is not None and full is not None and rebuilt.source == full.source
        total_full += full_ms
        total_inc += inc_ms
        print(f"{depth:>5} {len(entries):>3} {full_ms / len(entries):>9.0f} ms {inc_ms / len(entries):>9.0f} ms "
              f"{same:>2}/{len(entries)}")
    print(f"all: full replay {total_full / 1000:.1f} s, from parent {total_inc / 1000:.1f} s")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8)
