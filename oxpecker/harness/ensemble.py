"""Ensemble: one harness process per execution mode, run side by side.

Each process has a role. The *guide* (exactly one) is the reference every other outcome is
compared against and the only process with the JaCoCo agent: coverage, new probe ids and
`coverage_require` come from it alone. *Comparators* only report outcomes. An *oracle* (not yet
implemented) is a harness for another JavaScript engine that speaks the same protocol; it reports
only the JS-observable fields (its `capabilities`) and will decide which side of a difference is
wrong.
"""

from __future__ import annotations

import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Iterable

from .. import DEFAULT_HARNESS, available_harnesses, harness_bin
from .client import DEFAULT_MODES, HarnessClient, HarnessError, HarnessInfo, RunResult, describe_harness

GUIDE = "guide"
COMPARATOR = "comparator"
ORACLE = "oracle"
ROLES = (GUIDE, COMPARATOR, ORACLE)
DEFAULT_GUIDE = "interpreted"


@dataclass(frozen=True)
class HarnessSpec:
    """One harness process: its name (the key in RunResult.results), role and pinned mode, and
    which harness runs it (`harness`, launched by bin/harness-<harness>, unless `harness_bin`
    names a launcher)."""
    name: str
    role: str
    mode: str
    harness: str = DEFAULT_HARNESS
    harness_bin: str | None = None

    def __post_init__(self) -> None:
        if self.role not in ROLES:
            raise ValueError(f"unknown harness role: {self.role}")
        if self.role == ORACLE:
            raise NotImplementedError("oracle harnesses are not implemented yet")
        if self.harness_bin is None:
            if self.harness not in available_harnesses():
                raise ValueError(f"unknown harness {self.harness!r}; available: {', '.join(available_harnesses())}")
            object.__setattr__(self, "harness_bin", str(harness_bin(self.harness)))


def parse_target(target: str) -> tuple[str, str]:
    """`[harness:]mode` -> (harness, mode); the harness defaults to DEFAULT_HARNESS."""
    harness, _, mode = target.rpartition(":")
    return harness or DEFAULT_HARNESS, mode


def target_name(harness: str, mode: str) -> str:
    """The name of a harness process: the mode alone on the default harness, else `harness:mode`."""
    return mode if harness == DEFAULT_HARNESS else f"{harness}:{mode}"


def specs_for(targets: Iterable[str] = DEFAULT_MODES, guide: str = DEFAULT_GUIDE,
              include_guide: bool = False) -> list[HarnessSpec]:
    """The guide and a comparator per other target, each a `[harness:]mode`. The guide must be
    one of the targets unless `include_guide`, which adds it."""
    parsed = list(dict.fromkeys(parse_target(t) for t in targets))
    g = parse_target(guide)
    if g not in parsed:
        if not include_guide:
            raise ValueError(f"guide {guide!r} is not one of {[target_name(*t) for t in parsed]}")
        parsed.insert(0, g)
    return [HarnessSpec(target_name(*t), GUIDE if t == g else COMPARATOR, t[1], harness=t[0])
            for t in [g] + [t for t in parsed if t != g]]


class Ensemble:
    """The harness processes of one run, each a pinned HarnessClient. `run` sends a case to all
    of them at once and returns their outcomes keyed by harness name. A process that dies is
    restarted on its own; the others' outcomes for that case are kept. Not thread-safe."""

    def __init__(self, specs: Iterable[HarnessSpec], *, coverage: bool = False, quiet: bool = True,
                 recycle_every: int = 5000, hard_timeout_s: float = 60.0):
        self.specs = list(specs)
        guides = [s for s in self.specs if s.role == GUIDE]
        if len(guides) != 1:
            raise ValueError(f"an ensemble needs exactly one guide, got {len(guides)}")
        if len({s.name for s in self.specs}) != len(self.specs):
            raise ValueError("harness names must be unique")
        self.guide = guides[0].name
        self.coverage = coverage
        self.clients = {s.name: HarnessClient(s.harness_bin, mode=s.mode, quiet=quiet, recycle_every=recycle_every,
                                              hard_timeout_s=hard_timeout_s, coverage=coverage and s.role == GUIDE)
                        for s in self.specs}
        self.infos: dict[str, dict[str, Any]] = {}           # each running process's info, as sent
        self.declared: dict[str, HarnessInfo] = {}           # ... and as declared (name, version, modes)
        self._pool: ThreadPoolExecutor | None = None

    @classmethod
    def from_modes(cls, modes: Iterable[str] = DEFAULT_MODES, guide: str = DEFAULT_GUIDE, **kw: Any) -> "Ensemble":
        return cls(specs_for(modes, guide), **kw)

    @property
    def names(self) -> list[str]:
        return [s.name for s in self.specs]

    @property
    def comparators(self) -> list[str]:
        return [s.name for s in self.specs if s.role == COMPARATOR]

    @property
    def guide_client(self) -> HarnessClient:
        return self.clients[self.guide]

    @property
    def restarts(self) -> int:
        return sum(c.restarts for c in self.clients.values())

    def spec(self, name: str) -> HarnessSpec:
        return next(s for s in self.specs if s.name == name)

    # -- process management --------------------------------------------------------------
    def start(self) -> None:
        """Asks each launcher which modes it supports, then starts one process per spec, pinned to
        its mode. A spec whose mode its harness does not declare is a HarnessError."""
        launchers = {str(s.harness_bin) for s in self.specs}
        with ThreadPoolExecutor(max_workers=len(launchers)) as pool:
            static = dict(zip(launchers, pool.map(describe_harness, launchers)))
        for s in self.specs:
            d = static[s.harness_bin]
            if s.mode not in d.modes:
                raise HarnessError(f"harness {s.name!r}: {d.name} ({s.harness_bin}) does not support mode {s.mode!r}; "
                                   f"it declares {', '.join(d.modes)}")
        self._pool = ThreadPoolExecutor(max_workers=len(self.clients), thread_name_prefix="oxpecker-harness")
        for c in self.clients.values():
            c.start()
        for name, info in zip(self.clients, self._pool.map(lambda c: c.info(), self.clients.values())):
            self.infos[name] = info
            self.declared[name] = HarnessInfo.from_json(info)

    def stop(self) -> None:
        for c in self.clients.values():
            c.stop()
        if self._pool is not None:
            self._pool.shutdown(wait=False)
            self._pool = None

    def __enter__(self) -> "Ensemble":
        self.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.stop()

    def engine(self, name: str) -> str | None:
        """The declared name and version of the engine behind harness `name`, e.g. "Rhino 2.0.0"."""
        d = self.declared.get(name)
        return d.label if d else None

    def info(self) -> dict[str, Any]:
        """The guide's info, plus `guide` and every harness's info (with its role) under `harnesses`."""
        if not self.infos:
            self.start()
        return {**self.infos[self.guide], "guide": self.guide,
                "harnesses": {s.name: {"role": s.role, **self.infos[s.name]} for s in self.specs}}

    def describe(self) -> dict[str, Any]:
        """What identifies this ensemble across runs (stored with a fuzz corpus)."""
        return {"guide": self.guide,
                "harnesses": [{"name": s.name, "role": s.role, "harness": s.harness, "mode": s.mode,
                               "engine": self.engine(s.name)} for s in self.specs]}

    # -- coverage: the guide's alone -------------------------------------------------------
    def coverage_save(self, path: str) -> int:
        return self.guide_client.coverage_save(path)

    def coverage_load(self, path: str) -> int:
        return self.guide_client.coverage_load(path)

    def coverage_report(self) -> dict[str, Any]:
        return self.guide_client.coverage_report()

    # -- requests -------------------------------------------------------------------------
    def run(self, source: str, *, comparators: Iterable[str] | None = None, modes: Iterable[str] | None = None,
            id: str | None = None, coverage_bucket: str | None = None, coverage_new_ids: bool = False,
            coverage_require: dict[str, list[int]] | None = None, **kw: Any) -> RunResult:
        """Runs one case in the guide and in `comparators` (default: all of them; `()` runs the
        guide alone), or in the comparators whose mode is one of `modes`. The coverage arguments
        go to the guide only. Other keyword arguments are those of HarnessClient.run."""
        if modes is not None:
            if comparators is not None:
                raise TypeError("pass comparators or modes, not both")
            comparators = [s.name for s in self.specs if s.role == COMPARATOR and s.mode in set(modes)]
        if self._pool is None:
            self.start()
        assert self._pool is not None
        wanted = set(self.comparators if comparators is None else comparators)
        unknown = wanted - set(self.comparators)
        if unknown:
            raise ValueError(f"not comparators of this ensemble: {sorted(unknown)}")
        names = [n for n in self.names if n == self.guide or n in wanted]
        case_id = id or uuid.uuid4().hex[:12]

        def one(name: str) -> RunResult:
            extra = dict(coverage_bucket=coverage_bucket, coverage_new_ids=coverage_new_ids,
                         coverage_require=coverage_require) if name == self.guide else {}
            return self.clients[name].run(source, id=case_id, **kw, **extra)

        results = {}
        restarted = False
        for name, res in zip(names, self._pool.map(one, names)):
            spec = self.spec(name)
            (o,) = res.results.values()
            d = self.declared.get(name)
            o.harness, o.role, o.engine = name, spec.role, self.engine(name)
            o.capabilities = list(d.capabilities) if d else None
            results[name] = o
            restarted |= res.restarted
        return RunResult(case_id, results, restarted=restarted, guide=self.guide)


def describe_mismatch(stored: dict[str, Any], current: dict[str, Any]) -> str | None:
    """Why coverage recorded under `stored` (Ensemble.describe) does not carry over to `current`:
    the guide's mode or engine changed. None if it carries over."""
    def guide_of(d: dict[str, Any]) -> dict[str, Any]:
        return next((h for h in d.get("harnesses", []) if h.get("name") == d.get("guide")), {})
    a, b = guide_of(stored), guide_of(current)
    for key in ("mode", "engine"):
        if a.get(key) != b.get(key):
            return f"the guide's {key} changed: {a.get(key)} -> {b.get(key)}"
    return None

