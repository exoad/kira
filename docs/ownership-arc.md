# Ownership and ARC (Kira heap vs foreign)

Status: **design locked for implementation.** Runtime ARC is not fully shipped;
this doc is the contract so FFI and ARC do not fight.

## Two worlds

| World | What | Lifetime |
|-------|------|----------|
| **Kira-owned** | Instances of non-foreign Kira `class` types | Strong ARC (v1) |
| **Foreign** | `@_opaque` / extern library objects (`Tigr*`, FILE*, ...) | Manual; library rules |

Never run `rc_retain` / `rc_release` on foreign pointers.

## Kira ARC v1 (strong only)

- **Heap:** class instance = pointer to `{ RcHeader; fields... }` (exact layout
  in prelude).
- **Retain:** copy, assignment to another Kira ref, pass as Kira class arg.
- **Release:** end of scope, overwrite, end of full-expression temporaries
  (implementation may start with scope-end only and tighten).
- **Weak:** not in v1. Cycles leak until weak exists; document that.
- **Value types:** `Int32`, enums, small structs-as-values stay non-RC.
- **Arr views:** non-owning unless we introduce a separate owning collection
  type later.

## Foreign edge

```kira
// Illustrative -- see language surface as implemented
@_opaque class Tigr  // no Kira fields; C pointer in emit

@_extern fx tigrWindow(w: Int32, h: Int32, title: Str, flags: Int32): Tigr
@_extern fx tigrFree(bmp: Tigr): Void
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

1. Foreign opaque + extern call (no RC) -- unblocks TIGR-shaped demos.
2. Prelude RC hooks + class alloc path.
3. Codegen retain/release for Kira classes only.
4. Tests: pure Kira graph + foreign handle in the same program without
   double-free.
