#!/usr/bin/env bash
# Refresh the checked-in C snapshots for every example, then verify each one
# still builds and prints what its expected.txt says.
#
#   ./examples/regenerate.sh          # refresh + verify everything
#   ./examples/regenerate.sh --check  # verify only; fail if a snapshot is stale
#   ./examples/regenerate.sh 04-classes
#
# What lands in git, per example:
#   generated.user.c  -- the user lowering (everything after the runtime prelude)
#   expected.txt      -- exact stdout of the built binary
#
# The runtime prelude is byte-identical for every example, so it is checked in
# once as examples/prelude.reference.c instead of eight times.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"
CC_BIN="${CC:-cc}"
PRELUDE_REF="$ROOT/examples/prelude.reference.c"
PRELUDE_END='#endif /* KIRA_RUNTIME_H */'

CHECK_ONLY=0
SELECTED=()
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    -*) echo "unknown flag: $arg" >&2; exit 2 ;;
    *) SELECTED+=("$arg") ;;
  esac
done

if [[ ! -x "$KIRA_BIN" ]]; then
  echo "kira CLI not found at $KIRA_BIN" >&2
  echo "Run: ./gradlew installDist   (or set KIRA=/path/to/kira)" >&2
  exit 1
fi

# Split out.kira.c at the end of the runtime prelude.
prelude_of() { sed -n "1,\%^$(sed 's/[[\.*^$/]/\\&/g' <<<"$PRELUDE_END")\$%p" "$1"; }
user_of() { awk -v marker="$PRELUDE_END" 'p; index($0, marker){p=1}' "$1" | sed '/^$/N;/^\n$/D'; }

# `diff` a freshly built artifact against what is committed.
emit() { # emit <path> <content-file> <label>
  local dest="$1" fresh="$2" label="$3"
  if [[ $CHECK_ONLY -eq 1 ]]; then
    if ! diff -q "$dest" "$fresh" >/dev/null 2>&1; then
      echo "  STALE: $label" >&2
      diff -u "$dest" "$fresh" | head -40 >&2 || true
      return 1
    fi
    echo "  ok: $label"
  else
    cp "$fresh" "$dest"
    echo "  wrote: $label"
  fi
}

if [[ ${#SELECTED[@]} -gt 0 ]]; then
  DIRS=()
  for name in "${SELECTED[@]}"; do DIRS+=("$ROOT/examples/$name"); done
else
  DIRS=("$ROOT"/examples/[0-9][0-9]-*/)
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
prelude_seen=""

for dir in "${DIRS[@]}"; do
  dir="${dir%/}"
  name="$(basename "$dir")"
  if [[ ! -f "$dir/kira.yaml" ]]; then
    echo "unknown example: $name" >&2
    exit 1
  fi
  echo "=== $name ==="
  (
    cd "$dir"
    rm -f out.kira.c app
    "$KIRA_BIN" >/dev/null 2>&1 || { echo "  kira failed" >&2; exit 1; }

    user_of out.kira.c > "$WORK/user.c"
    prelude_of out.kira.c > "$WORK/prelude.c"

    "$CC_BIN" -std=c17 -O2 -o app out.kira.c 2>"$WORK/cc.err" || {
      echo "  cc failed:" >&2; cat "$WORK/cc.err" >&2; exit 1;
    }
    ./app > "$WORK/actual.txt"
    rm -f out.kira.c app
  ) || { failures=$((failures + 1)); continue; }

  # The prelude must stay identical across examples -- that is what makes a
  # single shared reference copy honest.
  if [[ -z "$prelude_seen" ]]; then
    prelude_seen="$WORK/prelude.first.c"
    cp "$WORK/prelude.c" "$prelude_seen"
  elif ! diff -q "$prelude_seen" "$WORK/prelude.c" >/dev/null; then
    echo "  WARNING: runtime prelude differs from the first example's" >&2
    failures=$((failures + 1))
  fi

  emit "$dir/generated.user.c" "$WORK/user.c" "$name/generated.user.c" || failures=$((failures + 1))
  emit "$dir/expected.txt" "$WORK/actual.txt" "$name/expected.txt" || failures=$((failures + 1))
done

if [[ -n "$prelude_seen" && ${#SELECTED[@]} -eq 0 ]]; then
  echo "=== shared runtime prelude ==="
  emit "$PRELUDE_REF" "$prelude_seen" "examples/prelude.reference.c" || failures=$((failures + 1))
fi

if [[ $failures -gt 0 ]]; then
  echo
  if [[ $CHECK_ONLY -eq 1 ]]; then
    echo "$failures snapshot(s) stale -- run ./examples/regenerate.sh and commit the diff" >&2
  else
    echo "$failures example(s) failed" >&2
  fi
  exit 1
fi

echo
if [[ $CHECK_ONLY -eq 1 ]]; then
  echo "all snapshots current"
else
  echo "done -- commit generated.user.c / expected.txt if the lowering changed on purpose"
fi
