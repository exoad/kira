#!/usr/bin/env bash
# 13-ffi-cpp: a Kira program calling a C++ class and C functions through @_extern.
#
#     ./gradlew installDist && bash examples/cpp/13-ffi-cpp/run.sh
#
# kira --target cpp writes the generated tree (and the runtime) under a scratch
# directory; the C functions are compiled as C, the rest as C++20, and the
# program's stdout is diffed against expected.txt. $CC and $CXX override the
# compilers (the first of cc/gcc/clang and c++/g++/clang++ on PATH otherwise).
#
# The generated tree holds the whole runtime, kira/os.cxx included, and that
# file is Winsock on Windows and pthreads on Linux glibc (kira/os.hxx says so):
# a MinGW or zig windows-gnu link needs -lws2_32 (MSVC takes ws2_32.lib from a
# pragma), and a Linux link -pthread, as kira/cpp/tests/goldens.sh passes them.
set -euo pipefail

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) hostlink=(-lws2_32) ;;
    Linux) hostlink=(-pthread) ;;
    *) hostlink=() ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
kira="${KIRA:-$root/build/install/kira/bin/kira}"
if [[ ! -x "$kira" ]]; then
    echo "kira CLI not found at $kira; run ./gradlew installDist (or set KIRA=/path/to/kira)" >&2
    exit 1
fi

pick() { # pick VAR candidates...
    local var=$1
    shift
    if [[ -n "${!var:-}" ]]; then
        printf '%s' "${!var}"
        return
    fi
    local c
    for c in "$@"; do
        if command -v "$c" > /dev/null 2>&1; then
            printf '%s' "$c"
            return
        fi
    done
    echo "no $var found (set $var=/path/to/compiler)" >&2
    exit 1
}
cc_bin=$(pick CC cc gcc clang)
cxx_bin=$(pick CXX c++ g++ clang++)

work="$root/build/examples/13-ffi-cpp"
rm -rf "$work"
mkdir -p "$work"
cd "$here"
"$kira" --target cpp --out "$work/gen" > "$work/kira.log" 2>&1 || {
    cat "$work/kira.log" >&2
    echo "kira --target cpp failed" >&2
    exit 1
}
mapfile -t sources < <(find "$work/gen" -name '*.cxx' | LC_ALL=C sort)
if [[ ${#sources[@]} -eq 0 ]]; then
    echo "kira wrote no .cxx under $work/gen" >&2
    exit 1
fi
"$cc_bin" -std=c17 -O2 -Wall -Wextra -Werror -c "$here/native/cshape.c" -o "$work/cshape.o"
"$cxx_bin" -std=c++20 -O2 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Werror -ffp-contract=off \
    -I "$work/gen" -I "$here/native" "${sources[@]}" "$work/cshape.o" "${hostlink[@]}" -o "$work/app"
"$work/app" | tr -d '\r' > "$work/actual.txt"
if diff -u "$here/expected.txt" "$work/actual.txt"; then
    echo "13-ffi-cpp: ok"
else
    echo "13-ffi-cpp: stdout differs from expected.txt" >&2
    exit 1
fi
