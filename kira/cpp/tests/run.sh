#!/usr/bin/env bash
# run.sh - the Kira C++ runtime test (rt_test.cxx) on every toolchain found.
#
#     bash kira/cpp/tests/run.sh
#
# Builds, each from a clean output (a stale binary never passes for a fresh one):
#   gcc            g++ -std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion
#                  -Wshadow -Wnon-virtual-dtor -Werror; runs it, the panic modes,
#                  the exit-70 mode and the must-not-compile cases
#   clang          zig c++ (clang 20), -target x86_64-windows-gnu on Windows; the same
#   zig-aarch64    zig c++ -target aarch64-linux-gnu.2.35 -c (the Orange Pi, the Jetson)
#   musl           zig c++ -target x86_64-linux-musl -static, run under
#                  LC_ALL=de_DE.UTF-8: natively on Linux, in `wsl -d docker-desktop`
#                  on Windows when that distro exists; its output must equal gcc's
#   arm            arm-none-eabi-g++ -mcpu=cortex-m33 -mthumb -Os -fno-exceptions
#                  -fno-rtti -DKIRA_PROFILE_FREESTANDING=1 -c; arm-none-eabi-nm must
#                  show no heap, exception or RTTI symbol
#
# Overrides: KIRA_CXX_GCC, KIRA_ZIG, KIRA_ARM_GXX. KIRA_REQUIRE_TOOLCHAINS=1 makes a
# missing toolchain a failure instead of a skip. MSVC is kira\cpp\tests\msvc.bat.
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out="$root/build/cpp-tests/run"
mkdir -p "$out"

WARN=(-std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror)
PANICS=(div mod overflow shl shr index view slice list unwrap rc substring strat result assert)

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
# Native tools on Windows get Windows paths, never MSYS ones.
p() { if [ "$windows" = 1 ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

inc=$(p "$root/kira/cpp")
src=$(p "$here/rt_test.cxx")

have() { command -v "$1" > /dev/null 2>&1 || [ -x "$1" ]; }
exe() { if [ "$windows" = 1 ]; then printf '%s.exe' "$1"; else printf '%s' "$1"; fi; }

# Output without CR: a Windows program's stdout is in text mode.
lf() { tr -d '\r' < "$1"; }

# Runs one hosted build: the suite, then the panic modes and the exit-70 mode.
check_run() {
    local name=$1 bin=$2
    local log="$out/$name.out" err="$out/$name.err"
    "$bin" > "$log" 2> "$err"
    local rc=$?
    local last
    last=$(lf "$log" | grep -E '^[0-9]+ checks, [0-9]+ failed$' | tail -1)
    if [ "$rc" = 0 ] && printf '%s' "$last" | grep -qE '^[0-9]+ checks, 0 failed$'; then
        ok "$name: rt_test runs: $last"
    else
        lf "$log" | grep -E '^  FAIL' | head -20
        fail "$name: rt_test exited $rc (${last:-no summary line})"
    fi
    grep -h 'the trap is' "$err" | sed "s/^/      $name: /"
    local what
    for what in "${PANICS[@]}"; do
        "$bin" panic "$what" > "$out/$name.panic.out" 2> "$out/$name.panic.err"
        rc=$?
        if [ "$rc" != 0 ] && grep -q '^kira: ' "$out/$name.panic.err" && ! grep -q 'did not panic' "$out/$name.panic.out"; then
            ok "$name: panic $what: $(grep -h '^kira: ' "$out/$name.panic.err" | tr -d '\r' | head -1)"
        else
            fail "$name: panic $what exited $rc without a kira: line"
        fi
    done
    "$bin" throw > "$out/$name.throw.out" 2> "$out/$name.throw.err"
    rc=$?
    if [ "$rc" = 70 ] && lf "$out/$name.throw.err" | grep -qx 'kira: boom'; then
        ok "$name: an uncaught kira::Error exits 70 with its message"
    else
        fail "$name: the throw mode exited $rc"
    fi
}

# A failing check inside a static_assert must not compile, and must fail
# because evaluation reached kira::panic.
check_negative() {
    local name=$1
    shift
    local n
    for n in 1 2 3 4 5 6; do
        rm -f "$out/$name.neg$n.o"
        if "$@" -DKIRA_RT_NEGATIVE=$n -c "$src" -o "$(p "$out/$name.neg$n.o")" > "$out/$name.neg$n.log" 2>&1; then
            fail "$name: KIRA_RT_NEGATIVE=$n compiled: a failing check passed a static_assert"
        elif grep -q 'panic' "$out/$name.neg$n.log"; then
            ok "$name: KIRA_RT_NEGATIVE=$n does not compile: its check reaches kira::panic"
        else
            head -5 "$out/$name.neg$n.log"
            fail "$name: KIRA_RT_NEGATIVE=$n failed to compile for another reason"
        fi
    done
}

# No heap, exception or RTTI symbol in a freestanding object.
check_symbols() {
    local name=$1 nm=$2 obj=$3
    local bad
    bad=$("$nm" "$obj" | awk 'NF >= 2 { print $NF }' |
        grep -E '^_?(malloc|free|calloc|realloc|_Znw|_Zna|_Zdl|_Zda|__cxa|_Unwind|_ZTI|_ZTS|__gxx_personality)' | sort -u | tr '\n' ' ')
    bad="$bad$("$nm" -C "$obj" | grep -i 'typeinfo' | tr '\n' ' ')"
    if [ -z "$bad" ]; then
        ok "$name: no malloc/free/_Znw/_Znam/_Zdl/__cxa/_Unwind/typeinfo in $(basename "$obj")"
    else
        fail "$name: forbidden symbols: $bad"
    fi
}

# ---- gcc -------------------------------------------------------------------------
gxx=${KIRA_CXX_GCC:-g++}
if have "$gxx"; then
    bin=$(exe "$out/rt_gcc")
    rm -f "$bin"
    if "$gxx" "${WARN[@]}" -I "$inc" "$src" -o "$(p "$bin")" > "$out/gcc.build.log" 2>&1 && [ -f "$bin" ]; then
        ok "gcc: rt_test builds ($("$gxx" -dumpfullversion 2> /dev/null || "$gxx" -dumpversion))"
        check_run gcc "$bin"
    else
        head -30 "$out/gcc.build.log"
        fail "gcc: rt_test does not build"
    fi
    check_negative gcc "$gxx" "${WARN[@]}" -I "$inc"
    fsobj="$out/rt_fs_gcc.o"
    rm -f "$fsobj"
    if "$gxx" "${WARN[@]}" -fno-exceptions -fno-rtti -DKIRA_PROFILE_FREESTANDING=1 -I "$inc" -c "$src" -o "$(p "$fsobj")" > "$out/gcc.fs.log" 2>&1; then
        ok "gcc: the freestanding profile compiles on the host compiler"
        check_symbols gcc nm "$fsobj"
    else
        head -20 "$out/gcc.fs.log"
        fail "gcc: the freestanding profile does not compile"
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
    bin=$(exe "$out/rt_clang")
    rm -f "$bin"
    if "$zig" c++ "${clangtarget[@]}" "${WARN[@]}" -I "$inc" "$src" -o "$(p "$bin")" > "$out/clang.build.log" 2>&1 && [ -f "$bin" ]; then
        ok "clang: rt_test builds (zig $("$zig" version) ${clangtarget[*]})"
        check_run clang "$bin"
    else
        head -30 "$out/clang.build.log"
        fail "clang: rt_test does not build"
    fi
    check_negative clang "$zig" c++ "${clangtarget[@]}" "${WARN[@]}" -I "$inc"

    obj="$out/rt_aarch64.o"
    rm -f "$obj"
    if "$zig" c++ -target aarch64-linux-gnu.2.35 "${WARN[@]}" -I "$inc" -c "$src" -o "$(p "$obj")" > "$out/aarch64.log" 2>&1 && [ -f "$obj" ]; then
        ok "zig-aarch64: rt_test compiles for aarch64-linux-gnu.2.35"
    else
        head -30 "$out/aarch64.log"
        fail "zig-aarch64: rt_test does not compile"
    fi

    musl="$out/rt_musl"
    rm -f "$musl"
    if "$zig" c++ -target x86_64-linux-musl -static "${WARN[@]}" -I "$inc" "$src" -o "$(p "$musl")" > "$out/musl.build.log" 2>&1 && [ -f "$musl" ]; then
        ok "musl: rt_test links, static, for x86_64-linux-musl"
        runner=()
        if [ "$windows" = 1 ]; then
            if have wsl && wsl -l -q 2> /dev/null | tr -d '\0\r' | grep -qx 'docker-desktop'; then
                linux=$(cygpath -m "$musl" | sed -E 's#^([A-Za-z]):#/mnt/host/\L\1#')
                runner=(env MSYS2_ARG_CONV_EXCL='*' wsl -d docker-desktop -e env LC_ALL=de_DE.UTF-8 "$linux")
            fi
        elif [ "$(uname -m)" = x86_64 ]; then
            runner=(env LC_ALL=de_DE.UTF-8 "$musl")
        fi
        if [ "${#runner[@]}" -gt 0 ]; then
            "${runner[@]}" > "$out/musl.out" 2> "$out/musl.err"
            rc=$?
            # wsl writes its own notices to stderr in UTF-16.
            tr -d '\000' < "$out/musl.err" | grep -a 'the trap is' | sed 's/^/      musl: /'
            if [ "$rc" = 0 ] && grep -qE '^[0-9]+ checks, 0 failed$' "$out/musl.out"; then
                ok "musl: rt_test runs under LC_ALL=de_DE.UTF-8: $(grep -E 'checks, ' "$out/musl.out" | tail -1)"
            else
                fail "musl: rt_test exited $rc under LC_ALL=de_DE.UTF-8"
            fi
            if [ -f "$out/gcc.out" ]; then
                if diff <(lf "$out/gcc.out") <(lf "$out/musl.out") > "$out/musl.diff"; then
                    ok "musl: its output under de_DE is byte for byte gcc's"
                else
                    head -20 "$out/musl.diff"
                    fail "musl: the locale-free output changed under de_DE"
                fi
            fi
        else
            echo "skip  musl: nowhere to run it (no docker-desktop WSL distro); built only"
            skips=$((skips + 1))
        fi
    else
        head -30 "$out/musl.build.log"
        fail "musl: rt_test does not build"
    fi
else
    missing clang "$zig"
    missing zig-aarch64 "$zig"
    missing musl "$zig"
fi

# ---- arm-none-eabi: the Pico's freestanding profile ----------------------------------
arm=${KIRA_ARM_GXX:-}
if [ -z "$arm" ]; then
    if have arm-none-eabi-g++; then
        arm=arm-none-eabi-g++
    elif [ -x /c/msys64/mingw64/bin/arm-none-eabi-g++.exe ]; then
        arm=/c/msys64/mingw64/bin/arm-none-eabi-g++.exe
    else
        arm=arm-none-eabi-g++
    fi
fi
if have "$arm"; then
    armnm=${arm%g++*}nm${arm##*g++}
    obj="$out/rt_arm.o"
    rm -f "$obj"
    if "$arm" -std=c++20 -mcpu=cortex-m33 -mthumb -Os -fno-exceptions -fno-rtti -DKIRA_PROFILE_FREESTANDING=1 \
        -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror \
        -I "$inc" -c "$src" -o "$(p "$obj")" > "$out/arm.log" 2>&1 && [ -f "$obj" ]; then
        ok "arm: the freestanding probe compiles for cortex-m33 ($("$arm" -dumpversion))"
        check_symbols arm "$armnm" "$obj"
        undefined=$("$armnm" -u "$obj" | awk '{ print $NF }' | grep -v '^__aeabi_' | tr '\n' ' ')
        echo "      arm: undefined symbols besides libgcc's __aeabi_*: ${undefined:-none}"
    else
        head -30 "$out/arm.log"
        fail "arm: the freestanding probe does not compile"
    fi
else
    missing arm "$arm"
fi

# ---- every hosted run printed the same thing -------------------------------------------
if [ -f "$out/gcc.out" ] && [ -f "$out/clang.out" ]; then
    if diff <(lf "$out/gcc.out") <(lf "$out/clang.out") > "$out/clang.diff"; then
        ok "gcc and clang print the same output"
    else
        head -20 "$out/clang.diff"
        fail "gcc and clang disagree"
    fi
fi

echo
echo "run.sh: $passes passed, $fails failed, $skips skipped"
[ "$fails" = 0 ]
