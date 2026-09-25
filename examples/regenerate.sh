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
#
# The C++ leg runs for every example named in examples/cpp-legs.txt (one name
# per line, `#` comments): `kira --target cpp`, then $CXX -std=c++20 -O2 with
# the runtime include dir kira/cpp, then the binary's stdout is diffed against
# expected.txt and against the C and JS legs. It writes no snapshot of its own,
# so --check covers it exactly as a plain run does.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"
# $CC wins; otherwise the first of cc / gcc / clang on PATH (a MinGW or MSYS
# PATH has gcc but not always cc).
if [[ -n "${CC:-}" ]]; then
  CC_BIN="$CC"
else
  CC_BIN=""
  for candidate in cc gcc clang; do
    if command -v "$candidate" >/dev/null 2>&1; then CC_BIN="$candidate"; break; fi
  done
  if [[ -z "$CC_BIN" ]]; then
    echo "no C compiler found (set CC=/path/to/cc)" >&2
    exit 1
  fi
fi
NODE_BIN="${NODE:-node}"

# The C++ leg's examples. $CXX wins; otherwise the first of c++ / g++ / clang++
# on PATH. A compiler is required only when the list names an example.
CPP_LEGS_FILE="$ROOT/examples/cpp-legs.txt"
CPP_LEGS=()
if [[ -f "$CPP_LEGS_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line//[[:space:]]/}"
    [[ -n "$line" ]] && CPP_LEGS+=("$line")
  done < "$CPP_LEGS_FILE"
fi
has_cpp_leg() { # has_cpp_leg <example-name>
  local n
  for n in ${CPP_LEGS[@]+"${CPP_LEGS[@]}"}; do
    [[ "$n" == "$1" ]] && return 0
  done
  return 1
}
CXX_BIN=""
if [[ ${#CPP_LEGS[@]} -gt 0 ]]; then
  if [[ -n "${CXX:-}" ]]; then
    CXX_BIN="$CXX"
  else
    for candidate in c++ g++ clang++; do
      if command -v "$candidate" >/dev/null 2>&1; then CXX_BIN="$candidate"; break; fi
    done
    if [[ -z "$CXX_BIN" ]]; then
      echo "no C++ compiler found for the examples in cpp-legs.txt (set CXX=/path/to/c++)" >&2
      exit 1
    fi
  fi
fi
CPP_RUNTIME_INC="$ROOT/kira/cpp"
cpp_legs_run=0

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
    # A Windows C runtime writes "\r\n" for every "\n" (node writes "\n");
    # expected.txt records the program's text, not the host's line ending.
    ./app | tr -d '\r' > "$WORK/actual-c.txt"
    rm -f out.kira.c app app.exe
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

    "$NODE_BIN" out.kira.js 2>"$WORK/node.err" | tr -d '\r' > "$WORK/actual-js.txt" || {
      echo "  node failed:" >&2; cat "$WORK/node.err" >&2; exit 1;
    }
    rm -f out.kira.js
  ); then
    js_failed=1
  fi

  # --- C++ backend: emit, compile, run (examples/cpp-legs.txt only) ------
  cpp_failed=0
  cpp_ran=0
  if has_cpp_leg "$name"; then
    cpp_ran=1
    if ! (
      cd "$dir"
      rm -rf "$WORK/cpp" "$WORK/app-cpp" "$WORK/app-cpp.exe"
      "$KIRA_BIN" --target cpp --out "$WORK/cpp" >/dev/null 2>&1 || { echo "  kira (cpp) failed" >&2; exit 1; }

      mapfile -t cpp_sources < <(find "$WORK/cpp" -name '*.cxx' | sort)
      if [[ ${#cpp_sources[@]} -eq 0 ]]; then
        echo "  kira (cpp) emitted no .cxx under $WORK/cpp" >&2; exit 1
      fi
      "$CXX_BIN" -std=c++20 -O2 -I "$CPP_RUNTIME_INC" -I "$WORK/cpp" -o "$WORK/app-cpp" "${cpp_sources[@]}" 2>"$WORK/cxx.err" || {
        echo "  c++ failed:" >&2; cat "$WORK/cxx.err" >&2; exit 1;
      }
      "$WORK/app-cpp" | tr -d '\r' > "$WORK/actual-cpp.txt"
    ); then
      cpp_failed=1
    fi
  fi

  if [[ $c_failed -eq 1 || $js_failed -eq 1 || $cpp_failed -eq 1 ]]; then
    failures=$((failures + 1))
    continue
  fi

  # The C++ leg must print exactly what expected.txt, C and JS print.
  if [[ $cpp_ran -eq 1 ]]; then
    cpp_legs_run=$((cpp_legs_run + 1))
    cpp_ok=1
    if ! diff -q "$dir/expected.txt" "$WORK/actual-cpp.txt" >/dev/null; then
      echo "  MISMATCH: C++ stdout differs from expected.txt" >&2
      diff -u "$dir/expected.txt" "$WORK/actual-cpp.txt" | head -40 >&2 || true
      cpp_ok=0
    fi
    if ! diff -q "$WORK/actual-c.txt" "$WORK/actual-cpp.txt" >/dev/null; then
      echo "  MISMATCH: C and C++ stdout differ" >&2
      diff -u "$WORK/actual-c.txt" "$WORK/actual-cpp.txt" | head -40 >&2 || true
      cpp_ok=0
    fi
    if [[ $NODE_OK -eq 1 ]] && ! diff -q "$WORK/actual-js.txt" "$WORK/actual-cpp.txt" >/dev/null; then
      echo "  MISMATCH: JS and C++ stdout differ" >&2
      diff -u "$WORK/actual-js.txt" "$WORK/actual-cpp.txt" | head -40 >&2 || true
      cpp_ok=0
    fi
    if [[ $cpp_ok -eq 1 ]]; then
      echo "  ok: $name C++ stdout (matches expected.txt, C and JS)"
    else
      failures=$((failures + 1))
    fi
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
  # Every name in cpp-legs.txt must have run: a misspelt entry would otherwise
  # verify nothing and say nothing.
  echo "=== C++ leg ==="
  echo "  ran $cpp_legs_run of ${#CPP_LEGS[@]} example(s) listed in examples/cpp-legs.txt"
  if [[ $cpp_legs_run -ne ${#CPP_LEGS[@]} ]]; then
    echo "  a listed example did not run; check the names in examples/cpp-legs.txt" >&2
    failures=$((failures + 1))
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
