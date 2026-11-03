"""Writes corpus entries' programs out as .js files, to read or replay them (oxpecker diff).

Usage: .venv/bin/python scripts/corpus_sources.py CORPUS_DIR OUT_DIR ID...
       .venv/bin/python scripts/corpus_sources.py CORPUS_DIR OUT_DIR --timed-out ENTRY_SPEED_JSON
       (the second form: the entries scripts/entry_speed.py saw time out)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from oxpecker.cmin import _lineages_depth_first
from oxpecker.fuzz import Corpus
from oxpecker.il.options import GenOptions


def main(corpus_dir: str, out_dir: str, args: list[str]) -> None:
    if args[:1] == ["--timed-out"]:
        ids = {r["id"] for r in json.loads(Path(args[1]).read_text(encoding="utf-8")) if r["timeout"]}
    else:
        ids = {int(a) for a in args}
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for e, lineage, _, _ in _lineages_depth_first(Corpus(Path(corpus_dir)), GenOptions(), 5):
        if e.id in ids and lineage is not None:
            path = out / f"entry-{e.id}.js"
            path.write_text(f"// corpus entry {e.id}, steps {' '.join(e.steps)}\n{lineage.source}", encoding="utf-8")
            print(f"{path}  {len(lineage.source.splitlines())} lines")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
