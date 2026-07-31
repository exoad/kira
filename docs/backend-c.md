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
  out.kira.c          ← C-as-IR (one translation unit, three layers)
       │  0. compiler bundle  (c_bundle.h)   -- substrate, mangle hooks
       │  1. language facade  (c_generator.c) -- Int32/Str/Arr/Map, print
       │  2. user lowering                    -- modules, main, classes, ...
       ▼
  cc -std=c17 ...       ← system optimizer, linker, sanitizers
       │
       ▼
  native binary
```

NekoVM was an earlier runtime hope. It may return later as a **second**
backend; it is not how programs run on mainline now.

### Bundle substrate (cupup-inspired)

Early [cupup](https://github.com/exoad/cupup) (Kira spin-off) emitted a **shared
compiler bundle** before any user code: header guard, `stdint` types, and
named hooks for true/false/static/const so the rest of the transpiler only
composed symbols. Kira copies that *structure* (not the abandoned half-emit):

| Layer | Resource | Role |
|-------|----------|------|
| 0 | `src/main/resources/c_bundle.h` | `kira_i32`, `KIRA_TRUE`, `KIRA_INLINE`, `KIRA_NULL`, ... |
| 1 | `src/main/resources/c_generator.c` | `typedef kira_i32 Int32`, Arr/Map helpers, `print` |
| 2 | codegen | User structs, monomorphized generics, `main` |

**Why:** further generation stays natural -- helpers and a future **name
mangler** retarget layer-0 / layer-1 *names* without reshaping control flow.
Readable Jack-facing names stay on layer 1 for demos; release builds can mangle
layer 0+ symbols by default later (preserve `main` / explicit exports).

Every example in the ladder commits its own lowering as `generated.user.c`
(layer 2) alongside one shared `examples/prelude.reference.c` (layers 0+1), so
the emitted C is reviewable in a diff. `./examples/regenerate.sh --check`
fails when a snapshot or an example's stdout drifts.
[examples/c-as-ir/](../examples/c-as-ir/) walks through those snapshots
construct by construct.

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
| Layer 0 bundle | `c_bundle.h` -- substrate types + hooks |
| Layer 1 facade | `c_generator.c` -- Kira names + thin runtime |
| Layer 2 user | Modules, functions, structs, enums, `main` |
| Host compile | e.g. `cc -std=c17 -O2 -o app out.kira.c` |

There is **no** separate `libkira-rt` you link by default: bundle + facade are
**source-included**. After `cc`, everything is native code in one binary.

Entry convention: Kira `fx main: () Void` becomes C `Int32 main(Void)` and
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
| Classes: `require` fields, methods, init | **Green** | Heap objects via `Class_new(...)` factories |
| ARC / RC heap (Kira classes) | **Green** | `kira_rc_alloc` at construction, `->` access, scope-end `kira_rc_release`; limits below |
| User generics (`Box<T>`, `fx id<T>`) | **Green** | **Monomorphized** (`Box_Int32`, `id_Int32`) |
| `Arr` literal / index / `set` / `get` / `size` / `contains` / `clone` | **Green** | `KiraSlot` elements; typed accessor macros per element type |
| `Map` put/get/remove/containsKey/containsValue/keys/valuesArr/entries/clear | **Green** | Open-addressing hash; Str keys compare by content (`Map_new_s`), integer keys by value (`Map_new_i`) |
| `List` add/addAll/get/set/removeAt/contains/clear/toArr | **Green** | Owning dynamic array (doubles on overflow) |
| `Set` / `Stack` / `Queue` / `Deque` | **Green** | Over `KiraVec`; Set membership is linear |
| `Maybe` / `Result` | **Green** | Slot payload; `Map.get` / `pop` / `dequeue` return `Maybe` |
| `Str` length/isEmpty/substring/charAt/contains/startsWith/endsWith/split/trim/toLower/toUpper | **Green** | `Str_*` in the prelude; producers allocate (see Str lifetime below) |
| `Num` toInt32/toInt64/toFloat32/toFloat64/abs | **Green** | Plain C casts; `abs` picks `llabs` / `fabs` by receiver |
| Traits / trait inheritance | **Green** | Fat-pointer interface structs + vtables; trampolines per class; call-site coercion |
| Variants | **Not lowered** | Skipped or commented in emit |
| Generic traits | **Not lowered** | Prelude magic only (e.g. `Equatable<T>`) |
| `@_opaque` foreign types | **Green** | Incomplete struct; values are `T*`; never ARC'd |
| `@_extern` C stubs | **Green** | Unmangled prototypes + calls; no body |
| `build.cSources` / `linkFlags` | **Green** | Printed on emit; used by ffi-mini |
| Weak refs | **Not implemented** | Cycles leak until weak exists |
| Separate Neko backend | **Not active** | `target: neko` reserved only |

**Proof surface:** `examples/01-hello` ... `09-stdlib` via `./examples/run.sh`,
each with a committed `generated.user.c` + `expected.txt` verified by
`./examples/regenerate.sh --check`, plus `BackendCompilationPipelineTest` /
`StdlibLoweringTest` / `TraitCodegenTest` / frontend tests.

**ARC limits (documented in code):** release is skipped on explicit `return`
paths (leak, not crash); class objects embedded in fields use borrowed
ownership (no retain on store); non-lvalue trait receivers like
`makeSpeaker().name()` evaluate the receiver twice.

**Container erasure:** every container stores `KiraSlot` (64-bit). That covers
`Int8`..`Int64`, `Bool`, `Str`, and class references, which slot through
`intptr_t`. **`Float32` / `Float64` elements are not representable** -- a
`List<Float64>` would silently reinterpret the bits, so it is a gap rather than
a supported case. Codegen casts each slot back to the declared element type
using the type arguments recorded at declaration, so element types must be
statically known at the use site.

**Str lifetime:** `substring` / `charAt` / `trim` / `toLower` / `toUpper` /
`split` return freshly `malloc`'d storage that is never freed -- Kira has no Str
ownership model yet. Same class of gap as unowned ARC temporaries.

---

## Runtime model (today)

- **Lifetimes:** Kira classes are **heap objects** with a strong refcount
  (`kira_rc_alloc` at construction, `kira_rc_release` at scope end). Value
  types, enums, and small structs stay on the C stack.
- **Retain:** v1 only retains on construction; copies and field stores use
  **borrowed** ownership (documented limit). Full copy/arg retain is future
  tightening.
- **Arr / List / Map** are owning runtime containers; Map is an open-addressing
  hash table (djb2, linear probing, auto-resize).
- **Traits** are by-value fat pointers (`{ void* data; VTable* vtable }`) with
  static per-(class, trait) vtables; dispatch goes through the vtable.
- Out-of-range index: runtime helper may `abort()`.
- **Foreign handles** (`@_opaque`) are raw C pointers and never go through RC.

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

1. **ARC tightening** -- retain on copy/field-store/arg; release on explicit
   return paths (currently borrowed-ownership on stores, leak-on-return).
   The committed snapshots pin the three limits listed above to exact lines:
   - *Release emitted **after** `return`, not skipped.* In
     `08-traits/generated.user.c`, `makeSpeaker` ends with
     `return (Speaker){...}; kira_rc_release(cat);` -- `cc -Wunreachable-code`
     flags it. The leak is the documented behaviour, but the emit is dead code
     rather than an omission, so the fix is placement-aware, not just a guard.
   - *Temporaries never released.* In `04-classes/generated.user.c`, the two
     `Point_new(...)` arguments to `Rectangle_new` leak; only **named locals**
     get a release. Same for `bolt: Speaker = Dog { "Bolt" }` in `08-traits`.
     This is a distinct gap from borrowed-ownership-on-store.
   - *Receiver lowered twice.* `trace(makeSpeaker().name())` becomes
     `makeSpeaker().vtable->name(makeSpeaker().data)` -- two calls, so two
     allocations, with `.data` and `.vtable` taken from **different** objects.
     Harmless only while the vtable is receiver-independent; it needs spilling
     to a temporary before dispatch.
2. **Variants** -- tagged union lowering (currently skipped in emit).
3. **Generic traits** -- lower user `trait T<X>` the way user generics monomorphize.
4. **Name mangler (default on)** -- rename layer-0/1 hooks + user symbols;
   keep `main` / exports; seedable; `compiler.mangle: false` for demos.
5. **More of the stdlib** -- only what we can lower honestly (real Str
   helpers). The stdlib is now split per concern under `kira/`, so growth lands
   in `core.kira` / `collections.kira` / ... rather than one file.
6. **Emit quality** -- fewer redundant parens; optional denser cupup-like packing
   when mangling is on.
7. **Optional second backend** -- Neko or other, only after C-as-IR is boring.

---

## Related docs

| Doc | Role |
|-----|------|
| [tutorial/](tutorial/) | Language path that stays inside green lowering |
| [tutorial/07-projects-and-tooling.md](tutorial/07-projects-and-tooling.md) | Manifest + compile commands |
| [../examples/](../examples/) | Ladder projects, each with a committed `generated.user.c` + `expected.txt` |
| [../examples/c-as-ir/](../examples/c-as-ir/) | **Annotated tour** of those snapshots -- Kira vs real emitted C |
| [Language specifications](../specifications/LanguageSpecifications.md) | Full language (may exceed C-as-IR) |
| `src/main/resources/c_bundle.h` | Layer 0 bundle substrate |
| `src/main/resources/c_generator.c` | Layer 1 facade + thin runtime |
| `src/main/kotlin/.../codegen/c/KiraCCodeGenerator.kt` | Lowering source of truth |
