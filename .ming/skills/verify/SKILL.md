---
name: verify
description: Full local test matrix for Kira - the same gates CI runs, reproduced by hand.
---

# Kira verify matrix

Triggers: "run the tests", "make sure everything passes", "check CI locally",
"is this change green", "pre-push verification".

CI (`.github/workflows/ci.yml`) runs four gates. Reproduce them in order:

## 1. Unit tests

```bash
./gradlew test
```

Covers frontend (lexer/parser/semantic), codegen, trait lowering, stdlib
lowering, foreign-edge pipeline, and LSP tests.

## 2. CLI install

```bash
./gradlew installDist
```

## 3. Example snapshots + behavior

```bash
./examples/regenerate.sh --check
```

Fails if any `generated.user.c` or `expected.txt` is stale, if any example
does not build or run, or if stdout drifted. This is the gate that catches
backend changes without refreshed snapshots.

## 4. Foreign edge

```bash
./examples/ffi-mini/run.sh
```

Quick ladder smoke:

```bash
./examples/run.sh             # all examples, emit + cc + run
./examples/run.sh 07-conway   # one example
```

If a codegen/runtime change deliberately alters emitted C, do NOT just run
--check: run `./examples/regenerate.sh` (no flag) to refresh snapshots, then
`--check` to confirm clean. See the examples-snapshots skill.
