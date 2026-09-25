#!/usr/bin/env bash
# sys.sh - the hosted system runtime test (sys_test.cxx + kira/os.cxx) on every
# toolchain found.
#
#     bash kira/cpp/tests/sys.sh
#
# Builds, each from a clean output (a stale binary never passes for a fresh one):
#   gcc            g++ -std=c++20 and the warning contract; runs the suite, the
#                  check-format diff and the exit mode; also compiles os.cxx under
#                  -DKIRA_PROFILE_FREESTANDING=1, which must leave an empty object
#   clang          zig c++ (clang 20), -target x86_64-windows-gnu on Windows; the same
#   zig-aarch64    zig c++ -target aarch64-linux-gnu.2.35 -c (the Orange Pi, the Jetson)
#   musl           zig c++ -target x86_64-linux-musl -static, run under
#                  LC_ALL=de_DE.UTF-8: natively on Linux, in `wsl -d docker-desktop`
#                  on Windows when that distro exists; this is the run that covers
#                  the POSIX half (the pty pair, fork/execvp, sigaction) on a
#                  Windows machine
#
# The check format: `sys_test testfmt-ref` prints a script through bibo's printf
# code and `sys_test testfmt-suite` the same script through kira::test::Suite;
# they must be identical and both must exit 1 (the script has failing checks).
#
# Overrides: KIRA_CXX_GCC, KIRA_ZIG. KIRA_REQUIRE_TOOLCHAINS=1 makes a missing
# toolchain a failure instead of a skip. MSVC is kira\cpp\tests\sys_msvc.bat.
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out="$root/build/cpp-tests/sys"
mkdir -p "$out"

WARN=(-std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror)

passes=0
fails=0
skips=0
ok() { echo "ok    $*"; passes=$((passes + 1)); }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
missing() {
    if [ "${KIRA_REQUIRE_TOOLCHAINS:-0}" = 1 ]; then
        fail "$1: not found ($2), and KIRA_REQUIRE_TOOLCHAINS=1"
    else
        echo "skip  $1: not found ($2)"
        skips=$((skips + 1))
    fi
}

windows=0
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) windows=1 ;; esac
linux=0
case "$(uname -s)" in Linux) linux=1 ;; esac
# Native tools on Windows get Windows paths, never MSYS ones.
p() { if [ "$windows" = 1 ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

inc=$(p "$root/kira/cpp")
src=$(p "$here/sys_test.cxx")
oscxx=$(p "$root/kira/cpp/kira/os.cxx")

have() { command -v "$1" > /dev/null 2>&1 || [ -x "$1" ]; }
exe() { if [ "$windows" = 1 ]; then printf '%s.exe' "$1"; else printf '%s' "$1"; fi; }

# Output without CR: a Windows program's stdout is in text mode.
lf() { tr -d '\r' < "$1"; }

# Libraries a hosted link needs: Winsock on Windows, pthread and openpty on Linux glibc.
hostlibs() {
    if [ "$windows" = 1 ]; then
        printf '%s\n' -lws2_32
    elif [ "$linux" = 1 ]; then
        printf '%s\n' -pthread -lutil
    fi
}

# Runs one hosted build: the suite, the check-format diff and the exit mode.
# $3.. is the command that runs the binary (empty: directly), and $2 is the
# binary's path as that command sees it.
check_run() {
    local name=$1 bin=$2
    shift 2
    local runner=("$@")
    local log="$out/$name.out" err="$out/$name.err"
    "${runner[@]}" "$bin" > "$log" 2> "$err"
    local rc=$?
    local last
    last=$(lf "$log" | grep -aE '^[0-9]+ checks, [0-9]+ failed$' | tail -1)
    if [ "$rc" = 0 ] && printf '%s' "$last" | grep -qE '^[0-9]+ checks, 0 failed$'; then
        ok "$name: sys_test runs: $last"
    else
        lf "$log" | grep -aE '^  FAIL|^        (got|want)' | head -30
        tr -d '\000' < "$err" | head -5
        fail "$name: sys_test exited $rc (${last:-no summary line})"
    fi

    "${runner[@]}" "$bin" testfmt-ref > "$out/$name.ref.out" 2> /dev/null
    local rcref=$?
    "${runner[@]}" "$bin" testfmt-suite > "$out/$name.suite.out" 2> /dev/null
    local rcsuite=$?
    if [ "$rcref" = 1 ] && [ "$rcsuite" = 1 ]; then
        ok "$name: the check-format script exits 1 through printf and through Suite"
    else
        fail "$name: the check-format script exited $rcref (printf) and $rcsuite (Suite), not 1 and 1"
    fi
    if [ -s "$out/$name.ref.out" ] && cmp -s "$out/$name.ref.out" "$out/$name.suite.out"; then
        ok "$name: kira::test::Suite prints byte for byte what bibo's printf code prints ($(lf "$out/$name.ref.out" | grep -acE '^  (ok|FAIL)') lines)"
    else
        diff "$out/$name.ref.out" "$out/$name.suite.out" | head -20
        fail "$name: kira::test::Suite's output differs from the printf reference"
    fi

    "${runner[@]}" "$bin" exit 5 > "$out/$name.exit.out" 2> /dev/null
    rc=$?
    if [ "$rc" = 5 ] && lf "$out/$name.exit.out" | grep -aqx 'exiting'; then
        ok "$name: kira::os::exit(5) flushes stdout and exits 5"
    else
        fail "$name: the exit mode exited $rc"
    fi
}

# ---- gcc -------------------------------------------------------------------------
gxx=${KIRA_CXX_GCC:-g++}
if have "$gxx"; then
    bin=$(exe "$out/sys_gcc")
    rm -f "$bin"
    mapfile -t libs < <(hostlibs)
    if "$gxx" "${WARN[@]}" -I "$inc" "$src" "$oscxx" -o "$(p "$bin")" "${libs[@]}" > "$out/gcc.build.log" 2>&1 && [ -f "$bin" ]; then
        ok "gcc: sys_test builds ($("$gxx" -dumpfullversion 2> /dev/null || "$gxx" -dumpversion))"
        check_run gcc "$bin"
    else
        head -30 "$out/gcc.build.log"
        fail "gcc: sys_test does not build"
    fi
    fsobj="$out/os_fs_gcc.o"
    rm -f "$fsobj"
    if "$gxx" "${WARN[@]}" -DKIRA_PROFILE_FREESTANDING=1 -I "$inc" -c "$oscxx" -o "$(p "$fsobj")" > "$out/gcc.fs.log" 2>&1; then
        defined=$(nm "$fsobj" 2> /dev/null | awk '$2 ~ /^[TDBRW]$/ { print $3 }' | head -5 | tr '\n' ' ')
        if [ -z "$defined" ]; then
            ok "gcc: os.cxx is empty under the freestanding profile"
        else
            fail "gcc: os.cxx defines symbols under the freestanding profile: $defined"
        fi
    else
        head -20 "$out/gcc.fs.log"
        fail "gcc: os.cxx does not compile under the freestanding profile"
    fi
else
    missing gcc "$gxx"
fi

# ---- zig: clang, aarch64, musl ---------------------------------------------------------
zig=${KIRA_ZIG:-zig}
if have "$zig"; then
    # A broken shared cache reports FileNotFound on files that exist.
    export ZIG_LOCAL_CACHE_DIR="${ZIG_LOCAL_CACHE_DIR:-$(p "$out/zig-cache")}"
    export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$(p "$out/zig-cache")}"
    if [ "$windows" = 1 ]; then clangtarget=(-target x86_64-windows-gnu); else clangtarget=(); fi
    mapfile -t libs < <(hostlibs)
    bin=$(exe "$out/sys_clang")
    rm -f "$bin"
    if "$zig" c++ "${clangtarget[@]}" "${WARN[@]}" -I "$inc" "$src" "$oscxx" -o "$(p "$bin")" "${libs[@]}" > "$out/clang.build.log" 2>&1 && [ -f "$bin" ]; then
        ok "clang: sys_test builds (zig $("$zig" version) ${clangtarget[*]})"
        check_run clang "$bin"
    else
        head -30 "$out/clang.build.log"
        fail "clang: sys_test does not build"
    fi

    aok=1
    for unit in "$src" "$oscxx"; do
        obj="$out/aarch64_$(basename "$unit" .cxx).o"
        rm -f "$obj"
        if ! "$zig" c++ -target aarch64-linux-gnu.2.35 "${WARN[@]}" -I "$inc" -c "$unit" -o "$(p "$obj")" > "$out/aarch64.log" 2>&1 || [ ! -f "$obj" ]; then
            head -30 "$out/aarch64.log"
            aok=0
        fi
    done
    if [ "$aok" = 1 ]; then
        ok "zig-aarch64: sys_test.cxx and os.cxx compile for aarch64-linux-gnu.2.35"
    else
        fail "zig-aarch64: sys_test.cxx or os.cxx does not compile"
    fi

    musl="$out/sys_musl"
    rm -f "$musl"
    if "$zig" c++ -target x86_64-linux-musl -static "${WARN[@]}" -I "$inc" "$src" "$oscxx" -o "$(p "$musl")" > "$out/musl.build.log" 2>&1 && [ -f "$musl" ]; then
        ok "musl: sys_test links, static, for x86_64-linux-musl"
        runner=()
        muslpath="$musl"
        if [ "$windows" = 1 ]; then
            if have wsl && wsl -l -q 2> /dev/null | tr -d '\0\r' | grep -qx 'docker-desktop'; then
                muslpath=$(cygpath -m "$musl" | sed -E 's#^([A-Za-z]):#/mnt/host/\L\1#')
                runner=(env MSYS2_ARG_CONV_EXCL='*' wsl -d docker-desktop -e env LC_ALL=de_DE.UTF-8)
            fi
        elif [ "$(uname -m)" = x86_64 ]; then
            runner=(env LC_ALL=de_DE.UTF-8)
        fi
        if [ "${#runner[@]}" -gt 0 ]; then
            check_run musl "$muslpath" "${runner[@]}"
        else
            echo "skip  musl: nowhere to run it (no docker-desktop WSL distro); built only"
            skips=$((skips + 1))
        fi
    else
        head -30 "$out/musl.build.log"
        fail "musl: sys_test does not build"
    fi
else
    missing clang "$zig"
    missing zig-aarch64 "$zig"
    missing musl "$zig"
fi

# ---- gcc and clang, on the same host, printed the same thing ---------------------------
if [ -f "$out/gcc.out" ] && [ -f "$out/clang.out" ]; then
    if diff <(lf "$out/gcc.out") <(lf "$out/clang.out") > "$out/clang.diff"; then
        ok "gcc and clang print the same output"
    else
        head -20 "$out/clang.diff"
        fail "gcc and clang disagree"
    fi
fi

echo
echo "sys.sh: $passes passed, $fails failed, $skips skipped"
[ "$fails" = 0 ]
