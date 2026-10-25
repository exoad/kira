---
name: backend-c-change
description: Safe loop for changing the C backend (codegen or runtime prelude), including the test projects' runs and commit.
---

# C backend change loop

Triggers: "change the codegen", "fix lowering", "edit KiraCCodeGenerator",
"update the runtime", "emitted C looks wrong", "a test project's output differs".

The files that affect emitted C:

- `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/c/KiraCCodeGenerator.kt`
- `.../c/CMagicTypeLowering.kt`
- `.../c/CIntrinsicsTable.kt`
- `src/main/resources/c_generator.c` (facade + runtime)
- `src/main/resources/c_bundle.h` (substrate)
- `kira/*.kira` (stdlib surface signatures)

## Loop

```bash
# 1. Edit, then the end-to-end runs: the test projects through C, JS, py and C++,
#    and ffi-mini through cc (each needs its toolchain on PATH, or skips its run)
./gradlew test --tests 'net.exoad.kira.TestProjectsRunTest' --tests 'net.exoad.kira.ForeignEdgePipelineTest'

# 2. The whole suite
./gradlew test
```

## Commit rules

- A change that deliberately alters what a test project prints updates its
  `src/test/resources/test-projects/0N-name/expected.txt` in the SAME commit,
  by hand, from a run you have checked.
- If only docs or tests changed (no emitted-C impact), expected.txt stays untouched.

## Gotchas

- The prelude must stay byte-identical across programs; a change that makes
  it differ is a bug, and TestProjectsRunTest flags it.
- Container elements erase to `KiraSlot`; Float elements are rejected
  up-front. Do not "fix" that by widening the slot silently.
- Method receivers are `Type* this`; `Str` is already a pointer, so Str
  helpers take the receiver by value.
