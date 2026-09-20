#!/usr/bin/env bash
# Build one example: emit C with kira, compile with cc, run.
# Usage: ./examples/run.sh 01-hello
#        ./examples/run.sh              # runs every example in order
#        ./examples/run.sh --keep 04-classes   # leave out.kira.c + app behind
#
# To refresh the committed C snapshots instead, use ./examples/regenerate.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"

KEEP=0
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    -*) echo "unknown flag: $arg" >&2; exit 2 ;;
    *) ARGS+=("$arg") ;;
  esac
done

if [[ ! -x "$KIRA_BIN" ]]; then
  echo "kira CLI not found at $KIRA_BIN" >&2
  echo "Run: ./gradlew installDist" >&2
  echo "Or set KIRA=/path/to/kira" >&2
  exit 1
fi

run_one() {
  local name="$1"
  local dir="$ROOT/examples/$name"
  if [[ ! -f "$dir/kira.yaml" ]]; then
    echo "unknown example: $name" >&2
    exit 1
  fi
  echo "=== $name ==="
  (
    cd "$dir"
    rm -f out.kira.c app
    "$KIRA_BIN"
    cc -std=c17 -O2 -o app out.kira.c
    ./app
    if [[ $KEEP -eq 1 ]]; then
      echo "-- kept $dir/out.kira.c and $dir/app"
    else
      rm -f out.kira.c app
    fi
  )
}

if [[ ${#ARGS[@]} -eq 0 ]]; then
  for d in "$ROOT"/examples/[0-9][0-9]-*/; do
    run_one "$(basename "$d")"
  done
else
  run_one "${ARGS[0]}"
fi
