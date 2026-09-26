# W2.4 (classes, traits, generics): known issues

The C++ backend's classes part: `CppClassEmitter.kt`, `CppClassLifetimes.kt`,
`CppGenericsEmitter.kt` and the tests under `src/test/kotlin/net/exoad/kira/cpp/oop/`. Each
entry says what the issue is, where it lives, how to reproduce it, and why it is safe to leave
for now. "Measured" means the probe was run through the CLI of a trial tree, then g++ 13.2 and
zig c++ 0.15 with the goldens' warning flags and `-Werror`. A trial tree is this branch with the
current W2.3 head (`cpp/w2-3-emit-exprs` 7c0fb42) merged without a commit and the verifier's
`rewire.py` applied, plus, where it says so, the W2.5 head (`cpp/w2-5-rules` 1dc7587) applied
without a commit.

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
  goldens) passes, and `CppClassLifetimesTest.theOopGoldensNeedNoGuard` checks that no body of
  the three gets a copied parameter or a hold (convergence round 2).
- **Reproduce.** On a trial tree, set `emit: required` in the three `case.yaml` files and run
  `./gradlew test --tests net.exoad.kira.cpp.CppGoldenEmitTest`: chain and classes fail with
  the two spill hunks only, with and without this round's change (measured on both, with and
  without W2.5). `CppGoldenCompileTest` passes, so the spilled trees compile and run on gcc,
  clang and MSVC and compile for aarch64.
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
- **Why it can wait.** It is a loud compile error, not a wrong program, and it exists only in
  struct code: superseded by W2.9 (no struct).

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

  Convergence round 2 edits:
  - `CppEmitContextImpl.kt` (TOUCHES): the parts interface gains
    `CppClassesPart.guards(ctx, fn)`, whose default is `CppGuards.NONE`, and the
    `CppGuards` class;
  - `CppDeclEmitter.kt` (W2.2's), three lines in `definition`: a free function or a struct
    method asks the classes part for its guards, names each copied parameter's reference as
    they say, and writes their lines ahead of the body;
  - `CppClassLifetimes.kt`, a new file of the classes part (it cannot conflict).
- **Why it can wait.** A parameter a free function's body may reach has the same hazard as a
  method's (`consume(h, h.item.unwrap().label)` with `consume` clearing `h` printed freed heap
  bytes on g++), and the free function's definition is the declaration emitter's. The hook is
  the smallest change that lets one analysis guard both. `git apply` of this round's diff onto
  a tree with W2.3 merged, and onto one with W2.3 and W2.5, applied cleanly (measured).

### KI-5. Four commits are unsigned

- **What.** 742d0b4, 80a5212, fa8d963 and 45675ea were made while gpg pinentry timed out.
- **Why it can wait.** Their content is reviewed and tested. Later commits are signed.
  Signing the old ones means rewriting this branch's history, which is the user's call.

### KI-6. What was not measured

- **gcc 11.4**, the design's floor, is not installed locally. gcc 13.2, zig clang 0.15 and
  MSVC 14.44 were used.
- **zig-aarch64 only compiles.** `CppClassCompileTest`'s aarch64 case builds the classes and
  the driver but runs nothing, because there is no emulator (`result.exe` is null for the
  cross build). The checks run on gcc, clang and MSVC.
- **The probes ran on g++ and zig c++ only**, not MSVC. `CppClassCompileTest` runs the same
  guards (a copied parameter, a hold, a free function's copy, a template's copy) on MSVC.
- **Why it can wait.** None of these gaps can hide a wrong program. A gcc 11 incompatibility is
  a compile error, and the aarch64 build is the same source the three runnable toolchains
  execute. Whoever has gcc 11.4 (CI, the Orange Pi) can run `CppClassCompileTest` there.

### KI-7. A struct's `this`, and a struct method's receiver, are not guarded

- **What.** A struct method's `this` points into wherever the struct lives, which can be a
  field of a class object (`obj.pos.move()`). A struct method that frees that object through
  another handle reads freed memory after it, and a range-for over a struct's own field is
  refused only through the same analysis as a class's (a struct's `this` counts as shared).
  The classes part holds a class method's object (`CppClassLifetimes.Guard.holdsThis`); a
  struct has no `shared_from_this`.
- **Where.** The declaration emitter's struct methods and the classes part's struct copies of
  trait defaults (`ClassLowering.inheritedDefinition`). Their parameters are guarded (copied at
  entry) like any function's; their `this` is not.
- **Why it can wait.** It exists only in struct code: superseded by W2.9 (no struct), where an
  immutable class lowers to a C++ value that no method can change, and a mutable class is a
  shared reference that holds itself as this round's classes do.

### KI-8. Conservative where the program uses kira:sync

- **What.** Dropping a Thread's last handle joins it, and after a join the thread's writes are
  the program's. So in a program whose own modules name a system class, every effect that may
  free an object counts as one that may do anything, and so does a class construction or a
  local that holds an object. More parameters are copied, more methods hold themselves, and a
  range-for over an object's storage is refused if its body frees anything at all.
- **Where.** `CppClassLifetimes.systemInUse`, `hasFinally` and `finallyPool`.
- **Why it can wait.** It only ever copies, holds or refuses more than it must, never less: no
  program is lowered unsafely. Narrowing it needs to know which handles can hold a Thread,
  which is worth doing when a kira:sync program meets it (the pilot's port, wave 3+).

### KI-9. With W2.5 merged, its MutabilityPass refuses six of this package's fixtures

- **What.** `CppClassCompileTest`'s program and five `CppClassShapeTest` cases write their own
  state from a plain `fx` (`Counter.bump`, `Target.hit`, `Dog.tag`, ...), because they test
  how the classes part derives `const` from the body (D29: the typer on cpp-backend lets a
  plain `fx` write its receiver). W2.5's rule (`rules.mutability.this`: declare it `mut fx`)
  refuses them, so on a tree with W2.5 merged those six tests fail at typing.
- **Where.** `src/test/kotlin/net/exoad/kira/cpp/oop/`, against W2.5's `MutabilityPass`.
- **Reproduce.** On a trial with W2.3 and W2.5, `./gradlew test`: the six fail with
  `rules.mutability.this` and `rules.mutability.method`, with and without this round's
  change (measured: the two trees' failing tests are the same 84, of 1261 and 1241; the other
  78 are the two KI-1 spills and W2.5's rules against W2.3's and W2.2's fixtures).
- **Why it can wait.** It is a disagreement between two correct rules about test inputs, not
  a miscompile. Whoever merges W2.5 turns those methods into `mut fx` (the C++ is the same,
  non-`const`), or keeps them as W2.5's negative cases; the classes part's derived `const`
  stays for W2.5's own known gaps (its ledger's Issue 8).

### KI-10. A method that holds itself delays its object's `finally` to the end of the call

- **What.** `t.kids[0].leave()`, with `leave` clearing the tree that owns the Kid, now runs
  the Kid's `finally` when `leave` returns, where before the guard it ran in the middle of
  `leave` (and `leave` then read freed memory).
- **Where.** `ClassLowering.holdLine`.
- **Why it can wait.** The call's receiver is a reference for the length of the call, as Swift
  holds `self`; the earlier order was a use after free, not a meaning. Recorded so the order is
  not taken for an accident.

### KI-11. What the analysis refuses that a different lowering could keep

- **What.** Four shapes are refused by name, each with the rewrite that compiles (measured:
  each rewrite compiles and prints the expected values on g++ and zig c++):
  - a range-for over storage an object holds whose body may change or free it (`bagloop2`):
    "iterate over a local copy";
  - a `mut` argument naming such storage, passed to a callee that may change or free it:
    "pass a local and store it back";
  - a call through an `Fx` such storage holds while the code it runs may replace it
    (`fxself`, a lambda reassigning the field it runs from): "copy it into a local first";
  - a lambda's `const&` parameter read after an effect that may reach it (`lamparam`): "copy
    it into a local at the top of the lambda".
- **Where.** `CppClassLifetimes.refusals`. The lowerings are W2.3's (its range-for, its call
  through an `Fx` value, its lambda parameters).
- **Why it can wait.** Refusing is correct under the convergence policy. W2.3 could lower each
  safely instead (iterate a copy, call a copy of the `kira::Fn`, copy a lambda's parameters at
  entry as the classes part does a method's) and the refusal could then go.

## Open decisions

### OD-2. What C++ supplies is taken to leave Kira's objects alone during a call

- **What.** The analysis follows every Kira body a call can reach: every body of the name for
  a virtual or trait call, every lambda and function used as a value for a call through an
  `Fx`. It does not see C++: an override a C++ class writes for a Kira trait (the chain
  driver's `Greedy`), an `Fx` a C++ caller builds (the sender driver's `write`), an `@_extern`
  function, a bodyless `pub` prototype a C++ file defines. Those are taken not to change or
  free Kira objects while the call runs, the way W2.5 takes an `@_extern` parameter not to keep
  a view.
- **The options, for the user.**
  1. Keep the contract (current). Chain and Sender stay as `expected/` has them.
  2. Take C++ to do anything. Then `Chain::has` (a loop over `loaded` that calls `b->id()`)
     is refused, and Chain and Sender derive `kira::Shared` and hold themselves in `load`,
     `has` and `send`.
- **Current behaviour kept.** Option 1; it is documented here and in `CppClassLifetimes`.

### OD-3. A field that is neither `require` nor defaulted, skipped at construction

- **What.** The typer lets `X { n = 1 }` leave out `pub c: Other` when `c` is neither
  `require` nor defaulted. D38 value-initializes it. Where that value is not one Kira has (a
  class, trait, `Ref` or system class handle, an `Fx`, or a struct or tuple holding one) the
  classes part now refuses the construction (policy rule 1; the `nullfield`, `nullfield2` and
  `nullfx` probes crashed). An enum field left out the same way is value-initialized to 0,
  which is not an entry of an enum whose values start elsewhere.
- **The question.** Should the typer require every field without a default at construction
  (D38 already says Kira requires a `require` field), which would make both the refusal and
  the enum's 0 unreachable?
- **Current behaviour kept.** The construction is refused where the value would be a null
  handle or an empty function; the enum case is value-initialized as D38 says.

## Routed to other packages

- **W2.5.** `consume(h, h.item.unwrap().label)`, where `consume` calls `h.clear()` directly on
  its parameter, is not charged by `HiddenWrites`, although its ledger says it catches this
  direct-write shape (the verifier's probe `uaf3`). The C++ is now safe (the classes part
  copies `s` at entry), but the rule's claim is not.
- **W2.3.** See KI-11 for the three refusals its lowering could replace.

## Fixed in convergence round 2 (for the record)

- **A `const&` parameter bound to storage the body's effects reach** (was OD-1, and the
  verifier's significant #1, halves a to c). With W2.5 merged, its MutabilityPass already
  refuses OD-1's own repros as written, `genalias3` and `stralias`, with
  `rules.mutability.this` (a plain `fx` writing its own state). The forms W2.5 accepts, a
  `mut fx` reached through a second handle (`stralias2`: `c.set(d.name)` printed `b` where C
  and JS print `a`; `genalias4` printed 7 where Kira gives 1) and the use-after-free variants
  (`uaflist`: std::bad_alloc; `uaf2`, `uaf3`: freed heap bytes), are now correct: a parameter
  C++ takes by `const&`, of a type some object can hold by value, that the body reads after an
  effect that may reach it, is copied at entry (`const kira::Str s = sRef_;`), in class
  methods, trait defaults, free functions and struct methods alike. Effects are decided over
  the whole program (`CppClassLifetimes`), so a parameter read before any effect, or one of a
  type no object holds, keeps its `const&`: none of the three goldens changes.
- **A method's receiver freed under it** (significant #1, half d: `selfkill`, `selfkill2`
  printed freed heap bytes). A class method that reads its receiver after an effect that may
  free an object holds itself for the call
  (`[[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();`), and the root of its
  chain derives `kira::Shared`. A trait default that would need the same is refused.
- **A range-for invalidated by its body** (significant #1, half e: `bagloop2` printed heap
  garbage), and a `mut` argument into such storage, are refused by name (KI-11).
- **A skipped field with no empty value** (significant #2: `nullfield`, `nullfield2` and
  `nullfx` segfaulted, tripped UBSan or threw `std::bad_function_call`). The construction is
  refused naming every such field and the three ways out (OD-3).
- **Measured.** On a trial with W2.3 and W2.5 (1dc7587), the same tree without this round's
  change still throws in `uaflist`, prints freed bytes in `bagloop2` and `selfkill2`, and
  prints `b` in `stralias2`; with it, each prints Kira's value or is refused by name. On
  W2.3 alone the failing tests are the two KI-1 spills, with and without the change (1094 and
  1074 tests), and of the verifier's 64 earlier probes only `genalias3` and `stralias`
  change, to 1 and `a`.

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
