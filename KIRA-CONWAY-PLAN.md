# Kira: Road to Conway's Game of Life

## Goal
Make Kira capable of running Conway's Game of Life end-to-end (compile → link → run).

## Current State (2026-07-31)
- Tests: All green, BUILD SUCCESSFUL
- Example ladder: 01-hello ... 07-conway all run
- Collections: Map has real put/get/remove/containsKey; List is owning dynamic
- ARC: Hooks exist in prelude; codegen still uses stack-allocated class structs (works for Conway)
- Traits: Emit comment only; semantic analyzer has TODO
- LSP: Diagnostics only (no hover/completion/go-to-def)

## Steps

### s1 - Map: real put/get/clear in C runtime + codegen lowering
**Status:** ✅ done

- Map struct replaced with open-addressing hash table (linear probing, djb2 hash, auto-resize at 0.75 load)
- C helpers: `Map_new()`, `Map_put()`, `Map_get()`, `Map_remove()`, `Map_containsKey()`, `Map_clear()`
- Codegen: `tryEmitCollectionMethod` lowered for `.put()`, `.get()`, `.remove()`, `.containsKey()`
- Committed: 0369527

---

### s2 - List: grow/add/remove/clear in C runtime + codegen lowering
**Status:** ✅ done

- List is owning dynamic struct: `{ Int32* data; Int32 length; Int32 capacity; }`, doubles on overflow
- C helpers: `List_new()`, `List_add()`, `List_get()`, `List_set()`, `List_removeAt()`, `List_clear()`, `List_toArr()`
- Codegen: `.add()`, `.set()`, `.remove()`, `.toArr()` lowered
- Committed: 0369527

---

### s5 - Conway's Game of Life in Kira
**Status:** ✅ done (working milestone)

- `examples/07-conway/`: `Grid` class over flat `Arr<Int32>`, neighbor counting, step, ASCII print
- Codegen fixes required (see below)
- Runs: glider pattern evolves 5 generations, exit 0

**Codegen fixes made while getting Conway to run:**
- Bare method calls inside class methods now mangle to `Class_method(this, ...)` (was emitting bare name + `&this`)
- `receiverTypeOf` for bare identifiers now falls back to `fieldTypes` so `cells[idx]` inside a method resolves
- Collection methods on class fields now lower (`cells.set(i, v)` → `Arr_set_i32(this->cells, i, v)`)

---

### s3 - ARC: wire retain/release into class instance lifecycle
**Status:** pending (deferred -- Conway works with stack-allocated classes)

`kira_rc_alloc/retain/release` exist in prelude but are never called by codegen. Conway proved the
stack-allocated class model is enough for real programs; ARC becomes important when classes are
passed around / returned and lifetime must outlive the creating scope.

**Changes (`KiraCCodeGenerator.kt`):**
- On `ObjectInitExpr` (class construction): emit `kira_rc_alloc(sizeof(Type))` + field init
- On variable assignment of class type: retain RHS, release old LHS
- On scope exit (function return): release local class-typed variables
- Track which locals are heap-allocated class types

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

## Execution Order
1. ✅ s1 (Map) + s2 (List) -- done, committed
2. ✅ s5 (Conway) -- done, runs; codegen fixes committed with it
3. ⏳ s3 (ARC) -- next
4. ⏳ s4 (Traits) -- after ARC

## Files to Modify
- `src/main/resources/c_generator.c` - C runtime helpers
- `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/c/KiraCCodeGenerator.kt` - Codegen
- `src/main/kotlin/net/exoad/kira/compiler/analysis/semantic/KiraSemanticAnalyzer.kt` - Trait semantics
- `kira/stl.kira` - Stdlib (maybe, if collection methods need updating)
- `examples/07-conway/` - Conway's Game of Life (done)
