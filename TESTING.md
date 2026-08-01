# Testing

The Kira compiler has one full test suite, run with a single command:

```bash
./gradlew test
```

It covers the whole pipeline -- lexer, parser, semantic analysis, C codegen,
end-to-end runtime behavior, and the real CLI -- plus the legacy smoke tests
that predate it. Everything is JUnit 5 + kotlin.test on the JVM; no external
services, no network.

## The suite at a glance

The comprehensive suite lives in
`src/test/kotlin/net/exoad/kira/suite/` as six focused test classes:

| Class | Tests | What it pins |
|-------|-------|--------------|
| `LexerSuiteTest` | 28 | Every literal form (dec/hex/float/string), keyword table, operators (incl. the conservative `>`-group), intrinsics, underscores, comments, source positions, and every lexer error path |
| `ParserSuiteTest` | 32 | Every declaration/statement/expression form the grammar accepts, run through **both** the legacy LL(k) parser and the ANTLR parser, plus malformed-program diagnostics and the unsupported-surface boundary |
| `SemanticSuiteTest` | 25 | Symbol declaration/resolution, scope stack, module URI validation, duplicate names, unknown types, literal/type mismatch, visibility, and `use` imports across real multi-file compilation units |
| `CodegenSuiteTest` | 22 | Emitted C **shape**: prelude substrate + facade, ARC hooks, function/global lowering, control flow, class struct + constructor + methods, enums, monomorphized generics, trait vtables, collections, externs |
| `RuntimeSuiteTest` | 19 | End-to-end: transpile Kira -> C, compile with the native toolchain, run the binary, assert **exact stdout** across the whole language ladder |
| `CliSuiteTest` | 6 | Spawns the real `net.exoad.kira.cli.MainKt` as a subprocess on throwaway projects: manifest load, emit, diagnostics exit codes, and running the produced binary |

A shared harness (`TestCompileSupport` in the parent package) drives the
frontend and backend for the suite.

## Running

```bash
./gradlew test            # everything
./gradlew test --tests "net.exoad.kira.suite.*"   # just the main suite
./gradlew test --tests "net.exoad.kira.suite.RuntimeSuiteTest"  # one class
```

Runtime and CLI tests need a C17 compiler (`clang`, `cc`, or `gcc`) on PATH.
Without one they **skip** (JUnit assumption) rather than fail, so the rest of
the suite still runs on toolchain-less machines.

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
  is a TODO in the analyzer.
- **`@_extern` emits the C name as a literal string** (`CodegenSuiteTest`,
  `externLowersToLiteralCNamePlaceholder`): the intended call is not yet
  generated.
- **Bare `return`, nullable `?`, `this`, lambdas, `initially`/`finally`
  blocks are rejected** (`ParserSuiteTest` boundary tests): grammar surface
  that the ANTLR grammar declares but the legacy parser does not implement yet.
- **ANTLR rejects nested generics that close with `>>`** (`ParserSuiteTest`,
  `nestedGenericsRejectedByAntlrBackend`): the ANTLR lexer greedily lexes
  `>>` as one `OP_SHR` token, but the parser grammar only accepts `GT` to
  close a `typeArguments` list. The classic C++ pre-11 `> >` problem: the
  legacy backend solved it with conservative single-`>` lexing (verified by
  `LexerSuiteTest.nestedGenericClosersLexAsIndividualAngles` and
  `ParserSuiteTest.nestedGenericsParseDeepOnLegacyBackend`), but the ANTLR
  gate has not. Single-level generics, comparisons next to generics, and
  explicit type-argument calls all work on both backends.
- **Generic call return types are not threaded into print format**
  (`CodegenSuiteTest`,
  `genericCallReturnTypeNotThreadedIntoPrintFormat`): monomorphization emits
  `id_Str`/`id_Int64` correctly, but the print-format heuristic at the call
  site falls back to `%d` for every generic call. Int32 works by luck;
  `id<Int64>(x)` prints wrong, `id<Str>(x)` prints a pointer as a number
  (`RuntimeSuiteTest.genericIdentityInstantiatesAcrossNumericTypes` covers
  the %d-safe happy path).

The older tests in `src/test/kotlin/net/exoad/kira/` and
`src/test/kotlin/net/exoad/tests/kira/` (parser smoke, stdlib lowering, trait
codegen, manifest/KIM, LSP paths, foreign-edge, function syntax, and the
JS codegen smoke test) still run in the same `./gradlew test` gate. They are
kept alongside the suite; new coverage belongs in `net.exoad.kira.suite`.

## CI

CI (`.github/workflows/ci.yml`) runs the same four gates the `verify` skill
documents: `./gradlew test`, `./gradlew installDist`,
`./examples/regenerate.sh --check`, and `./examples/ffi-mini/run.sh`.
The unit-test gate is exactly the suite above.

## Adding coverage

- New behavior tests go in the matching `*SuiteTest` class, or a new class
  under `net.exoad.kira.suite`.
- Prefer exact-stdout assertions in `RuntimeSuiteTest` over text-scraping the
  emitted C when the question is "does it work".
- Prefer `CodegenSuiteTest` shape assertions when the question is "what does
  it lower to".
- If you fix a known gap listed above, remove the "currently broken" pin and
  replace it with a real assertion of the fixed behavior.
