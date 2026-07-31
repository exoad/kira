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
- [x] Codegen: `Class_new` factories (heap + RC=1), `->` access, scope-end release
- [x] Class examples still green under ASan-friendly discipline

**Done when:** Kira class instances are heap+RC; `examples/04-classes` and
FFI hello both still correct; extern pointers never go through RC. -- **met**

**Known limits:** release skipped on explicit return paths (leak, not crash);
field stores are borrowed (no retain); copies/args not retained.

### M3 -- Stdlib that runs

- [x] Map put/get/remove/containsKey/clear (open-addressing hash)
- [x] Owning List (add/get/set/removeAt/clear/toArr)
- [x] Traits: fat-pointer interface structs, vtables, dispatch, call-site coercion
- [ ] Real Str helpers (`substring`, `split`, ...) -- partial today
- [ ] Error types usable in app code without lying about emit

**Met by:** `examples/06-collections` (Map/List), `examples/08-traits`,
`examples/07-conway` (full program: grid + ARC class + loops).

### M4 -- Tooling hardness

- [ ] Mangling default-on (preserve `main` + extern names); demos opt out
- [ ] Diagnostics collector (less process panic)
- [ ] LSP hover / go-to-def (after symbols are stable)

## Later

- Variant lowering (tagged unions)
- Generic traits (`trait T<X>` monomorphized like user generics)
- ARC tightening: retain on copy/field-store/arg; release on return paths
- Weak refs, concurrency via C event/thread libs
- Optional second backend (Neko) only after C-as-IR is boring
- Self-host / stack migration is a **north star**, not a near milestone

## Non-goals near term

- Rewriting TIGR in pure Kira
- Full GC
- Competing ownership systems inside app-level Kira OOP
