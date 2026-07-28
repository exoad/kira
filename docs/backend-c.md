# C as IR (ISO C17)

Kira's **reference backend** lowers checked Kira down to **ISO C17** source.
That C is the intermediate representation: we do not emit machine code or
Neko bytecode today, and we do not JIT. A host C toolchain finishes the job.

Internal shorthand, if you want one: **C-as-IR**. No separate file format or
brand is required -- the artifact is ordinary `.c` (`out.kira.c`).

```text
.kira + kira.yaml
       │
       ▼
  JVM compiler (frontend: lex → parse → semantic)
       │
       ▼
  out.kira.c          ← C-as-IR
       │  • runtime prelude (types, print, thin Arr/Map, ...)
       │  • lowered user modules (one translation unit)
       ▼
  cc -std=c17 ...       ← system optimizer, linker, sanitizers
       │
       ▼
  native binary
```

NekoVM was an earlier runtime hope. It may return later as a **second**
backend; it is not how programs run on mainline now.

---

## Why C17 source (not a custom bytecode IR)

- **Opts and tools for free** -- `cc -O2`, LTO, asan/ubsan, gdb/lldb.
- **Magic at transpile time** -- `@_magic` / intrinsics (`trace`, collection
  helpers, ...) become C macros, `static inline` helpers, or inlined bodies in
  the prelude. We do not build a Kira optimizer; we lower carefully and let C
  do the rest.
- **Portable enough** -- any C17 compiler; no VM install on the target.
- **Matches the tree** -- this is what `build.target: c` already does.

---

## Artifact shape

| Piece | Role |
|-------|------|
| `out.kira.c` | Single translation unit written by `kira` |
| Prelude | From `src/main/resources/c_generator.c`, prepended into that file |
| User code | Modules, functions, structs, enums, `main` |
| Host compile | e.g. `cc -std=c17 -O2 -o app out.kira.c` |

There is **no** separate `libkira-rt` you link by default: the prelude is
**source-included**. After `cc`, everything is native code in one binary.

Entry convention: Kira `fx main(): Void` becomes C `Int32 main(Void)` and
returns `0`.

---

## Progress (what lowers today)

Honest matrix for the C-as-IR path. "Green" means the example ladder or tests
emit, compile as C17, and run.

| Area | Status | Notes |
|------|--------|--------|
| Project load (`kira.yaml`, path stdlib) | **Green** | cwd-only; no `--project` yet |
| Modules + `use` | **Green** | URI must match path (`app:main` → `app/main.kira`) |
| Functions, locals, `mut`, operators | **Green** | |
| `if` / `else` / `while` / `for` + ranges | **Green** | |
| `trace` / print intrinsics | **Green** | → `printf` + newline |
| Enums | **Green** | Tagged C enums |
| Classes: `require` fields, methods, init | **Green** | Methods → `Type_method(Type* this, ...)` |
| User generics (`Box<T>`, `fx id<T>`) | **Green** | **Monomorphized** (`Box_Int32`, `id_Int32`) |
| Stdlib magic types in prelude | **Partial** | Typedefs + thin helpers; not full `stl.kira` bodies |
| `Arr` literal / index / `size` / `isEmpty` | **Baseline** | Erased; Int32-oriented helpers |
| `Map` empty / `isEmpty` / `size` | **Baseline** | Count-only; no real put/get hashing |
| `List` grow / `add` | **Stub** | Alias-ish to Arr at C boundary |
| Traits / inheritance / variants | **Not lowered** | Skipped or commented in emit |
| ARC / RC heap / weak refs | **Not implemented** | Spec describes ARC; runtime is plain C lifetimes |
| Hybrid ARC + manual C | **Planned** | Design direction; not started |
| Separate Neko backend | **Not active** | `target: neko` reserved only |

**Proof surface:** `examples/01-hello` ... `06-collections` via `./examples/run.sh`,
plus `BackendCompilationPipelineTest` / frontend tests.

---

## Runtime model (today)

- **Lifetimes:** C stack / local; struct-by-value; method receiver as pointer.
- **No** retain/release, **no** GC, **no** Neko heap.
- **Arr** is a view (`data` + `length`), not a full owning RC container.
- Out-of-range index: runtime helper may `abort()`.

Future hybrid ownership (ARC for heap classes + explicit manual/raw for C
buffers) would extend the **prelude + codegen**, still as C-as-IR -- not a VM
switch.

---

## Intrinsics and "magic"

Lowering policy:

1. Prefer **prelude** `static inline` / macros for small, shared ops.
2. Prefer **codegen rewrite** when the shape depends on types or call site
   (e.g. monomorphized generics, method mangling, collection method names).
3. Do not invent a Kira SSA optimizer; keep semantics in the frontend and
   dumb-but-correct C in the backend.

Jack's C style guide applies to prelude and emitted shape (Allman braces,
shared type names, etc.).

---

## Tooling contract

```bash
./gradlew installDist
export PATH="$(pwd)/build/install/kira/bin:$PATH"

cd my-project    # directory with kira.yaml
kira             # writes out.kira.c
cc -std=c17 -O2 -o app out.kira.c
./app
```

- Manifest: `build.target: c` (or `native` → same emit).
- Output file: `out.kira.c` (gitignored).
- LSP (`kira-lsp`) shares the frontend only; it does not emit C.

---

## ISO C17 target

**Dialect goal:** emitted C and the prelude should stay within **ISO C17**
(`-std=c17`). Docs and scripts standardize on that flag.

Practical notes:

- Need `stdint.h`, `stdbool.h`, compound literals, designated-friendly layout;
  all C17 (and C11) mainstream.
- Avoid GNU-only extensions in emit unless gated and documented.
- CI / examples should compile with `-std=c17` (clang and gcc).

---

## Roadmap (C-as-IR only)

Ordered for this backend; not a Neko plan.

1. **Ownership design note** -- what is ARC'd vs borrowed vs manual (hybrid).
2. **Prelude RC** -- heap class instances: header, retain/release, then codegen.
3. **Collections** -- real Map put/get; owning List; keep Arr as view or split types.
4. **More of `stl.kira`** -- only what we can lower honestly.
5. **Emit quality** -- fewer redundant parens, clearer names, optional split
   headers later if TU size hurts (still C-as-IR).
6. **Optional second backend** -- Neko or other, only after C-as-IR is boring.

---

## Related docs

| Doc | Role |
|-----|------|
| [tutorial/](tutorial/) | Language path that stays inside green lowering |
| [tutorial/07-projects-and-tooling.md](tutorial/07-projects-and-tooling.md) | Manifest + compile commands |
| [../examples/c-as-ir/](../examples/c-as-ir/) | **Side-by-side demos** -- Kira vs real `generated.user.c` |
| [Language specifications](../specifications/LanguageSpecifications.md) | Full language (may exceed C-as-IR) |
| `src/main/resources/c_generator.c` | Prelude source of truth |
| `src/main/kotlin/.../codegen/c/KiraCCodeGenerator.kt` | Lowering source of truth |
