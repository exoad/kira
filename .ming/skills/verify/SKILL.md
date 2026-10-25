---
name: verify
description: Full local test matrix for Kira - the same gates CI runs, reproduced by hand.
---

# Kira verify matrix

Triggers: "run the tests", "make sure everything passes", "check CI locally",
"is this change green", "pre-push verification".

CI's build job (`.github/workflows/ci.yml`) runs two gates; its cpp jobs run
the C++ harness per toolchain. Reproduce them in order:

## 1. Unit tests

```bash
./gradlew test
```

Covers frontend (lexer/parser/semantic), codegen, trait lowering, stdlib
lowering, foreign-edge pipeline, and LSP tests, and the end-to-end runs:

- `TestProjectsRunTest`: the test projects (`src/test/resources/test-projects/0N-name`)
  compiled by the CLI and run on C, JS, and for some of them py and C++, each
  stdout diffed with its `expected.txt`;
- `ForeignEdgePipelineTest`: `test-projects/ffi-mini` (`@_opaque`, `@_extern`,
  a C source of its own) compiled and run through cc.

A missing toolchain (cc, node, Python, g++) skips only that leg's run; with
all four on PATH nothing there skips. Just those runs:

```bash
./gradlew test --tests 'net.exoad.kira.TestProjectsRunTest' --tests 'net.exoad.kira.ForeignEdgePipelineTest'
```

## 2. CLI install

```bash
./gradlew installDist
```

If a codegen/runtime change deliberately alters what a test project prints,
update its `expected.txt` from a checked run in the same commit (see the
snapshots rule).
