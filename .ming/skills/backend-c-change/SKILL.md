---
name: backend-c-change
description: Safe loop for changing the C backend (codegen or runtime prelude), including snapshot refresh and commit.
---

# C backend change loop

Triggers: "change the codegen", "fix lowering", "edit KiraCCodeGenerator",
"update the runtime", "emitted C looks wrong", "snapshots are stale".

The files that affect emitted C:

- `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/c/KiraCCodeGenerator.kt`
- `.../c/CMagicTypeLowering.kt`
- `.../c/CIntrinsicsTable.kt`
- `src/main/resources/c_generator.c` (facade + runtime)
- `src/main/resources/c_bundle.h` (substrate)
- `kira/*.kira` (stdlib surface signatures)

## Loop

```bash
# 1. Edit, then unit tests (fast feedback, no CLI needed)
./gradlew test

# 2. Rebuild the CLI so examples use the new compiler
./gradlew installDist

# 3. Refresh snapshots + verify behavior (NOT --check on purpose)
./examples/regenerate.sh

# 4. Confirm clean
./examples/regenerate.sh --check
./examples/ffi-mini/run.sh
```

## Commit rules

- Commit the code change and the regenerated snapshots (`generated.user.c`,
  `expected.txt`, `prelude.reference.c`) in the SAME commit - a codegen
  change without its snapshots breaks CI.
- Never hand-edit a generated snapshot; regenerate instead.
- If only docs or tests changed (no emitted-C impact), snapshots stay untouched.

## Gotchas

- The prelude must stay byte-identical across examples; a change that makes
  it differ is a bug, and regenerate.sh flags it.
- Container elements erase to `KiraSlot`; Float elements are rejected
  up-front. Do not "fix" that by widening the slot silently.
- Method receivers are `Type* this`; `Str` is already a pointer, so Str
  helpers take the receiver by value.
