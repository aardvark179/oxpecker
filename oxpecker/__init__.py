"""oxpecker: differential testing of Mozilla Rhino, interpreted vs compiled."""

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Each harness is a project harness-<name>/ with a launcher bin/harness-<name>; OXPECKER_HARNESS
# picks the one used where none is named (bin/harness reads it too).
DEFAULT_HARNESS = os.environ.get("OXPECKER_HARNESS", "rhino2.0")


def harness_bin(name: str) -> Path:
    """The launcher of harness `name`."""
    return ROOT / "bin" / f"harness-{name}"


def harness_libs(name: str) -> Path:
    """Where harness `name`'s build puts its jar and the JaCoCo agent."""
    return ROOT / f"harness-{name}" / "build" / "libs"


def available_harnesses() -> list[str]:
    """The harnesses with a launcher in bin/."""
    return sorted(p.name.removeprefix("harness-") for p in (ROOT / "bin").glob("harness-*"))


HARNESS_BIN = harness_bin(DEFAULT_HARNESS)
