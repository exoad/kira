# Kira doctrine -- pure simple OOP

Kira has **one** structure for programs: objects, methods, and modules.
There is not a second "systems dialect" inside the language, and C is not a
peer paradigm. C is a **foreign edge** reached through explicit bridges.

## Unified OOP doctrine

1. **Everything user-facing is object-shaped.**  
   Types are classes/enums (and traits where the language allows). Behavior is
   methods and functions in modules. No parallel "C mode" syntax for app code.

2. **Privacy and immutability by default.**  
   `pub` and `mut` are opt-in. That is part of the OOP discipline, not optional
   style.

3. **One memory doctrine for Kira-owned objects.**  
   Heap instances of Kira classes are managed by **Kira ARC** (strong refs;
   weak later). Stack/value locals follow normal scope. There is not a menu of
   competing ownership systems inside pure Kira code.

4. **Foreign memory is never silently Kira memory.**  
   Pointers and objects that come from C libraries are **opaque foreign
   handles**. They are not ARC-retained. Lifetime is explicit at the stub
   (e.g. `tigrFree`). Mixing models only happens at a documented boundary.

5. **C-as-IR is the implementation substrate, not the language identity.**  
   We lower to ISO C17 so the host toolchain optimizes and we can link real C.
   Readable or mangled C output is an emit policy. The programmer still writes
   Kira OOP.

6. **Backends may multiply; doctrine does not.**  
   A future Neko or other target must preserve the same OOP surface. Only the
   lowering changes.

## What this forbids

- Designing APIs that force users to think in malloc/free for normal Kira
  objects.
- Applying ARC retain/release to extern/C library handles by default.
- Splitting the language into "safe OOP" vs "raw C subset" as two first-class
  styles of application code.
- Letting FFI convenience erode class/method/module as the only app structure.

## What this allows

- Thin `@_extern` modules that declare C functions and opaque types.
- Linking `tigr.c` (or any C lib) beside `out.kira.c`.
- Manual free on foreign handles; ARC on Kira class instances.
- Magic/intrinsics as compiler-owned implementation detail of the OOP stdlib.

## North star

Kira should feel like a small, strict OOP language that **happens** to compile
through C and can call the C world -- not like a C macro pack with objects
bolted on. Long-term self-host / stack dreams stay on that doctrine: Kira as
the orchestration and object layer, C libraries as engines behind stubs.
