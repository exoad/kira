# Testing

The Kira compiler has one full test suite, run with a single command:

```bash
./gradlew test
```

It covers the whole pipeline -- lexer, parser, semantic analysis, C codegen,
end-to-end runtime behavior, and the real CLI -- plus the legacy smoke tests
that predate it. Everything is JUnit 5 + kotlin.test on the JVM; no external
services, no network.

The frontend is Kotlin-native only: `KiraLexer` + `KiraParser` are the one
parser. The ANTLR grammar backend was removed (August 2026) -- there is no
generated grammar, no `ParserBackend` knob, and no second parser to keep in
sync. That deletion is what collapsed the suite's "both backends" loops into
single-frontend coverage.

## The suite at a glance

The comprehensive suite lives in
`src/test/kotlin/net/exoad/kira/suite/` as six focused test classes:

| Class | Tests | What it pins |
|-------|-------|--------------|
| `LexerSuiteTest` | 29 | Every literal form (dec/hex/float/string), keyword table, operators (incl. the conservative `>`-group), intrinsics, underscores, comments, source positions, and every lexer error path |
| `ParserSuiteTest` | 34 | Every declaration/statement/expression form the Kotlin-native parser accepts, generics and the closing-angle-bracket parity, plus malformed-program diagnostics and the unsupported-surface boundary |
| `SemanticSuiteTest` | 25 | Symbol declaration/resolution, scope stack, module URI validation, duplicate names, unknown types, literal/type mismatch, visibility, and `use` imports across real multi-file compilation units |
| `CodegenSuiteTest` | 24 | Emitted C **shape**: prelude substrate + facade, ARC hooks, function/global lowering, control flow, class struct + constructor + methods, enums, monomorphized generics, trait vtables, collections, externs |
| `RuntimeSuiteTest` | 21 | End-to-end: transpile Kira -> C, compile with the native toolchain, run the binary, assert **exact stdout** across the whole language ladder |
| `ParitySuiteTest` | 15 | Regressions for silent miscompiles (shifts, literals, escapes, enums, underscores, element reads, for-in, named arguments): each program runs on **both** the C and JS backends and must print the same exact text |
| `CliSuiteTest` | 6 | Spawns the real `net.exoad.kira.cli.MainKt` as a subprocess on throwaway projects: manifest load, emit, diagnostics exit codes, and running the produced binary |

A shared harness (`TestCompileSupport` in the parent package) drives the
frontend and backend for the suite.

## Running

```bash
./gradlew test            # everything
./gradlew test --tests "net.exoad.kira.suite.*"   # just the main suite
./gradlew test --tests "net.exoad.kira.suite.RuntimeSuiteTest"  # one class
```

Runtime and CLI tests need a C17 compiler (`clang`, `cc`, or `gcc`) on PATH,
and the JS tests need `node`. `$CC` / `$NODE` override the PATH search. On
Windows the harness resolves the real `.exe` (not the MSYS `/c/...` path
`which` prints), runs `bin/kira.bat` for the installed CLI, and strips the
`\r` a Windows C runtime adds to every line before comparing stdout.

Without a toolchain those tests **skip** (JUnit assumption) so the rest of
the suite still runs on toolchain-less machines. That is the *only* thing
that may skip: once a compiler is found, emitted C that fails to compile
**fails** the test. It used to be an assumption too, which let every
non-compiling lowering hide behind a green run.

CLI tests write scratch projects under `build/tmp/cli-suite/`; runtime tests
write scratch C under `build/tmp/c-run/` and `build/tmp/c-syntax/`. All of
that is build output, not source.

## What the suite deliberately pins as *broken*

Several tests document known compiler gaps rather than pretending they work.
If a gap gets fixed, its test starts failing -- that is the point. Each one
carries a comment naming the gap. Current set:

- **Compound assignment discards the result** (`CodegenSuiteTest`,
  `compoundAssignmentsAreCurrentlyEmittedAsDiscardedExpressions`): `a += 2`
  emits `(a + 2);` instead of `a = (a + 2);`. Behavior tests therefore avoid
  compound assignment.
- **Arithmetic lowering drops a term** (`RuntimeSuiteTest`,
  `arithmeticAndOrderOfOperations`): `1 + 2 * 3 - 4 / 2` currently evaluates to
  `5`, not `11`, so the runtime pin documents the actual behavior.
- **Class-class duplicate names pass silently** (`SemanticSuiteTest`,
  `classClassDuplicatesAreCurrentlySilentlyAccepted`): the class visitor
  early-returns when the type name already resolves.
- **Undeclared function calls are not diagnosed** (`SemanticSuiteTest`,
  `unknownFunctionReferenceIsCurrentlyNotDiagnosed`): function-call resolution
  is a TODO in the analyzer. (The one exception is a call that uses named
  arguments: those must bind to a resolvable callee's parameters, or they are
  diagnosed -- `namedArgumentsOnAnUnresolvableCalleeAreDiagnosed`.)
- **`@_extern` emits the C name as a literal string** (`CodegenSuiteTest`,
  `externLowersToLiteralCNamePlaceholder`): the intended call is not yet
  generated.
- **Nullable `?`, `this`, lambdas, `initially`/`finally` blocks are
  rejected** (`ParserSuiteTest` boundary tests): surface the Kotlin-native
  parser does not implement yet. (Bare `return` was on this list; it parses
  now -- `ParserSuiteTest.parsesBareReturn`.)
- **Generic call return types are not threaded into print format**
  (`CodegenSuiteTest`,
  `genericCallReturnTypeNotThreadedIntoPrintFormat`): monomorphization emits
  `id_Str`/`id_Int64` correctly, but the print-format heuristic at the call
  site falls back to `%d` for every generic call. Int32 works by luck;
  `id<Int64>(x)` prints wrong, `id<Str>(x)` prints a pointer as a number
  (`RuntimeSuiteTest.genericIdentityInstantiatesAcrossNumericTypes` covers
  the %d-safe happy path).
- **Nested containers are rejected by the C toolchain**
  (`RuntimeSuiteTest.nestedGenericArrayIsRejectedByCToolchain`): an `Arr` is
  wider than the 64-bit `KiraSlot` a container stores, so `cc` rejects the
  emitted C for `Arr<Arr<Int32>>`. The test asserts the emitted shape and
  that the compile fails -- it used to *silently skip* this case, hiding the
  limit behind green.

The older tests in `src/test/kotlin/net/exoad/kira/` and
`src/test/kotlin/net/exoad/tests/kira/` (parser smoke, stdlib lowering, trait
codegen, manifest/KIM, LSP paths, foreign-edge, function syntax, ARC, null
safety, and the JS codegen smoke test) still run in the same `./gradlew test`
gate. They are kept alongside the suite; new coverage belongs in
`net.exoad.kira.suite`.

## The C++ backend harness

`--target cpp` is tested by driving real C++ toolchains, not by scraping the
emitted text. The harness lives in `src/test/kotlin/net/exoad/kira/cpp/`:

| Class | What it does |
|-------|--------------|
| `support/CppToolchains` | Finds each toolchain (below) without `which`, probes it once, and applies `KIRA_TOOLCHAINS` / `KIRA_REQUIRE_TOOLCHAINS` |
| `support/CppCompileSupport` | `compile` with the design's warning contract, `run` with a timeout, `nmCheck` for freestanding objects; wipes every candidate output (exe, `.o`, `.obj`) before a compile so a stale binary can never pass |
| `support/CppGoldenCase` | Loads one golden case directory (format below) and validates it |
| `CppGoldenCompileTest` | For every golden case and every toolchain its `case.yaml` names: compile `expected/**` plus the driver against the runtime, run it where the toolchain links, diff stdout with `expected.txt`; `arm` objects are also nm-checked for heap, exception and RTTI symbols |
| `CppWarningContractTest` | Compiles a fixture with one known warning (an unused parameter) under every toolchain and asserts the compile **fails**, next to a clean control that must compile. This is the proof that `-Werror` and `/WX` are really on |

Run it alone with:

```bash
./gradlew test --tests 'net.exoad.kira.cpp.*'
```

### Toolchains

| Id | Tool | Flags (design 8.2) | Override |
|----|------|--------------------|----------|
| `gcc` | `g++` on PATH, else `C:/msys64/{ucrt64,mingw64}/bin` | `-std=c++20 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wnon-virtual-dtor -Werror -ffp-contract=off` | `KIRA_CXX_GCC` |
| `clang` | `clang++` on Linux/macOS; `zig c++` on Windows (LLVM clang 18 rejects the MSVC 14.44 STL) | the same | `KIRA_CXX_CLANG` (a path, or a command such as `zig c++`) |
| `zig-aarch64` | `zig c++ -target aarch64-linux-gnu.2.35`, compile only | the same | `KIRA_ZIG` (the zig binary) |
| `msvc` | `cl` after `vcvars64.bat`, found through `vswhere.exe`; Windows only | `/std:c++20 /W4 /WX /EHsc /permissive-` | `KIRA_MSVC_VCVARS` (the .bat) |
| `arm` | `arm-none-eabi-g++` on PATH, else `C:/msys64/{mingw64,ucrt64}/bin`; compile only, nm-checked | `-std=c++20 -mcpu=cortex-m33 -mthumb -Os -fno-exceptions -fno-rtti -Wall -Wextra -Wconversion -Werror -ffp-contract=off -DKIRA_PROFILE_FREESTANDING=1` | `KIRA_ARM_GXX` |

A freestanding case (`profile: freestanding`) adds `-DKIRA_PROFILE_FREESTANDING=1`
under every toolchain; `arm` always has it. MSVC runs through a generated
`compile.bat` (`call vcvars64.bat && cl ...`) because a quoted path with
spaces does not survive `cmd /c` on a single command line; its diagnostics
arrive on stdout, and the harness reads both streams.

Environment:

- `KIRA_TOOLCHAINS=gcc,clang,...` restricts the set. A toolchain outside the
  set is not required and not run.
- `KIRA_REQUIRE_TOOLCHAINS=1` makes a missing toolchain a test **failure**
  that names it. Without it a missing toolchain skips its tests (a JUnit
  assumption), which suits a laptop and never suits CI. `RuntimeSuiteTest`'s
  `assumeTrue` once turned non-compiling C into skipped tests; this is why.
- An override that points at nothing (`KIRA_ARM_GXX=/nonexistent`) is a
  missing toolchain, never a silent fallback to PATH. On Windows give
  overrides as Windows paths, not MSYS `/c/...` ones.
- `-Dkira.cppGoldenDir=<dir>` (or `KIRA_CPP_GOLDEN_DIR`) chooses the golden
  root(s), path-separator joined. By default both
  `src/test/resources/cpp-golden` (the corpus) and
  `src/test/resources/cpp-harness-selftest` (the harness's own two cases and
  the warning fixture, with a stand-in runtime under `include/`) run. Every
  root must yield at least one case; a default root that is absent from the
  checkout fails under `KIRA_REQUIRE_TOOLCHAINS=1` and skips visibly otherwise.
- `-Dkira.cppRuntimeDir=<dir>` chooses the runtime include dir; by default a
  root's own `include/` when it has one, else `kira/cpp`.

`build.gradle.kts` forwards the `kira.*` properties to the test JVM and
registers the variables above as task inputs, so changing one re-runs the
tests instead of replaying an up-to-date result.

### The golden format (design 5.10)

```
src/test/resources/cpp-golden/<case>/
  kira.yaml          layout beside, srcDir src, lineDirectives false
  src/**.kira        the sources
  expected/**        the generated tree, relative to the case root
  driver/main.cxx    prints bibo's check format ("N checks, M failed"); driver/*.hxx are fakes
  expected.txt       the driver's exact stdout
  case.yaml          toolchains: [gcc, clang, msvc, zig-aarch64, arm]
                     profile: hosted | freestanding
                     defines: []
                     emit: pending | required
```

`CppGoldenCompileTest` runs every case from wave 1 on; `emit: required` is
read by W2.2's `CppGoldenEmitTest`, which runs the compiler on `src/` and
diffs against `expected/`. The compile step's include roots are the case's
`expected/` and `driver/`, every directory under `expected/` that holds a
header, then the runtime, so a driver may write `#include "src/x.kira.hxx"`
or `#include "x.kira.hxx"`. A directory in a golden root without a
`case.yaml` is an error (a half-written case must not vanish); only
`include/` and names starting with `_` are exempt. Scratch output lands in
`build/tmp/cpp-harness/<root>/<case>/<toolchain>/`.

### The examples' C++ leg

`examples/regenerate.sh` runs a C++ leg for every example named in
`examples/cpp-legs.txt`: `kira --target cpp --out <tmp>`, then
`$CXX -std=c++20 -O2 -I kira/cpp`, then the binary's stdout is diffed
against `expected.txt` and against the C and JS legs. `--check` covers it
too, and a listed name that never ran fails the script.

## CI

CI (`.github/workflows/ci.yml`) runs the same four gates the `verify` skill
documents: `./gradlew test`, `./gradlew installDist`,
`./examples/regenerate.sh --check`, and `./examples/ffi-mini/run.sh`.
The unit-test gate is exactly the suite above.

Three more jobs run only the C++ harness, each with
`KIRA_REQUIRE_TOOLCHAINS=1` so nothing can skip:

- `cpp-linux` (ubuntu-24.04): g++, clang and gcc-arm-none-eabi from apt, zig
  0.15.2 via `mlugg/setup-zig`; `KIRA_TOOLCHAINS=gcc,clang,zig-aarch64,arm`.
- `cpp-gcc11` (container `ubuntu:22.04`): g++ 11.4, the board floor;
  `KIRA_TOOLCHAINS=gcc`.
- `cpp-msvc` (windows-latest): `KIRA_TOOLCHAINS=msvc`.

## Adding coverage

- New behavior tests go in the matching `*SuiteTest` class, or a new class
  under `net.exoad.kira.suite`.
- Prefer exact-stdout assertions in `RuntimeSuiteTest` over text-scraping the
  emitted C when the question is "does it work".
- Prefer `CodegenSuiteTest` shape assertions when the question is "what does
  it lower to".
- If you fix a known gap listed above, remove the "currently broken" pin and
  replace it with a real assertion of the fixed behavior.
