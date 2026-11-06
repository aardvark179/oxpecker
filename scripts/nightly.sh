#!/bin/sh
# Local "nightly": regression check, a differential-testing campaign, the inspector export, corpus replays.
# Usage: scripts/nightly.sh [examples] [out-dir]      (defaults: 2000, runs/nightly-<date>)
set -u
cd "$(dirname "$0")/.."
EXAMPLES="${1:-2000}"
OUT="${2:-runs/nightly-$(date +%Y-%m-%d)}"
mkdir -p "$OUT"
PATH="$PWD/bin:$PATH"; export PATH

echo "== build"
(cd harness-rhino2.0 && ./gradlew -q fatJar) || exit 1
(cd harness-rhino1.9 && ./gradlew -q fatJar) || exit 1
./gradlew -q :oxpecker-fuzz:fatJar || exit 1

echo "== regressions must still be detected"
./gradlew -q :oxpecker-fuzz:test -Pslow --tests 'dev.oxpecker.harness.HarnessTest' --tests 'dev.oxpecker.harness.HarnessCoverageTest' 2>&1 \
    | tail -5 | tee "$OUT/tests.txt"

echo "== differential-testing campaign (10 minutes)"
oxpecker fuzz -o "$OUT/corpus" --seconds 600 -q 2>&1 | tail -30 | tee "$OUT/fuzz.txt"

echo "== inspector export for the UI"
oxpecker export -o ui/data --per-generator 20 --per-template 20 --worst 15 2>&1 | tail -22 | tee "$OUT/export.txt"

echo "== corpora"
oxpecker corpus rhino-jstests -o "$OUT/jstests" 2>&1 | tail -12 | tee "$OUT/jstests.txt"
oxpecker corpus test262 --filter language -o "$OUT/t262-language" 2>&1 | tail -20 | tee "$OUT/t262.txt"

echo "== summary in $OUT"
