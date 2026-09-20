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
Readable Jack-facing names stay on layer 1 for demos; the **user layer** is
minified and obfuscated by default today (see "Minified + obfuscated output"),
while the prelude stays readable and byte-identical.

Every example in the ladder commits its own lowering as `generated.user.c`
(layer 2) alongside one shared `examples/prelude.reference.c` (layers 0+1), so
the emitted C is reviewable in a diff. `./examples/regenerate.sh --check`
fails when a snapshot or an example's stdout drifts.
[examples/c-as-ir/](../examples/c-as-ir/) walks through those snapshots
construct by construct.

---

## Minified + obfuscated output

Generated user code (both backends) is **minified and obfuscated by default**:
comments and insignificant whitespace are stripped, and every user-declared
identifier (functions, classes, methods, fields, locals, enum names and
members, mangled method names, specialization names) is renamed to a short
deterministic name. `main` stays `main`; `@_extern` C symbols and `@_opaque`
types are never renamed, so the foreign edge is untouched.

The runtime prelude (layers 0+1 for C, the JS prelude) is **not** minified: it
is the shared runtime, stays byte-identical across examples, and is what
`regenerate.sh` splits on. Only the user layer is compressed.

Control:

- `kira --readable` (or `--target c --readable`) restores the pretty
  Jack-style formatting for inspection.
- `build.minify: false` in `kira.yaml` makes readable output the default for
  that project.

The pass is a deterministic tokenizer (`OutputMinifier`): tokens are never
merged (operator combinations like `- -` or `/ *` get a separating space), so
the minified artifact lexes to the same token stream and behaves identically.
Example snapshots commit the minified user layer, so `regenerate.sh --check`
still guards drift.

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

**Ownership model.** A class value is a refcounted heap object. The rules the
backend enforces:

| Situation | Lowering |
|---|---|
| `a: Pet = Pet { ... }` | `Pet_new(...)` -- fresh `+1` |
| `b: Pet = a` (alias) | `kira_rc_retain(b)` after the store |
| `a = value` (reassign) | `kira_rc_store` (retains first, so `a = a` is safe) or `kira_rc_store_owned` for a fresh value |
| constructor argument | Fields **consume** a `+1`; a borrowed local is wrapped in `kira_rc_retained(...)` |
| field cleanup | `Class_finalize` released by `kira_rc_release` when the count hits zero |
| block exit | `kira_rc_release` per local, **inside** the block that declared it |
| `return x` | Releases precede the `return`; the returned local keeps its `+1` |
| container local | `List_dispose` / `Map_dispose` / ... at scope end |

Verified with AddressSanitizer plus `leaks` over aliasing, field storage,
constructor temporaries, reassignment, loop allocation, argument passing and
return paths.

**Remaining limits:** no weak refs, so reference *cycles* still leak;
`Str`-producing methods allocate and are never freed (see below); non-lvalue
trait receivers like `makeSpeaker().name()` still evaluate the receiver twice;
containers cannot nest (an `Arr` is wider than a slot, so `Arr<Arr<Int32>>` is
rejected by `cc`).

**Container erasure:** every container stores `KiraSlot` (64-bit). That covers
`Int8`..`Int64`, `Bool`, `Str`, and class references, which slot through
`intptr_t`. **`Float32` / `Float64` elements are not representable** -- a
`List<Float64>` would silently reinterpret the bits, so it is a gap rather than
a supported case. Codegen casts each slot back to the declared element type
using the type arguments recorded at declaration, so element types must be
statically known at the use site.

**Str lifetime (open design question):** `substring` / `charAt` / `trim` /
`toLower` / `toUpper` / `split` return freshly `malloc`'d storage that is never
freed -- 50k `trim()` calls leak ~800 KB. This is *not* a simple bug fix: a C
string literal has no RC header, so `release` cannot blindly read the memory in
front of the pointer. Picking one of these is a language decision:

1. a registry of heap `Str` pointers with refcounts (safe, costs a lookup per op),
2. intern every literal into RC storage on first use (uniform, changes literal cost),
3. an arena with a defined reset point (cheapest, needs a scope to reset at).

Until then, `Str` results are borrowed-forever.

**Null safety:** `null` is a stdlib global of type `Null`, not a keyword --
exactly like `true` / `false` are globals of type `Bool`. Every type is
non-nullable; `Maybe<T>` is the only shape that admits absence. The frontend
rejects `null` against a non-`Maybe` type and rejects reaching through a
`Maybe` without unwrapping. Both the C and JS backends lower the coercion
(`Maybe_none()` / `Maybe_some(...)`, `kira_none()` / `kira_some(...)`).

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

1. `@_magic` declarations are typechecker-only signatures: the backend never
   emits their bodies. Free functions resolve through **binding manifests**
   (`kira/<module>.bind.yaml`, loaded beside the stdlib modules) that map the
   canonical Kira name to its C symbol and required includes. Adding a stdlib
   function is a data change next to the module, not a compiler edit -- see
   `CMagicBindingTable`.
2. Prefer **prelude** `static inline` / macros for small, shared ops.
3. Prefer **codegen rewrite** when the shape depends on types or call site
   (e.g. monomorphized generics, method mangling, collection method names).
4. Do not invent a Kira SSA optimizer; keep semantics in the frontend and
   dumb-but-correct C in the backend.

**Stdlib layout.** The stdlib is one self-contained directory: `kira/*.kira`
holds the declarations, `kira/*.bind.yaml` holds the magic binding manifests,
and the per-backend runtime implementations live beside them under `kira/c/`
(`c_bundle.h` + `c_generator.c`, the C prelude) and `kira/js/`
(`js_generator.js`). `StdlibLayout` resolves the stdlib root from the loaded
`kira:` dependencies and loads the runtime file from the matching backend
folder; the backends no longer embed stdlib resources in the jar (jar lookup
remains as a fallback for packaged builds).

Most stdlib declarations are `@_magic` signatures whose bodies live in that
prelude, but a module may also carry **real Kira code**: non-magic functions
with bodies are emitted like user code (`hasEmittableStdlibFunctions`).
`kira:math` is the first hybrid module -- `clamp` and `lerp` are written in
Kira on top of the magic `min`/`max`. That is the bootstrap path: as the
language matures, stdlib surface moves from prelude C into Kira itself.

**Operator overloading** desugars in the frontend-facing rule set: when a
binary/unary/compound operator has a statically non-primitive operand, it
lowers to a call on the matching `@op_*` intrinsic (`a + b` → `op_add(a, b)`,
`a += b` → `a = op_add(a, b)`). Primitives keep the native C operator. User
overloads are ordinary functions declared as `fx @op_add: ...`; the JS
backend mirrors the same desugar for parity.

Two magic families are **not** manifest-bindable, on purpose:

- **Print family** (`trace` / `print` / `println` / `eprint`) synthesizes its
  printf format string from the Kira argument type at each call site, so it
  stays a codegen intrinsic (`isPrintLike` / `emitPrintCall`).
- **Collection methods** (`Arr.get`, `List.add`, ...) need slot-erasure
  adapters derived from the declaration's type parameters plus receiver
  passing; today they lower through `tryEmitCollectionMethod`. The same
  manifest mechanism is the intended home for their binding data.

`CIntrinsicsTable` remains as a fallback for names the manifest does not bind;
it is not the primary resolution source.

Jack's C style guide applies to prelude and emitted shape (Allman braces,
shared type names, etc.).

---

## Tooling contract

```bash
./gradlew installDist
export PATH="$(pwd)/build/install/kira/bin:$PATH"

cd my-project    # directory with kira.yaml
kira             # writes out.kira.c (minified + obfuscated user layer)
kira --readable  # same, but pretty Jack-style formatting
cc -std=c17 -O2 -o app out.kira.c
./app
```

- Manifest: `build.target: c` (or `native` → same emit);
  `build.minify: false` disables minification for the project.
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
4. **Name mangler (default on)** -- user-layer minify+obfuscate is done and
   default-on (`OutputMinifier`); remaining work is mangling layer-0/1 prelude
   hooks and a seedable rename, with `main` / externs always preserved.
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
