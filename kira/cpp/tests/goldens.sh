#!/usr/bin/env bash
# goldens.sh - the C++ golden corpus (src/test/resources/cpp-golden) against kira/cpp.
#
#     bash kira/cpp/tests/goldens.sh [case ...]
#
# Each case's expected/**/*.cxx and driver/*.cxx are compiled with the include path
# kira/cpp, then <case>/driver, with every toolchain its case.yaml names:
#   gcc, clang, msvc   build, run, and diff stdout (CR stripped) with expected.txt;
#                      the run must also exit 0
#   zig-aarch64        compile and link for aarch64-linux-gnu.2.35 (nothing runs it)
#   arm                arm-none-eabi-g++ -DKIRA_PROFILE_FREESTANDING=1 -c each file;
#                      arm-none-eabi-nm must show no heap, exception or RTTI symbol
# gcc and clang use -std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow
# -Wnon-virtual-dtor -Werror; msvc is kira\cpp\tests\msvc.bat's /W4 /WX.
#
# Overrides: KIRA_CXX_GCC, KIRA_CXX_CLANG (a whole command, default `zig c++`),
# KIRA_ZIG, KIRA_ARM_GXX. KIRA_TOOLCHAINS=gcc,msvc,... runs only those.
# KIRA_REQUIRE_TOOLCHAINS=1 makes a missing toolchain a failure instead of a skip.
# Outputs: build/cpp-tests/goldens.
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
corpus="$root/src/test/resources/cpp-golden"
out="$root/build/cpp-tests/goldens"
mkdir -p "$out"

WARN=(-std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror)

passes=0
fails=0
skips=0
ok() { echo "ok    $*"; passes=$((passes + 1)); }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
skip() {
    if [ "${KIRA_REQUIRE_TOOLCHAINS:-0}" = 1 ]; then
        fail "$1: not found, and KIRA_REQUIRE_TOOLCHAINS=1"
    else
        echo "skip  $1: not found"
        skips=$((skips + 1))
    fi
}

windows=0
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) windows=1 ;; esac
p() { if [ "$windows" = 1 ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
have() { command -v "$1" > /dev/null 2>&1 || [ -x "$1" ]; }
exe() { if [ "$windows" = 1 ]; then printf '%s.exe' "$1"; else printf '%s' "$1"; fi; }
lf() { tr -d '\r' < "$1"; }

inc=$(p "$root/kira/cpp")

# ---- toolchains -------------------------------------------------------------------
gxx=${KIRA_CXX_GCC:-g++}
zig=${KIRA_ZIG:-zig}
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
armnm=${arm%g++*}nm${arm##*g++}
if have "$zig"; then
    export ZIG_LOCAL_CACHE_DIR="${ZIG_LOCAL_CACHE_DIR:-$(p "$out/zig-cache")}"
    export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$(p "$out/zig-cache")}"
fi
if [ "$windows" = 1 ]; then clangtarget=(-target x86_64-windows-gnu); else clangtarget=(); fi
if [ -n "${KIRA_CXX_CLANG:-}" ]; then
    read -r -a clangcmd <<< "$KIRA_CXX_CLANG"
else
    clangcmd=("$zig" c++ "${clangtarget[@]}")
fi
wanted() { [ -z "${KIRA_TOOLCHAINS:-}" ] || printf ',%s,' "$KIRA_TOOLCHAINS" | grep -q ",$1,"; }
msvc_found() { [ "$windows" = 1 ] && [ -f "C:/Users/error/Code/bibo-kira/tools/find_vs.bat" ]; }

# ---- case.yaml: flow lists and scalars only -----------------------------------------
yaml_scalar() { sed -n -E "s/^$2:[[:space:]]*([^#]*[^[:space:]#]).*$/\1/p" "$1" | head -1; }
yaml_list() {
    sed -n -E "s/^$2:[[:space:]]*\[(.*)\].*$/\1/p" "$1" | head -1 | tr ',' '\n' |
        sed -E 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^"(.*)"$/\1/' | grep -v '^$'
}

# ---- one case, one toolchain ----------------------------------------------------------
# Every output is deleted before its build, so a stale binary never passes.
run_hosted() {
    local name=$1 dir=$2 tc=$3 bin=$4
    local log="$out/$name/$tc"
    "$bin" > "$log.stdout" 2> "$log.stderr"
    local rc=$?
    if ! diff <(lf "$dir/expected.txt") <(lf "$log.stdout") > "$log.diff"; then
        head -20 "$log.diff"
        fail "$name/$tc: stdout differs from expected.txt (exit $rc)"
    elif [ "$rc" != 0 ]; then
        fail "$name/$tc: stdout matches, but the run exited $rc"
    else
        ok "$name/$tc: $(lf "$log.stdout" | grep -E '^[0-9]+ checks, [0-9]+ failed$' | tail -1)"
    fi
}

build_gnu() {
    local name=$1 dir=$2 tc=$3
    shift 3
    local bin
    bin=$(exe "$out/$name/$tc")
    rm -f "$bin"
    if "$@" "${WARN[@]}" "${defs[@]}" -I "$inc" -I "$(p "$dir/driver")" "${srcs[@]}" -o "$(p "$bin")" > "$out/$name/$tc.log" 2>&1 && [ -f "$bin" ]; then
        run_hosted "$name" "$dir" "$tc" "$bin"
    else
        grep -v '^[[:space:]]*|' "$out/$name/$tc.log" | head -30
        fail "$name/$tc: does not build"
    fi
}

build_msvc() {
    local name=$1 dir=$2
    local bin="$out/$name/msvc.exe" objdir="$out/$name/msvc-obj"
    local rsp="$out/$name/msvc.rsp"
    rm -f "$bin"
    rm -rf "$objdir"
    mkdir -p "$objdir"
    {
        printf '/I"%s"\n' "$(cygpath -w "$root/kira/cpp")"
        printf '/I"%s"\n' "$(cygpath -w "$dir/driver")"
        local d
        for d in "${defines[@]}"; do printf '/D"%s"\n' "$d"; done
        local s
        for s in "${srcs[@]}"; do printf '"%s"\n' "$(cygpath -w "$s")"; done
        printf '/Fe"%s"\n' "$(cygpath -w "$bin")"
        printf '/Fo"%s\\\\"\n' "$(cygpath -w "$objdir")"
    } > "$rsp"
    if cmd //c "$(cygpath -w "$here/msvc.bat")" cl "@$(cygpath -w "$rsp")" > "$out/$name/msvc.log" 2>&1 && [ -f "$bin" ]; then
        run_hosted "$name" "$dir" msvc "$bin"
    else
        grep -E 'error|warning' "$out/$name/msvc.log" | grep -v '^[[:space:]]*$' | head -30
        fail "$name/msvc: does not build"
    fi
}

build_aarch64() {
    local name=$1 dir=$2
    local bin="$out/$name/aarch64"
    rm -f "$bin"
    if "$zig" c++ -target aarch64-linux-gnu.2.35 "${WARN[@]}" "${defs[@]}" -I "$inc" -I "$(p "$dir/driver")" "${srcs[@]}" -o "$(p "$bin")" > "$out/$name/zig-aarch64.log" 2>&1 && [ -f "$bin" ]; then
        ok "$name/zig-aarch64: compiles and links for aarch64-linux-gnu.2.35"
    else
        grep -v '^[[:space:]]*|' "$out/$name/zig-aarch64.log" | head -30
        fail "$name/zig-aarch64: does not build"
    fi
}

build_arm() {
    local name=$1 dir=$2
    local s obj bad all_ok=1 objs=0
    for s in "${srcs[@]}"; do
        obj="$out/$name/arm-$(basename "$s").o"
        rm -f "$obj"
        if ! "$arm" -std=c++20 -mcpu=cortex-m33 -mthumb -Os -fno-exceptions -fno-rtti -DKIRA_PROFILE_FREESTANDING=1 \
            -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror "${defs[@]}" \
            -I "$inc" -I "$(p "$dir/driver")" -c "$s" -o "$(p "$obj")" > "$out/$name/arm.log" 2>&1 || [ ! -f "$obj" ]; then
            grep -v '^[[:space:]]*|' "$out/$name/arm.log" | head -30
            fail "$name/arm: $(basename "$s") does not compile freestanding"
            all_ok=0
            continue
        fi
        objs=$((objs + 1))
        bad=$("$armnm" "$obj" | awk 'NF >= 2 { print $NF }' |
            grep -E '^_?(malloc|free|calloc|realloc|_Znw|_Zna|_Zdl|_Zda|__cxa|_Unwind|_ZTI|_ZTS|__gxx_personality)' | sort -u | tr '\n' ' ')
        bad="$bad$("$armnm" -C "$obj" | grep -i 'typeinfo' | tr '\n' ' ')"
        if [ -n "$bad" ]; then
            fail "$name/arm: $(basename "$s") has forbidden symbols: $bad"
            all_ok=0
        fi
    done
    if [ "$all_ok" = 1 ]; then
        ok "$name/arm: $objs objects compile for cortex-m33, none with malloc/free/_Znw/_Znam/_Zdl/__cxa/_Unwind/typeinfo"
    fi
}

# ---- the corpus ----------------------------------------------------------------------------
if [ "$#" -gt 0 ]; then
    names=("$@")
else
    names=()
    for d in "$corpus"/*/; do
        names+=("$(basename "$d")")
    done
fi
if [ "${#names[@]}" = 0 ]; then
    fail "no golden cases under $corpus"
fi

for name in "${names[@]}"; do
    dir="$corpus/$name"
    mkdir -p "$out/$name"
    missing=""
    for f in kira.yaml case.yaml expected.txt driver/main.cxx; do
        [ -f "$dir/$f" ] || missing="$missing $f"
    done
    [ -n "$(find "$dir/src" -name '*.kira' 2> /dev/null)" ] || missing="$missing src/**.kira"
    [ -n "$(find "$dir/expected" -type f 2> /dev/null)" ] || missing="$missing expected/**"
    if [ -n "$missing" ]; then
        fail "$name: the case lacks$missing"
        continue
    fi
    emit=$(yaml_scalar "$dir/case.yaml" emit)
    profile=$(yaml_scalar "$dir/case.yaml" profile)
    if [ "$emit" != pending ] && [ "$emit" != required ]; then
        fail "$name: case.yaml emit is '$emit', not pending or required"
        continue
    fi
    if [ "$profile" != hosted ] && [ "$profile" != freestanding ]; then
        fail "$name: case.yaml profile is '$profile', not hosted or freestanding"
        continue
    fi
    mapfile -t toolchains < <(yaml_list "$dir/case.yaml" toolchains)
    mapfile -t defines < <(yaml_list "$dir/case.yaml" defines)
    defs=()
    for d in "${defines[@]}"; do defs+=("-D$d"); done
    srcs=()
    while IFS= read -r s; do srcs+=("$(p "$s")"); done < <(find "$dir/expected" -name '*.cxx' | LC_ALL=C sort)
    while IFS= read -r s; do srcs+=("$(p "$s")"); done < <(find "$dir/driver" -maxdepth 1 -name '*.cxx' | LC_ALL=C sort)
    if [ "${#toolchains[@]}" = 0 ]; then
        fail "$name: case.yaml names no toolchain"
        continue
    fi
    for tc in "${toolchains[@]}"; do
        if ! wanted "$tc"; then
            continue
        fi
        case "$tc" in
            gcc) if have "$gxx"; then build_gnu "$name" "$dir" gcc "$gxx"; else skip "$name/gcc ($gxx)"; fi ;;
            clang) if have "${clangcmd[0]}"; then build_gnu "$name" "$dir" clang "${clangcmd[@]}"; else skip "$name/clang (${clangcmd[0]})"; fi ;;
            msvc) if msvc_found; then build_msvc "$name" "$dir"; else skip "$name/msvc (find_vs.bat)"; fi ;;
            zig-aarch64) if have "$zig"; then build_aarch64 "$name" "$dir"; else skip "$name/zig-aarch64 ($zig)"; fi ;;
            arm) if have "$arm"; then build_arm "$name" "$dir"; else skip "$name/arm ($arm)"; fi ;;
            *) fail "$name: unknown toolchain '$tc' in case.yaml" ;;
        esac
    done
done

echo
echo "goldens.sh: ${#names[@]} cases; $passes passed, $fails failed, $skips skipped"
[ "$fails" = 0 ]
