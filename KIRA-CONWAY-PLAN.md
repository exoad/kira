# Kira: Road to Conway's Game of Life

## Goal
Make Kira capable of running Conway's Game of Life end-to-end (compile → link → run).

## Current State (2026-07-31)
- Tests: All green, BUILD SUCCESSFUL
- C backend: Working for basic constructs (functions, classes, enums, generics, control flow)
- Collections: Arr has index/size/isEmpty; Map is count-only stub; List is Arr alias stub
- ARC: Hooks exist in prelude but never called by codegen
- Traits: Emit comment only; semantic analyzer has TODO
- LSP: Diagnostics only (no hover/completion/go-to-def)

## Steps

### s1 - Map: real put/get/clear in C runtime + codegen lowering
**Status:** pending

Replace the stub `Map` struct (`{ Int32 length }`) with a working hash table.

**C runtime (`c_generator.c`):**
- Replace Map struct with: `{ Any* keys; Any* values; Bool* occupied; Int32 length; Int32 capacity; }`
- Add helpers: `Map_new()`, `Map_put()`, `Map_get()`, `Map_remove()`, `Map_containsKey()`, `Map_containsValue()`, `Map_keys()`, `Map_valuesArr()`, `Map_entries()`, `Map_clear()`
- Use linear probing for simplicity (good enough for Conway)
- Hash function: simple FNV-1a or djb2 for keys

**Codegen (`KiraCCodeGenerator.kt`):**
- Extend `tryEmitCollectionMethod` to lower `.put()`, `.get()`, `.remove()`, `.containsKey()`, etc.
- `ObjectInitExpr` for Map should call `Map_new()` with initial capacity
- Handle Maybe<V> return type for `.get()` and `.remove()`

**Verify:** Write Kira smoke test using Map put/get, compile + run through pipeline.

---

### s2 - List: grow/add/remove/clear in C runtime + codegen lowering
**Status:** pending

`List` is currently `typedef Arr List` - needs its own dynamic struct.

**C runtime (`c_generator.c`):**
- New struct: `{ Int32* data; Int32 length; Int32 capacity; }`
- Add helpers: `List_new()`, `List_add()`, `List_removeAt()`, `List_clear()`, `List_get()`, `List_set()`, `List_toArr()`
- Grow policy: double capacity on overflow (start at 4)

**Codegen (`KiraCCodeGenerator.kt`):**
- Extend `tryEmitCollectionMethod` for `.add()`, `.removeAt()`, `.clear()`, `.toArr()`
- `ObjectInitExpr` for List should call `List_new()` not `Arr_empty()`
- Update `CMagicTypeLowering` if needed

**Verify:** Smoke test List add/get/size through pipeline.

---

### s3 - ARC: wire retain/release into class instance lifecycle
**Status:** pending

`kira_rc_alloc/retain/release` exist in prelude but are never called by codegen.

**Changes (`KiraCCodeGenerator.kt`):**
- On `ObjectInitExpr` (class construction): emit `kira_rc_alloc(sizeof(Type))` + field initialization
- On variable assignment of class type: emit `kira_rc_retain()` on RHS, `kira_rc_release()` on old LHS
- On scope exit (function return): emit `kira_rc_release()` for local class-typed variables
- Track which locals are heap-allocated class types

**Approach:** Start with function-level cleanup (release all class locals before return), refine to block-level later.

**Verify:** Construct a class, pass it around, confirm no leaks (valgrind or manual check).

---

### s4 - Traits: semantic analysis + C lowering
**Status:** pending

TraitDecl currently emits a comment; semantic analyzer has `TODO`.

**Semantic Analysis (`KiraSemanticAnalyzer.kt`):**
- Register trait methods in symbol table
- Validate that implementing classes provide all required methods
- Track trait implementations per class

**C Codegen (`KiraCCodeGenerator.kt`):**
- Traits lower to interface struct: `{ void* data; VTable* vtable; }`
- VTable holds function pointers for each trait method
- Emit VTable structs per trait+class combo
- Emit dispatch through vtable pointers

**Verify:** Define a trait, implement it on a class, call a trait method.

---

### s5 - Conway's Game of Life in Kira
**Status:** pending

Write the program:
- `Grid` class (wrapping `Arr<Int32>` for flat 2D grid)
- Neighbor counting function
- Generation stepping logic
- ASCII printing (alive/dead cells)
- Main loop with multiple generations

Compile through full pipeline: `kira` → `cc` → run.

---

## Execution Order
1. s1 (Map) and s2 (List) - independent, can parallelize
2. s3 (ARC) - independent of s1/s2, more complex
3. s4 (Traits) - depends on class lowering, may interact with ARC
4. s5 (Conway) - integration test that uses everything

## Files to Modify
- `src/main/resources/c_generator.c` - C runtime helpers
- `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/c/KiraCCodeGenerator.kt` - Codegen
- `src/main/kotlin/net/exoad/kira/compiler/analysis/semantic/KiraSemanticAnalyzer.kt` - Trait semantics
- `kira/stl.kira` - Stdlib (maybe, if collection methods need updating)
- New example: `examples/07-conway/` - Conway's Game of Life
