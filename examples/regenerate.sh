#!/usr/bin/env bash
# Refresh the checked-in C and JS snapshots for every example, then verify each
# one still builds and prints what its expected.txt says.
#
#   ./examples/regenerate.sh          # refresh + verify everything
#   ./examples/regenerate.sh --check  # verify only; fail if a snapshot is stale
#   ./examples/regenerate.sh 04-classes
#
# What lands in git, per example:
#   generated.user.c  -- the C user lowering (everything after the runtime prelude)
#   generated.user.js -- the JS user lowering (everything after the runtime prelude)
#   expected.txt      -- exact stdout of the built binary (both backends must agree)
#
# The runtime preludes are byte-identical for every example, so they are checked
# in once as examples/prelude.reference.c and examples/prelude.reference.js.
#
# The JS pass runs when `node` is on PATH (or $NODE points at it); without it,
# C verification still runs and JS snapshots are left alone.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"
CC_BIN="${CC:-cc}"
NODE_BIN="${NODE:-node}"
C_PRELUDE_REF="$ROOT/examples/prelude.reference.c"
JS_PRELUDE_REF="$ROOT/examples/prelude.reference.js"
C_PRELUDE_END='#endif /* KIRA_RUNTIME_H */'
JS_PRELUDE_END='// __KIRA_JS_PRELUDE_END__'

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

NODE_OK=0
if command -v "$NODE_BIN" >/dev/null 2>&1; then
  NODE_OK=1
else
  echo "node not found (set NODE=/path/to/node) -- skipping the JS backend pass" >&2
fi

# Split out.kira.c / out.kira.js at the end of their runtime preludes.
c_prelude_of() { sed -n "1,\%^$(sed 's/[[\.*^$/]/\\&/g' <<<"$C_PRELUDE_END")\$%p" "$1"; }
c_user_of() { awk -v marker="$C_PRELUDE_END" 'p; index($0, marker){p=1}' "$1" | sed '/^$/N;/^\n$/D'; }
js_prelude_of() { sed -n "1,\%^$(sed 's/[[\.*^$/]/\\&/g' <<<"$JS_PRELUDE_END")\$%p" "$1"; }
js_user_of() { awk -v marker="$JS_PRELUDE_END" 'p; index($0, marker){p=1}' "$1" | sed '/^$/N;/^\n$/D'; }

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
c_prelude_seen=""
js_prelude_seen=""

for dir in "${DIRS[@]}"; do
  dir="${dir%/}"
  name="$(basename "$dir")"
  if [[ ! -f "$dir/kira.yaml" ]]; then
    echo "unknown example: $name" >&2
    exit 1
  fi
  echo "=== $name ==="

  c_failed=0
  js_failed=0

  # --- C backend: emit, compile, run -------------------------------
  if ! (
    cd "$dir"
    rm -f out.kira.c app
    "$KIRA_BIN" >/dev/null 2>&1 || { echo "  kira (c) failed" >&2; exit 1; }

    c_user_of out.kira.c > "$WORK/user.c"
    c_prelude_of out.kira.c > "$WORK/prelude.c"

    "$CC_BIN" -std=c17 -O2 -o app out.kira.c 2>"$WORK/cc.err" || {
      echo "  cc failed:" >&2; cat "$WORK/cc.err" >&2; exit 1;
    }
    ./app > "$WORK/actual-c.txt"
    rm -f out.kira.c app
  ); then
    c_failed=1
  fi

  # --- JS backend: emit, run with node -----------------------------
  if [[ $NODE_OK -eq 1 ]] && ! (
    cd "$dir"
    rm -f out.kira.js
    "$KIRA_BIN" --target js >/dev/null 2>&1 || { echo "  kira (js) failed" >&2; exit 1; }

    js_user_of out.kira.js > "$WORK/user.js"
    js_prelude_of out.kira.js > "$WORK/prelude.js"

    "$NODE_BIN" out.kira.js > "$WORK/actual-js.txt" 2>"$WORK/node.err" || {
      echo "  node failed:" >&2; cat "$WORK/node.err" >&2; exit 1;
    }
    rm -f out.kira.js
  ); then
    js_failed=1
  fi

  if [[ $c_failed -eq 1 || $js_failed -eq 1 ]]; then
    failures=$((failures + 1))
    continue
  fi

  # Both backends must print exactly the same thing; expected.txt is shared.
  # (Only when node actually ran -- without it we verify the C side alone.)
  if [[ $NODE_OK -eq 1 && $c_failed -eq 0 && $js_failed -eq 0 ]] && ! diff -q "$WORK/actual-c.txt" "$WORK/actual-js.txt" >/dev/null; then
    echo "  MISMATCH: C and JS stdout differ" >&2
    diff -u "$WORK/actual-c.txt" "$WORK/actual-js.txt" | head -40 >&2 || true
    failures=$((failures + 1))
  fi

  # The preludes must stay identical across examples -- that is what makes a
  # single shared reference copy honest.
  if [[ $c_failed -eq 0 ]]; then
    if [[ -z "$c_prelude_seen" ]]; then
      c_prelude_seen="$WORK/prelude.first.c"
      cp "$WORK/prelude.c" "$c_prelude_seen"
    elif ! diff -q "$c_prelude_seen" "$WORK/prelude.c" >/dev/null; then
      echo "  WARNING: C runtime prelude differs from the first example's" >&2
      failures=$((failures + 1))
    fi
  fi
  if [[ $NODE_OK -eq 1 && $js_failed -eq 0 ]]; then
    if [[ -z "$js_prelude_seen" ]]; then
      js_prelude_seen="$WORK/prelude.first.js"
      cp "$WORK/prelude.js" "$js_prelude_seen"
    elif ! diff -q "$js_prelude_seen" "$WORK/prelude.js" >/dev/null; then
      echo "  WARNING: JS runtime prelude differs from the first example's" >&2
      failures=$((failures + 1))
    fi
  fi

  if [[ $c_failed -eq 0 ]]; then
    emit "$dir/generated.user.c" "$WORK/user.c" "$name/generated.user.c" || failures=$((failures + 1))
  fi
  if [[ $NODE_OK -eq 1 && $js_failed -eq 0 ]]; then
    emit "$dir/generated.user.js" "$WORK/user.js" "$name/generated.user.js" || failures=$((failures + 1))
  fi
  if [[ $c_failed -eq 0 ]]; then
    emit "$dir/expected.txt" "$WORK/actual-c.txt" "$name/expected.txt" || failures=$((failures + 1))
  fi
done

if [[ ${#SELECTED[@]} -eq 0 ]]; then
  if [[ -n "$c_prelude_seen" ]]; then
    echo "=== shared C runtime prelude ==="
    emit "$C_PRELUDE_REF" "$c_prelude_seen" "examples/prelude.reference.c" || failures=$((failures + 1))
  fi
  if [[ -n "$js_prelude_seen" ]]; then
    echo "=== shared JS runtime prelude ==="
    emit "$JS_PRELUDE_REF" "$js_prelude_seen" "examples/prelude.reference.js" || failures=$((failures + 1))
  fi
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
  echo "done -- commit generated.user.c / generated.user.js / expected.txt if the lowering changed on purpose"
fi
