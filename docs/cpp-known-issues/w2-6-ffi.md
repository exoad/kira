# Known issues: w2-6-ffi (convergence round 1)

One entry per deferred issue: what, where, how to reproduce it, and why leaving it is safe.
Fixed issues from the last verdict are not listed here (see the round's commit message).

## A same-size twin field write is a C++ error, not Kira's message

**What.** Writing to an extern struct field whose C++ type is a same-size twin of the
declared one (a C `int` field on arm-none-eabi, an unscoped enum field), or passing such a
field as a `mut` argument, fails with a plain C++ "lvalue required as left operand of
assignment" (or MSVC's equivalent), not `"Kira's X.field no longer matches its C++
header"`. A field whose C++ type is the declared one writes as it always has (the member
itself, an lvalue).

**Where.** `kira/cpp/kira/ffi.hxx`'s `field<T, M>`: it returns the member itself only when
the C++ type equals `T`; when it is a twin, `field<T>(...)` is `static_cast<T>(...)`, a
prvalue, and prvalues do not bind to `T&` or pass through `kira::ffi::out(...)`.

**Reproduction.** A C `enum Mode { OFF, ON }; struct Cfg { Mode mode; };` declared in Kira
as `mode: Int32` (an extern enum is not a Kira declaration, so `Int32` is the only spelling);
`cfg.mode = 1` or `setMode(mut m: Int32)` called as `setMode(cfg.mode)` fails to compile,
since `kira::ffi::field<std::int32_t>(cfg.mode)` is a prvalue there.

**Why it is safe to defer.** This is consistent with design 7.2's rule that Kira never
takes `int&` to such a field (an enum could never take the integer back, so the write must
go through an extern function that assigns the enum from the integer, or Kira must read the
field, compute, and pass the result as a by-value argument instead of a `mut` one). The
failure is a compiler error at the point of use, not a silent miscompile: it names the
member and the `assignment`/`out<T>` proxy in the error, so a program that tries this does
not build, and building is required before it can run. No test in the suite writes such a
field. Fix, if wanted: a two-argument `field<T>` write path through an extern setter,
generated from the same struct declaration, is future work; nothing in the FFI rule
requires it now, since no in-tree program does this.

## gcc 11.4, the design's floor, is unmeasured this round

**What.** The design's minimum compiler (design 7, floor gcc 11.4) has not compiled this
round's `kira/ffi.hxx` changes (the round-5 `declared<T>`/`field<T>` split by
`std::is_scalar_v`, and this round's rvalue-aware `field<T, M&&>`).

**Where.** `kira/ffi.hxx` targets C++17/20 features already in use elsewhere in the file
(`if constexpr`, `decltype(auto)`, `std::optional`, a `__cpp_char8_t` guard); nothing added
this round is later than that, but it is asserted, not measured, on gcc 11.

**Reproduction.** N/A — there is no gcc 11 toolchain on this machine, and
`docker-desktop` (the usual way to reach one) was stopped when this was checked.

**Why it is safe to defer.** Every change in `field<T, M>` and `declared<T, V>` is ordinary
SFINAE and reference-collapsing surface, no different in kind from what gcc 11.4 already
builds elsewhere in this file (the `Matches`/`SigMatches` machinery, `if constexpr` chains).
gcc 13.2, zig clang 20 (≈ clang 20) and MSVC all build and run it (`kira/cpp/tests/run.sh`,
`msvc.bat`). Measuring gcc 11.4 specifically is worth doing before this ships to the Pi's
toolchain, not before this package converges.

## declared<T> costs one move for a class-typed result already of the declared type

**What.** `kira::ffi::declared<T>(call())` is `Declared<T>::from(static_cast<V&&>(v))`,
which for a class-typed `T` that already has the C++ type `T` (the check let it through
unconverted) is a move-construction the round-4 lowering did not pay (a prvalue of type `T`
was returned as-is, eliding the conversion).

**Where.** `kira/ffi.hxx`'s `declared<T, V>` (both overloads).

**Why it is safe to defer.** One move of a small handle type (an `Rc`, a struct with a
couple of scalar members) is not a correctness issue, and the alternative (special-casing
`T == V` to skip the wrapper) reintroduces the `is_convertible`-shaped hole the whole
`declared<T>` design closes for every other `T`. Scalars and pointers, the common case,
cost nothing (the cast is a no-op or a register move either way).

## The w2-3-emit-exprs merge conflict predates this round

**What.** `git merge-tree --write-tree --name-only cpp/w2-6-ffi cpp/w2-3-emit-exprs` (head
42f1b7f) conflicts in `CppDeclEmitter.kt`: this branch's `extern "C"` include block (rounds
1-4) against W2.3's `includeLine`. `cpp/w2-4-emit-oop`, `cpp/w2-5-rules` and `cpp/w2-7-sys`
merge cleanly.

**Where.** `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/cpp/CppDeclEmitter.kt`
(W2.2's file; this branch's earlier rounds and W2.3 both touch the header's include list).

**Why it is safe to defer.** It is a merge-order conflict between two sibling packages'
branches, not a defect in either one's code on its own branch: this package's own
`./gradlew test` and every acceptance command pass standalone. Resolution (keep this
branch's `extern "C"` block, write each include with W2.3's `includeLine(it)`) is for
`cpp-backend`'s merge step, and was already trialled clean against W2.3 42f1b7f plus that
one resolution.
