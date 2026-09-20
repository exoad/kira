# Ownership and ARC (Kira heap vs foreign)

Status: **v1 shipped** (heap allocation, scope-end release, `->` access);
copy/field-store retain is a documented gap, not yet implemented. This doc is
the contract so FFI and ARC do not fight.

## Two worlds

| World | What | Lifetime |
|-------|------|----------|
| **Kira-owned** | Instances of non-foreign Kira `class` types | Strong ARC (v1) |
| **Foreign** | `@_opaque` / extern library objects (`Tigr*`, FILE*, ...) | Manual; library rules |

Never run `rc_retain` / `rc_release` on foreign pointers.

## Kira ARC v1 (strong only)

- **Heap:** class instance = pointer to `{ RcHeader; fields... }` (exact layout
  in prelude). Construction lowers to `Class_new(...)` → `kira_rc_alloc` with
  RC=1; member access is `->`; `kira_rc_release` fires at scope end.
- **Retain:** copy, assignment to another Kira ref, pass as Kira class arg.
  **Not yet in codegen** -- copies and field stores are borrowed today
  (documented limit). Release at end of scope and end of full-expression
  temporaries is the implemented subset.
- **Weak:** not in v1. Cycles leak until weak exists; document that.
- **Value types:** `Int32`, enums, small structs-as-values stay non-RC.
- **Arr views:** non-owning unless we introduce a separate owning collection
  type later. `List` / `Map` are owning runtime containers (they manage their
  own storage, independent of the class RC).

## Foreign edge

```kira
// Illustrative -- see language surface as implemented
@_opaque class Tigr  // no Kira fields; C pointer in emit

@_extern fx tigrWindow: (w: Int32, h: Int32, title: Str, flags: Int32) Tigr
@_extern fx tigrFree: (bmp: Tigr) Void
```

- Emit uses the **C symbol names as written** (no mangling).
- Caller must `tigrFree` (or library equivalent).
- Kira type system treats the handle as an opaque class identity for OOP
  call style if we add methods later; those methods are thin wrappers, not
  ARC owners of the handle unless explicitly designed as such (default: not).

## Interaction with pure OOP doctrine

App code still looks like objects and methods. Foreign libraries appear as
modules of stubs and opaque types -- still OOP-shaped at the Kira surface --
while lifetime of the *bytes* follows C. That preserves one doctrine in Kira
source without claiming one allocator for the whole process.

## Implementation order

1. Foreign opaque + extern call (no RC) -- unblocks TIGR-shaped demos. -- **done**
2. Prelude RC hooks + class alloc path. -- **done**
3. Codegen retain/release for Kira classes only. -- **scope-end release done;
   copy/store/arg retain next**
4. Tests: pure Kira graph + foreign handle in the same program without
   double-free. -- **partially covered by the example ladder; add explicit
   foreign+ARC test when retain lands**
