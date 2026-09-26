# W2.4 (classes, traits, generics): known issues

The C++ backend's classes part: `CppClassEmitter.kt`, `CppGenericsEmitter.kt` and the
tests under `src/test/kotlin/net/exoad/kira/cpp/oop/`. Each entry says what the issue is,
where it lives, how to reproduce it, and why it is safe to leave for now. "Measured" means
the probe was run through the CLI of a trial tree: this branch with the current W2.3 head
(`cpp/w2-3-emit-exprs` 41575c6) merged without a commit and the verifier's `rewire.py`
applied, then g++ 13.2 and zig c++ 0.15 with the goldens' warning flags and `-Werror`.

## Known issues

### KI-1. chain and classes differ from `expected/` by one W2.3 D33 spill each

- **What.** With W2.3 merged, `CppGoldenEmitTest` is not byte-identical for the `chain` and
  `classes` cases. Each differs from `expected/` by one statement that W2.3's D33 rule now
  spills into a `t0_`/`t1_` IIFE: `if(b->id() == wanted)` in `Chain::has`, and
  `return kira::cat(name, " says ", sound());` in `Animal::describe`. `sender` is
  byte-identical.
- **Where.** W2.3's expression lowering (`CppHoister`), against
  `src/test/resources/cpp-golden/{chain,classes}/expected/`. Nothing W2.4 emits differs:
  `CppOopGoldenShapeTest` (every declaration, class body and definition head of the three
  goldens) passes.
- **Reproduce.** Merge `cpp/w2-3-emit-exprs` (41575c6), apply `rewire.py`, set
  `emit: required` in the three `case.yaml` files, then run
  `./gradlew test --tests net.exoad.kira.cpp.CppGoldenEmitTest`: 14 tests, 2 failed (chain,
  classes). `CppGoldenCompileTest` passes, 79 tests and 0 failed, so the spilled trees compile
  and run on gcc, clang and MSVC and compile for aarch64.
- **Why it can wait.** The emitted code is correct and compiles. The difference is W2.3's
  rule against expected text written before that rule. W2.3 or the integrator has to either
  keep the rule and regenerate those two `expected/` files, or narrow the rule. Until one of
  them lands, the three cases stay `emit: pending` on this branch (brief acceptance item 4,
  "after rebasing on W2.3").

### KI-2. A trait default's local named as a copying struct's field fails `-Wshadow`

- **What.** A struct takes a trait's default body as its own member (D1). When that body
  declares a local whose name is also one of the struct's fields (`side: Int32 = 2` in
  `Shape.doubled`, copied into `struct Square { side }`), gcc and clang refuse the local
  under `-Wshadow -Werror`.
- **Where.** W2.3's `CppStmtEmitter.body` takes the frame's owner as
  `fn?.owner ?: ctx.scope`. For a struct's copy, `fn.owner` is the trait, so the struct's
  fields are never counted as taken. A parameter of that body is already renamed
  (`CppEmitContextImpl.shadows`, `CppClassesPart.structsCopying`); the local is W2.3's.
- **Reproduce.** The `structshadow2` probe fails on g++ and zig with
  "declaration of 'side' shadows a member". With `ctx.scope ?: fn?.owner` it prints 18, and
  no other probe or golden changes (measured in round 6).
- **Why it can wait.** It is a loud compile error, not a wrong program. Without `-Werror` the
  local hides the field only inside the copied body, where Kira's name meant the local anyway.
  The fix is one line in W2.3's file.

### KI-3. A `mut Unsafe<T>` field initialized from an `Unsafe<T>` value does not compile

- **What.** Table 5.1 spells `Unsafe<T>` as `const T*` unless the binding is `mut`, which
  gives `T*`. So `Holder { q = cell() }`, with `require pub mut q: Unsafe<Int32>` and
  `pub fx cell: () Unsafe<Int32>;`, passes a `const std::int32_t*` to the constructor's
  `std::int32_t* q_` ("no matching function for call to construct_at").
- **Where.** The `Unsafe` row of design table 5.1 (`CppTypeSpeller.wrap`, W2.2), which W2.6
  (FFI) uses. The constructor takes each field in the field's own column, and a `mut` local
  initialized the same way meets the same mismatch.
- **Reproduce.** The `ctormut` probe fails the same way on the baseline trial and on this
  round's trial.
- **Why it can wait.** The compiler refuses the program loudly. This round did not cause it,
  and fixing it means deciding where a `const T*` may become a `T*`, which is FFI design.

### KI-4. Files outside this package's OWNS list

- **What.** Earlier rounds edited:
  - `CppTypeSpeller.kt` (W2.2's): `spellUnder`, the parameter-leaf hook, and the
    `X* const&` rule;
  - `CppDeclEmitter.kt` and `CppDeclEmitterTest.kt` (W2.2's);
  - `CppEmitContextImpl.shadows`, a TOUCHES file.

  This round edits only W2.4's own files and this ledger.
- **Why it can wait.** `git merge-tree` against every wave-2 branch was clean in round 6.
  Spelling a template's member under its type arguments needs the speller's column logic,
  and text substitution of type-parameter names was the only alternative, which is unsafe.

### KI-5. Four commits are unsigned

- **What.** 742d0b4, 80a5212, fa8d963 and 45675ea were made while gpg pinentry timed out.
- **Why it can wait.** Their content is reviewed and tested. This round's commit is signed.
  Signing the old ones means rewriting this branch's history, which is the user's call.

### KI-6. What was not measured

- **gcc 11.4**, the design's floor, is not installed locally. gcc 13.2, zig clang 0.15 and
  MSVC 14.44 were used.
- **zig-aarch64 only compiles.** `CppClassCompileTest`'s aarch64 case builds the classes and
  the driver but runs nothing, because there is no emulator (`result.exe` is null for the
  cross build). The checks run on gcc, clang and MSVC.
- **Why it can wait.** Neither gap can hide a wrong program. A gcc 11 incompatibility is a
  compile error, and the aarch64 build is the same source the three runnable toolchains
  execute. Whoever has gcc 11.4 (CI, the Orange Pi) can run `CppClassCompileTest` there.

## Open decisions

### OD-1. A by-value parameter spelled `const&` reads what the callee wrote

- **What.** Kira parameters are values. Design table 5.1 passes every type that is not a
  scalar, view, enum or pointer as `const T&`, including a type parameter `T`, and design 5.8
  and D37 say the exclusivity rule closes the aliasing hazard. D37 only covers a `mut`
  argument and the receiver of a `mut fx`, though, and D29 lets a class's plain `fx` write its
  receiver. When the argument names a place that the callee writes before it reads the
  parameter, the C++ reads the new value.
  - `genalias3`: `class Box<T>`, `fx take: (v: T) T { n = m; return v }`, and
    `b.take(g.n)` with `g` being `b`. C++ prints 7 where Kira gives 1.
  - `stralias`: `class C { mut name: Str = "a"; fx set: (s: Str) Str { name = "b"; return s } }`
    and `c.set(c.name)`. C++ prints `b` where Kira gives `a`.

  Both are measured the same on the baseline trial and on this round's trial.
- **What W2.4 does now.** An override that takes by `const&` a parameter its own declaration
  takes by value (a scalar, `Bool`, enum, view or pointer, forced by a generic base) copies it
  at entry (`const std::int32_t v = vRef_;`), so `genalias2` prints 1. Nothing else changed,
  per the design.
- **The options, for the user.**
  1. Extend D37 (W2.5's `ExclusivityPass`): treat a call whose callee writes its receiver as
     a `mut` receiver. `CppClassFacts.isConstMethod` is false exactly for those callees. Both
     repros then become a diagnostic. This narrows what compiles and costs nothing at run time.
  2. Copy every `const&` parameter the body names at entry into any method that writes
     anything. This is correct for every path, but costs one copy per call (a `Str` or
     `List` argument allocates).
  3. Pass those types by value in table 5.1. This has the same cost as option 2, and changes
     every signature and golden.

  Writes through another reference (`a.take(b, b.name)` where `take` renames `b`) are outside
  option 1 and need option 2 or 3, or D37 extended to the arguments of a writing callee.
- **Current behaviour kept.** It is the documented one (table 5.1, D37).

## Fixed in convergence round 1 (for the record)

- **An override that copies a pointer.** It spelled `const const std::int32_t* v = v_;`, a
  duplicate const on gcc, clang and MSVC, and `const Handle* v`, which a callee taking
  `Handle*` refused. The copy is now the parameter as its own declaration spells it, with the
  pointer made const: `const std::int32_t* const v`, `const char* const v`, `Handle* const v`.
- **A copied parameter's reference name.** The reference `<param>_` was the `t0_` of W2.3's
  first D33 temporary for a parameter named `t0` (`-Werror=shadow`), and the `ex_` of W2.3's
  catch variable for one named `ex`. References are now `<param>Ref_`, and a construction's
  D33 temporaries are `t0_Arg_`. W2.3 draws its temporaries from a pool of its own, which never
  sees the context's `CppNames`, and every name it makes is lowercase, so a name with a
  capitalized mark never meets one of W2.3's. The same collision existed for a construction
  spilled inside a spilled call (`spillnest`), and that is fixed too.
- **Deep override chains.** Deciding an override's signature was exponential in the chain's
  depth: 40 s at 10 levels, and 14 levels did not finish in 9.5 minutes. It is now linear
  (`owedBy`/`rootOf` remembered per lowering), and 14 levels emit in under 0.5 s.
- **Constructions with pointer fields.** D33 temporaries of pointer fields
  (`const const std::int32_t* t0_`) are now `const std::int32_t* const t0_Arg_`. A skipped
  field without a default of pointer type (`Handle*{}`, which is no expression) is now
  `static_cast<Handle*>(nullptr)`. Both use the field's own column.
