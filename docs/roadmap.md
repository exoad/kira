# Kira roadmap (working)

Doctrine: [doctrine.md](doctrine.md). Backend status: [backend-c.md](backend-c.md).

Acceptance mindset: pure OOP in Kira; C only at an explicit foreign edge.

## Now (in progress)

### M1 -- Foreign edge (C libs without breaking OOP)

- [x] C-as-IR layered emit (bundle + facade + user)
- [x] Opaque foreign type + pointer values in C-as-IR (`@_opaque`)
- [x] `@_extern` function stubs → unmangled C prototypes + calls
- [x] `kira.yaml` `cSources` / `linkFlags`
- [x] Showcase: `examples/ffi-mini` (TIGR-shaped create/present/destroy)

**Done when:** a Kira `main` opens a foreign surface API via stubs, links extra
`.c`, runs natively; no ARC on foreign handles. -- **met by ffi-mini**

### M2 -- Class-only ARC (Kira heap)

- [x] Design note locked (strong-only v1; foreign excluded) -- `ownership-arc.md`
- [x] Prelude: alloc header, retain, release hooks
- [ ] Codegen: retain on copy/store/arg; release on scope end
- [ ] Class examples still green under ASan-friendly discipline

**Done when:** Kira class instances are heap+RC; `examples/04-classes` and
FFI hello both still correct; extern pointers never go through RC.

### M3 -- Stdlib that runs

- Map put/get, owning List, real Str helpers (Kira and/or thin C behind OOP API)
- Error types usable in app code without lying about emit

### M4 -- Tooling hardness

- Mangling default-on (preserve `main` + extern names); demos opt out
- Diagnostics collector (less process panic)
- LSP hover / go-to-def (after symbols are stable)

## Later

- Weak refs, traits/inheritance lowering, concurrency via C event/thread libs
- Optional second backend (Neko) only after C-as-IR is boring
- Self-host / stack migration is a **north star**, not a near milestone

## Non-goals near term

- Rewriting TIGR in pure Kira
- Full GC
- Competing ownership systems inside app-level Kira OOP
