# W2.4 (classes, traits, generics): known issues

The C++ backend's classes part: `CppClassEmitter.kt`, `CppClassLifetimes.kt`,
`CppGenericsEmitter.kt` and the tests under `src/test/kotlin/net/exoad/kira/cpp/oop/`. Each
entry says what the issue is, where it lives, how to reproduce it, and why it is safe to leave
for now. "Measured" means the probe was run through the CLI of a trial tree, then g++ 13.2 and
zig c++ 0.15 with the goldens' warning flags and `-Werror` (and, from convergence round 3 on,
MSVC 14.44 with `/fsanitize=address`). A trial tree is this branch with a W2.3 head merged
without a commit and the verifier's `rewire.py` applied, plus, where it says so, the W2.5 head
(`cpp/w2-5-rules` 1dc7587) applied without a commit. Convergence round 4 measures against the
current W2.3 head, `cpp/w2-3-emit-exprs` 9e00cfb; rounds 1 to 3 measured against a00af85, and
an entry that still cites a measurement on a00af85 says so.

Views are second-class (decision 4b, the design's `30-second-class.md`). W2.5's ViewPass owns
every view refusal, so the classes part follows no view: its kind 4 (views into class storage)
and kind 1's `lent` exception are deleted, not patched. What that closed is listed under
"Closed by the second-class rule". The second-class round measures on a trial of this branch
with W2.3 9e00cfb merged and `rewire.py` applied (no W2.5: its ViewPass is written in parallel).
Second-class round 2 measures on a trial of this branch's working tree with W2.3's re-signed
head `e355c3e` (the same tree as 9e00cfb) merged the same way; what it fixed is under "Fixed in
second-class round 2". From round 2 on, decision 4b is read literally (OD-5).

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
  the two spill hunks only. Measured in round 4 on W2.3 9e00cfb: the whole `./gradlew test`
  runs 1126 tests with 3 failures, the two spill hunks and the routing failure of KI-19, the
  same set as the tree before round 3 (d410f69) on 9e00cfb. `CppGoldenCompileTest` passes, so
  the spilled trees compile and run on gcc, clang and MSVC and compile for aarch64. (On
  a00af85 the two messages were the same, and round 3's tree had 2 failures of 1113.) The
  second-class round's trial runs 1117 tests with 5 failures: the same two spill hunks, KI-19's
  routing failure, and the two CppHoisterTest cases on W2.3's `Ref<View<Int32>>` fixture
  (KI-19); `CppGoldenCompileTest` passes. Second-class round 2, on a trial with W2.3 9740456:
  the whole `./gradlew test` runs 1131 tests with 0 failures while the cases stay pending; with
  `emit: required` set, `CppGoldenEmitTest` fails on exactly these two hunks (sender is
  byte-identical) and `CppGoldenCompileTest` runs 79 tests with 0 failures. Under decision 4b
  read literally, `b->id()` is a trait call EffectsPass cannot prove pure, so the spill is
  W2.3's rule working as intended.
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

### KI-5. Closed: every commit is signed

- **What.** 742d0b4, 80a5212, fa8d963 and 45675ea were made while gpg pinentry timed out. The
  branch has since been re-signed with identical trees: `git log --format=%G? cpp-backend..HEAD`
  gives `G` for all 14 commits before second-class round 2's (measured in round 2).

### KI-6. What was not measured

- **gcc 11.4**, the design's floor, is not installed locally. gcc 13.2, zig clang 0.15 and
  MSVC 14.44 were used.
- **zig-aarch64 only compiles.** `CppClassCompileTest`'s aarch64 case builds the classes and
  the driver but runs nothing, because there is no emulator (`result.exe` is null for the
  cross build). The checks run on gcc, clang and MSVC.
- **The earlier rounds' probes ran on g++ and zig c++ only**, not MSVC. `CppClassCompileTest`
  runs the same guards (a copied parameter, a hold, a free function's copy, a template's copy)
  on MSVC. Convergence rounds 3 and 4 also ran their probes that compile under MSVC ASan.
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
- **Reproduce.** On a trial with W2.3 9e00cfb and W2.5 1dc7587, `./gradlew test` with the three
  OOP goldens at `emit: required`: the six fail with `rules.mutability.this` and
  `rules.mutability.method`. Measured in round 4: 96 of 1293 fail with the round's change and
  98 of 1285 at 70f3e7f; the 96 are the 98 less the two CppHoisterTest cases round 3 broke
  (round 3's significant #3), and the rest are the two KI-1 spills, KI-19's routing failure,
  the evalorder golden and W2.5's rules against W2.3's and W2.2's fixtures. (The second-class
  round deletes the view tests that accepted a W2.5 refusal; its internal-assertion test
  accepts `rules.view.type` first. No other fixture of this package writes a second-class
  type anywhere but a parameter or an argument.)
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

### KI-12. With a `finally` anywhere, constructions and object-holding locals count as releases

- **What.** When any class of the program has a `finally` (or kira:sync is in use, KI-8), every
  class construction counts as an effect that may free an object (the new object may be dropped
  at once and run its `finally`), and so does every local that holds an object, at its
  declaration (it is dropped at the end of its block). So a method that builds an object and
  then reads `this` holds itself (a `kira::Shared` base and a `weak_from_this().lock()` per
  call), more parameters are copied, and a trait default that does the same is refused.
- **Where.** `CppClassLifetimes.Walker.construction` and the Walker's `VariableDecl` case.
- **Reproduce.** The `holdctor` probe (`Counter.make` builds an `Other`, whose class has a
  `finally`, then reads `n`) emits `keepAlive_` in `Counter::make`; it prints the right values.
- **Why it can wait.** It only ever copies, holds or refuses more than it must, never less.
  Narrowing it means placing a local's drop at the end of its block and charging a
  construction only with what its `finally` does, a precision change with no safety gain.

### KI-13. The guards rely on W2.5 for globals and direct aliasing

- **What.** `CppClassLifetimes.holdable` asks what class fields, `Ref` arguments and held `Fx`
  values hold by value, never what a global holds. So on a tree with W2.3 and without W2.5, a
  `const&` parameter bound to an element of a global List the callee grows (`globalalias`,
  and `globaltrait` through a trait call) still prints freed heap bytes on g++. W2.5 refuses
  both (`rules.exclusivity.alias`). The same holds for a place passed twice to one call directly
  (`fxparam`: `callIt(b.f, b)`, which prints the right string since round 3's copy), which W2.5
  refuses as `rules.exclusivity.alias`, and for a loop whose body changes what it iterates
  through the variable the range is reached from (`loopreassign`: `for s in it.labels { it =
  Item {} }`, freed heap bytes on W2.3 alone), which W2.5 refuses as `rules.exclusivity.loop`:
  the classes part's loop rule covers a change made through another handle (`bagloop2`), which
  W2.5 cannot see.
- **Views (second-class round 1).** Every view refusal is now W2.5's ViewPass's, so on a tree
  without W2.5 no view is checked at all: `genview` (a view in a generic class's field,
  `rules.view.type`), and every view shape rounds 3 and 4 refused here (a view in a local, a
  `Ref` or a container, a view returned from a non-view parameter or a local's handle, a view
  handed to a call that grows its storage) compile unchecked there. The merge order of
  `30-second-class.md` 7.0 puts W2.5's ViewPass before this branch for that reason.
- **Where.** `CppClassLifetimes.collectHeld` and `holdable`, against W2.5's ExclusivityPass;
  every view shape, against W2.5's ViewPass.
- **Reproduce.** `globalalias`, `globaltrait` and `loopreassign` on a trial with W2.3 9e00cfb
  alone print garbage on g++; on a trial with W2.3 and W2.5 each is refused (measured in round
  4 over all 186 probes).
- **Why it can wait.** The C++ backend is shipped with W2.5's rules: the cpp target runs them
  (design 3.4). This entry records that the classes part is not safe without W2.5 merged, so
  W2.5 (with ViewPass) has to be merged before this package.

### KI-14. `keepAlive_` and `<param>Ref_` could meet a C++ name in scope

- **What.** The hold's local is `keepAlive_` and a copied parameter's reference is `vRef_`.
  Kira's lexer refuses both spellings for a Kira name (an underscore is allowed only in
  UPPER_SNAKE_CASE), and W2.3's synthesized names are lowercase stems, so neither can meet a
  Kira name. A C++ name in scope can: a namespace-scope `keepAlive_` from an FFI header.
- **Where.** `ClassLowering.bodyName`.
- **Why it can wait.** The result is a loud `-Wshadow` compile error, not a wrong program.

### KI-16. Class- and trait-typed receiver paths rely on W2.3 copying the `kira::Rc`

- **What.** A call whose receiver lies one handle away from a parameter or a field of `this`
  (`it.labels.add(h.take())`, `other.labels.add(h.take())`), with an argument that frees that
  object, is safe because W2.3 copies the `kira::Rc` of a class- or trait-typed receiver path
  before the arguments run. The classes part guards `this`, which W2.3 cannot copy (a method
  whose argument may free its own object holds itself: `recvfree`, `implicitcall`), and, since
  second-class round 2, a receiver of type-parameter type, which W2.3 does not copy
  (`kira::deref(x).m(...)`, x bound by reference): a parameter is copied at entry, storage an
  object holds is refused (genrecv, genrecv2; "Fixed in second-class round 2"). Round 1's
  "measured safe" was false for that receiver.
- **Where.** W2.3's receiver ordering (`CppExprEmitter.receiverOperand`) and
  `CppClassLifetimes.Walker.call`, `paramReceiverRefusal`.
- **Reproduce.** `recvfree2` and `recvfield` print the right values on g++, zig and MSVC ASan
  on a trial with W2.3 a00af85, and on g++ and zig on one with 9e00cfb (round 4).
- **Why it can wait.** It is measured safe on the W2.3 this package merges with. If W2.3
  stops copying a handle receiver, the same refusal as a `mut` argument's
  (`CppClassLifetimes.mutArgRefusals`) extends to it, as `paramReceiverRefusal` already does
  for a type parameter's.

### KI-17. A `mut` argument inside another is refused where a lowering could keep it

- **What.** A `mut` argument inside another `mut` argument of the same call
  (`f(mut x, mut x.label)`) is refused by name, and W2.5 refuses it first as
  `rules.exclusivity.argument`. (Round 3's view refusals of this entry are ViewPass's now:
  `rules.view.local` and `rules.view.write`.)
- **Where.** `CppClassLifetimes.overlappingMutArgs`.
- **Why it can wait.** Refusing is correct under the convergence policy; the rewrite is a local.

### KI-18. A range-for over storage a temporary keeps relies on W2.3 9e00cfb or later

- **What.** Round 3 refused `for s in makeItem().labels` and every range reached from a
  temporary through a handle, an element or an accessor, because C++'s range-for kept the
  `kira::Rc` temporary alive only to the end of its initializer. W2.3 9e00cfb copies such a
  range in the range expression (`kira::List<kira::Str>(makeItem()->labels)`,
  `CppHoister.rangeMayDangle`), so the classes part no longer refuses it: the refusal made the
  evalorder golden and two CppHoisterTest cases fail on that W2.3 (round 3's significant #3).
  A loop over a view of such a temporary (the verifier's `loopfreshview`) is ViewPass's
  (`rules.view.position`: a range's origins are view parameters or literals only). On W2.3
  a00af85, which copies nothing, `loopfresh` and `loopfreshview` are a heap-use-after-free.
- **Where.** `CppClassLifetimes.loopRefusal` and `temporaryRange`, against W2.3's
  `CppStmtEmitter` range-for.
- **Reproduce.** On a trial with W2.3 9e00cfb (with and without W2.5): `loopfresh`,
  `loopfresh_nf`, `loopfresh_r3`, `loopfresh2` (an element's field, a `Maybe`'s value's field,
  a List in a fresh object's List) and `loopfresh3` (bodies that build and drop objects) print
  every label on g++ and zig c++ and are ASan-clean on MSVC (round 4). The copied range's
  temporary is destroyed at the end of the range's initializer, so an Item's `finally` runs
  before the first iteration (`freed` is printed first), as D34's C++ order has it for a
  temporary; a local (`loopfreshok`) prints `freed` after the loop.
- **Why it can wait.** It is safe on the W2.3 this package merges with; the entry records the
  order: W2.3 at 9e00cfb or later has to be merged no later than this package.

### KI-19. Closed: CppHoisterTest's twelfth `cpp.view-lifetime` refusal, until W2.3's commit B

- **Closed in second-class round 2.** W2.3's commit B is in: on trials of this round's tree
  with W2.3 e355c3e and with 9740456, the whole `./gradlew test` runs 1130 and 1131 tests with
  0 failures, CppHoisterTest included. What follows is the record.

- **What.** The verifier's `rewire.py` returns from W2.3's `CppExprEmitter.classConstruction`
  into the classes part's `construct` at its first line, above W2.3's
  `hoister.refuseTemporaryView(f.expr, "the object it is stored in")`, so on a trial with W2.3
  9e00cfb `CppHoisterTest.aTemporaryTheLoweringMakesOrStorageAPathMovesIsRefusedWithWhatToWriteInstead`
  finds 11 of its 12 `cpp.view-lifetime` refusals. The classes part no longer refuses
  `Ref<View<Int32>> { value = makeList().view() }` as a user error (its `temporaryViewed` is
  deleted): the program is ViewPass's (`rules.view.type`, a `Ref` of a view), and should one
  reach the classes part, its construction reports `cpp.internal` (a field that holds a view
  or a pointer), so it is never lowered.
- **Where.** The integration's rewire, against W2.3's `classConstruction`.
- **Also, on the same trial.** `CppHoisterTest.aMutViewALambdaInAGenericFunctionLendsMakesItsSourceShared`
  and `aForRangeThatMayBeAReferenceIntoATemporaryIsCopiedWhileItLives` share the fixture
  `hoister-round4`, whose `makeRefV` builds `Ref<View<Int32>> { value = gsrc.view() }`
  (round4.kira:28). The classes part's `cpp.internal` assertion refuses that construction, so
  both fail there (second-class round 1). Under decision 4b the program is `rules.view.type`:
  once W2.5's ViewPass is merged, which the merge order puts before this branch, it is refused
  at typing whatever the classes part does, and the fixture is W2.3's to rewrite.
- **Why it can wait.** W2.3's commit B (30-second-class.md 7.2) deletes `refuseTemporaryView`
  and `cpp.view-lifetime`, and turns those tests into emission tests; the refused programs move
  to ViewPassTest. The entry closes then.

### KI-22. A field default's lambda that calls a later `pub` function does not compile

- **What.** `sink: Fx<...> = fx(v) { return total(v) }` puts the lambda in the constructor's
  default argument, in the class body in the header, and the header declares the `pub`
  function `total` after the class: g++ says 'total' was not declared in this scope, zig 'use
  of undeclared identifier'. The same shape with a private helper is moved into `impl_` and
  works (second-class round 1's verifier, minor).
- **Where.** The header's order (W2.2's design 4.2 placement) against a default argument the
  classes part spells in the class body.
- **Why it can wait.** It is a loud compile error, not a wrong program; the rewrite is to
  declare the function before the class. A construction spilled in a field default (round 2)
  calls only what a field default could already call, and `CppClassCompileTest`'s `DuoBox`
  compiles because `bumpSeed` is declared first.

## Open decisions

### OD-2. What C++ supplies is taken to leave Kira's objects alone during a call

- **What.** The analysis follows every Kira body a call can reach: every body of the name for
  a virtual or trait call, every lambda and function used as a value for a call through an
  `Fx`. It does not see C++: an `@_extern` function, a bodyless `pub` prototype a C++ file
  defines, an override a C++ class writes for a Kira trait (the chain driver's `Greedy`), and
  an `Fx` a C++ caller builds (the sender driver's `write`).
- **The contract.** For the first two, this is 30-second-class.md 5.4, which replaces this
  package's FFI note: an extern writes Kira storage only through its `mut` arguments and its
  receiver, and runs Kira code only through the `Fx` arguments it is given. The classes part
  applies the same contract to the last two, which are C++ code called through a Kira
  signature.
- **What the contract lets such a callee do (second-class round 2).** Run each `Fx` argument
  during the call: its `during` (`calleeEffects`) is the union of what they may do, a lambda
  written there its body's, a function named as a value its body's, any other `Fx` value
  everything. And read its arguments and its receiver while it runs, after the callbacks: it
  copies nothing at entry, as a Kira callee does. Round 1 took an extern's and a bodyless
  prototype's `during` as nothing (externmut, externstr).
- **The option left for the user.** Take a C++ override or a C++-built `Fx` to do anything.
  Then `Chain::has` (a loop over `loaded` that calls `b->id()`) is refused, and Chain and
  Sender derive `kira::Shared` and hold themselves in `load`, `has` and `send`.
- **Current behaviour kept.** The contract; it is documented here and in `CppClassLifetimes`.

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

### OD-5. Decision 4b's "impure" read literally, not as "has hidden writes"

- **What.** 4b: "a full-expression that forms a view of a place must not also mutate that
  place, or anything that could alias it (any impure call, when the place lies in a mutable
  class)". `30-second-class.md` 3.2 read "impure" as "has hidden writes" (`HiddenWrites`), so
  that `t: Int32 = total(this.buf.view())` beside a call that only prints or throws stays
  allowed. Second-class round 1's verifiers broke that analysis five ways (a `finally` run by a
  drop, a `MutView` formed inside a callee, an `Fx` nested in an extern argument, an extern's
  `Fx` arguments, construction order), which is the provenance analysis 4b exists to avoid.
- **Current behaviour (from round 2).** The literal reading: 3.3's test for a shared place (a
  field of a mutable class, a `mut` global, anything behind a `Ref` or a handle) is
  `effect(C) == IMPURE`, and EffectsPass is conservative on each shape round 1 broke. Both are
  W2.5's; this package writes no view rule. A refusal the literal reading adds is correct, and
  any test relying on the softer reading is rewritten; none of this package's tests does (its
  one view fixture, `aViewOfACopiedParameterIsTakenOfTheCopy`, calls only the pure `total`).
- **The option left for the user.** The softer reading, `hidden(C)` not empty, which 3.2
  names as a one-line swap. Not implemented.

## Routed to other packages

- **W2.5.** `consume(h, h.item.unwrap().label)`, where `consume` calls `h.clear()` directly on
  its parameter, is not charged by `HiddenWrites`, although its ledger says it catches this
  direct-write shape (the verifier's probe `uaf3`). The C++ is now safe (the classes part
  copies `s` at entry), but the rule's claim is not.
- **W2.5, ViewPassTest.** The converge verdict's S1, S2 and S3 probes (`arrview`, `arrsame`,
  `arrview2`, `arrparam`, `arrhanded`, `arrmut`, `arrmethod`, `arrref`, `strelem`, `strelem2`,
  `strparam`, `listelemparam`, `refelem`, `viewmutparam`, `viewmutlist`) are refused only by
  ViewPass now (30-second-class.md 7.5: `rules.view.local`, `.write`, `.type`), and are its
  negative cases. So are the 13 CppClassLifetimesTest programs this round deletes (a view in a
  local, a `Ref`, a List or a lambda's capture; a view returned from a non-view parameter or a
  local's handle; a view handed to a call that grows its storage; `refview`): they were in
  that file at 5808b78. W2.2's `CppDeclFixesTest` declares `struct Viewed { pub v: View<Later> }`,
  which `rules.view.type` refuses.
- **W2.3.** See KI-11 for the refusals its lowering could replace, KI-16 for the receiver copy
  the classes part relies on, KI-18 for the range copy it relies on (9e00cfb or later), and
  KI-19 for the CppHoisterTest case its commit B replaces.
- **W2.5.** KI-13: the classes part is not safe without W2.5's alias rules and ViewPass merged.
  OD-5: decision 4b read literally (`effect(C) == IMPURE`, EffectsPass conservative on the shapes
  second-class round 1 broke) is W2.5's to implement; nothing here depends on either reading.
- **The C backend (no package here).** It prints `new` for ctorwrong2 too, its own
  argument-order issue: it is not a reference for Kira's left-to-right order, which D33 and
  W2.5's `rules.exclusivity.order` message are (second-class round 1's verifier, minor).

## Fixed in second-class round 2 (for the record)

Round 1's verdict named four significant issues, each accepted with and without W2.5 1dc7587.
Each is now fixed with a test of the right result, or refused with a test of the refusal.
Measured on a trial of this round's final tree with W2.3's newest head `9740456` merged (the
whole `./gradlew test`: 1131 tests, 0 failures, 0 skipped), and the same probes on an earlier
state of it with W2.3 `e355c3e` (1130 tests, 0 failures): each probe on g++ 13.2 and zig c++
0.15 with `-Werror`, and MSVC 14.44 `/fsanitize=address`, with the same output on both trials.
On this branch alone: 1024 tests, 0 failures, 0 skipped; the oop tests 139 (compile test 4 of
4 toolchains, none skipped).

- **Construction order (ctorwrong2, ctororder, ctorthis).** `ClassLowering.construction`
  spilled only when two operands called something impure, so a place or a `const&` parameter
  was forwarded by reference into `make_shared` and read after an impure sibling. It now ranks
  every operand as W2.3's call hoister ranks an argument (`CppClassFacts.operandRank`: IMPURE,
  READS, PURE), and when one is IMPURE and another is not PURE, it copies every operand that
  is not PURE into a typed temporary in Kira's order: the given values as written, then the
  skipped middle defaults it fills in (a trailing default is the constructor's default
  argument, which C++ runs after every argument). The lambda captures `[&]` only when an
  operand names the body's frame, and `[]` otherwise, which a constructor's default argument
  also takes (`DuoBox`). IMPURE follows EffectsPass where it recorded an answer, and otherwise
  takes every call, construction, overloaded operator, assignment, `throw`, `try` and intrinsic
  but `@_static_assert` to have an effect. ctorwrong2 prints `old` (was `new`), ctororder the
  label and ctorthis the name (were MSVC ASan heap-use-after-free, and freed heap bytes on g++
  and zig), with 0 ASan reports. Tests: four `CppClassShapeTest` construction-order cases, and
  five `CppClassCompileTest` checks, which fail on the old rule (2 of 42 checks on gcc, 5 on
  clang and MSVC, measured by reverting the rule) and pass on the new one.
- **`this` under construction or destruction (initcall2, initlam, fincall2).** A method an
  `initially` or `finally` may run (`CppClassFacts.initializerReach`: what it calls on `this`,
  on through each override family below the called method) now refuses `this` as a value
  (`thisValue`) and a lambda that captures `self = shared_from_this()` (`selfCapture`), naming
  the block and the call that reaches it. The refusal sits where the lowering would spell
  `shared_from_this()`, so it does not depend on which captures the facts scan counts (initlam's
  lambda captures only a field). All three probes are refused (they threw std::bad_weak_ptr on
  g++ and zig; MSVC 0xC0000409). Tests: four refusals (direct, through another method, an
  escaping lambda, an override a trait default dispatches to) and one method that stays
  lowered.
- **A type-parameter receiver (genrecv, genrecv2).** C++ reads `kira::deref(x)` after the
  arguments, and W2.3 copies only a class- or trait-typed receiver. `Walker.call` now counts a
  type-parameter receiver as in use after the arguments, so a parameter the arguments may reach
  is copied at entry (`const T x = xRef_;`), and `paramReceiverRefusal` refuses one in storage
  an object holds. genrecv prints the name for `callIt<Kid>` (was `other 7`), genrecv2 the name
  (was std::bad_alloc on g++, MSVC ASan heap-use-after-free), 0 ASan reports. Tests: the copy,
  no copy where no argument reaches it, the refusal of a field receiver, a local left alone,
  and a `CppClassCompileTest` check (fails on clang and MSVC without the copy).
- **What a callee C++ supplies runs during the call (externmut, externstr).** `calleeEffects`
  gave an `@_extern` and a bodyless prototype nothing. Now its `during` is the union of its
  `Fx` arguments' effects (everything for an `Fx` value it cannot see into), and so is a
  virtual or trait call's on top of the Kira overrides (OD-2); such a callee reads its
  arguments and its receiver after the callbacks, so a parameter it is handed is copied at
  entry, and storage an object holds is refused (`cppCalleeArgRefusals`,
  `cppCalleeReceiverRefusal`). externmut is refused as a `mut` argument the call may free (was
  MSVC ASan heap-use-after-free); externstr prints the label (was freed heap bytes on g++, MSVC
  ASan heap-use-after-free), 0 ASan reports. Tests: the refusal with a lambda and with an `Fx`
  value, a harmless callback accepted, the copy at entry, no copy without an `Fx` argument, a
  field argument refused, a trait receiver refused and a trait receiver parameter copied.
- **Round 1's `allowed` probe** still prints 6 6 5 12 4 1 12 112 1 on g++, zig and MSVC ASan (0
  reports): nothing allowed was newly refused.
- **The killed fixer's partial work.** Kept: the lifetimes half (`suppliedByCpp`, `fxArgs`,
  `fxArguments`, `cppCalleeArgRefusals`, `paramReceiverRefusal` and the two `Walker.call`
  uses), reviewed and tested; and `operandRank` with its ranks. Rewritten: `callsImpurely`
  (it trusts an EffectsPass entry wherever one is recorded, not only at the root). Discarded:
  `methodThisUses` and `initializerThisReaches`, which never reported anything and missed
  initlam's field-only capture; the use-site refusal replaces them. Not present in it, added:
  the construction's use of the ranks, the capture choice, the skipped middle defaults, the
  receiver of a C++-supplied callee, and every test.

## Closed by the second-class rule (second-class round 1)

Decision 4b makes every view second-class, and W2.5's ViewPass refuses what the classes part
used to follow. Deleted from `CppClassLifetimes.kt` (2213 lines, now 1176): kind 4 whole
(`Walker.viewSources` and the view locals, captures, loops, hand-offs and returns it checked,
`ViewSource`, `ViewHit`, `BlockEnd`, `movesBuffer`, `movesReached`, `reallocates`,
`pointsInto`, `ownsBuffer`, `sharesViews`, `temporaryViewed`) and kind 1's `lent` exception
(`Guard.lent`, `lentParams`, `lentRefusal`, `reachedRefusals`, `Lending`). Deleted from
`CppClassEmitter.kt`: the construction's `temporaryViewed` refusal, replaced by a `cpp.internal`
assertion (a class field that holds a second-class type).

- **KI-3** (a `mut Unsafe<T>` field from an `Unsafe<T>` result): an `Unsafe` field is
  `rules.view.type`, and an extern returning one is `rules.view.extern`.
- **KI-15** (a parameter a returned view may point into stays the caller's reference): a
  returned view comes only from the function's view parameters, its receiver or a literal
  (the return rule, `rules.view.return`), so no view can point into a copied parameter after
  the call, and every parameter an effect may reach is copied at entry. `viewlend`,
  `viewlend2`, `viewlend3` and `lentval` return a view of a `List` parameter: `rules.view.return`.
- **KI-20** (round 4's view rules refused more than they must) and **KI-21** (a view stored
  through a `mut` argument): the rules are gone; a `mut` view parameter is `rules.view.type`.
- **OD-4** (a view lent from a value parameter while the caller's storage changes): it cannot
  be returned (`rules.view.return`), and a view parameter's storage is checked at the caller for
  the whole call (`rules.view.write`).
- **The converge verdict's S1** (`Arr` refilled under a view), **S2** (an element replaced
  under a view) and **S3** (a view passed `mut`): refused by ViewPass (routed above). S1's
  root cause, whether replacing an `Arr<T>` moves its buffer, no longer exists: 3.3 counts
  every moving write whatever the type. S2's `strparam` also needs kind 1's copy of the `Str`
  parameter itself, which stays.
- **The converge verdict's S4** (a lambda made after an effect capturing a `const&` parameter
  or `this`): fixed. `Walker.node(LambdaExpr)` reads each captured parameter, and `this` for a
  captured field or receiver, where the lambda is made, so the parameter is copied at entry and
  the method holds itself. On a trial with W2.3 9e00cfb and `rewire.py`: `capparam` prints `a`
  (was `b`), `capparamfree` prints the label (was a heap-use-after-free), `capthis` prints the
  name (threw std::bad_weak_ptr), and `capparam2` still prints `a`, on g++ 13.2, zig c++ 0.15
  and MSVC 14.44 `/fsanitize=address` with 0 ASan reports on each.
- **Fixtures.** The two shape tests and the compile test's overrides and constructions at
  `Unsafe<Int32>` and `CStr` (a generic trait's type argument, a field, an extern result) keep
  only their opaque-handle half: those types cannot be written there any more.

## Fixed in convergence round 4 (for the record)

Measured on trials with W2.3 9e00cfb, without and with W2.5 1dc7587: 186 probes (rounds 1 to
3's 136, the verifier's 30 and this round's 20) on g++ and zig c++ before (70f3e7f) and after
the change. Every probe that changed is below: each read freed memory or kept a dangling view
before (`lentthisref` printed the right size of a view into a freed Bag), or is a loop W2.3
now copies. The ones that compile among the changed and their neighbours (`loopfresh`,
`loopfresh2`, `loopfresh3`, `loopfresh_nf`, `loopfresh_r3`, `loopfreshok`, `lentok`,
`viewparamkept`, `viewlend2`, `viewlend3`, `lentalias`, `lentvalok`, `viewbeforeok`, `mutthis`,
`mutparam_nf`, `recvfree`, `implicitcall`, `fxparam3`) are ASan-clean on MSVC.

- **A view that leaves the call into storage only the call keeps alive** (significant #1).
  `lentref`: a `Ref<View<Int32>>` parameter is a way out, so the List parameter the body views
  stays the caller's reference and is not copied at entry. `lenthandle`: a handle parameter
  copied at entry (an effect may drop the caller's handle) whose object's storage a view that
  may leave the call points into is refused, since the copy may be its last owner at return;
  `lenthandle2`: the same for a method that holds itself. `lentlocal`, `lentlocal2`: a view
  returned through a local's handle is refused (W2.5 refuses it too). `lentfx` (the view handed
  to an `Fx` parameter) is refused. Each was a heap-use-after-free under MSVC ASan.
- **Views the view check did not follow** (significant #2). A view a call returns is followed
  into what its receiver, its arguments, an implicit `this` and, for a Kira callee, the
  program's globals reach (`viewhandle`, `viewmethod`, `viewfn`, `viewglobal`); a view kept in
  a local container (`viewlist`), an if-expression (`viewif`), a `Maybe` (`viewmaybe`) or a
  generic box (`viewbox`) holds what its parts do; a view a lambda captures is checked where
  the lambda may run (`viewcapture`, `viewcapture2`, `viewforeach`); a view read by a call
  after a later argument moved it (`viewrecv`, `viewrecv2`), a loop over a view whose body
  moves it (`loopview`, `loopview2`), a view of a block's local read after the block
  (`viewblock`), and one reached through a local reassigned since (`viewreassignh`) are
  refused by name. `refview`, a heap object given a view into a temporary, is refused by the
  construction itself (KI-19). Each printed garbage on g++ or was a heap-use-after-free under
  MSVC ASan; on W2.3 alone, `loopview`, `loopview2`, `viewrecv`, `viewrecv2`, `refview`,
  `lentlocal` and `lentlocal2` no longer rely on W2.5 (KI-13).
- **The loop over a temporary is W2.3's copy** (significant #3). Round 3's refusal made the
  evalorder golden and two CppHoisterTest cases fail on W2.3 9e00cfb; it is gone (KI-18), and
  the three pass again: on W2.3 alone `./gradlew test` fails only the KI-1 spills and KI-19,
  and with W2.5 the failing set is 70f3e7f's less those two CppHoisterTest cases (KI-9).
- **Narrowed so as not to regress** W2.3's evalorder golden, which the first form of the new
  rules refused in six places: a lambda given to a local is checked where the local is called
  (the golden's `pk()`, `pu()`, `pa()`, `pb()`, `pf()` run at once), and a view a Kira callee
  stores through a `mut` argument is followed into class storage only (KI-21).
- **Minor items.** The ledger names the W2.3 each measurement holds for (the header); KI-13
  lists the shapes still unsafe on W2.3 alone; KI-18 records the dependency on W2.3 9e00cfb;
  KI-19 records the rewire's lost refusal; `viewcapture2` is refused here.

## Fixed in convergence round 3 (for the record)

Each shape was measured on a trial with W2.3 and W2.5 (g++, zig c++ and MSVC ASan): before
the change it printed freed heap bytes, crashed or printed the wrong value; after it, it prints
Kira's values with ASan clean, or is refused by name.

- **The copy at entry broke a view the function returns** (significant #1, a regression of
  round 2: `viewlend2`, `viewlend3`). A parameter a returned view may point into stays the
  caller's reference (KI-15); both print 1 2 again.
- **A `mut` argument one field away from `this` or a parameter** (significant #2: `mutthis`,
  `mutparam` and their `_nf` forms). The walk counts a `mut` argument as a use of the object
  it lies in until the call returns, so a method holds itself (`setAfter(h, mut label)`) and a
  parameter is copied at entry (`const kira::Rc<Item> it = itRef_;`), which keeps the Item
  alive while `setAfter` writes into it. A place one field away from `this` or a parameter is
  taken as kept only when the method holds itself or the parameter is copied.
- **A receiver freed by its own argument** (significant #3: `recvfree`, `implicitcall`). The
  walk counts the receiver as used after the arguments, so the method holds itself.
- **A range-for over a temporary's field** (significant #4: `loopfresh`, `loopfresh_nf`).
  Refused by name; `h.item.unwrap().labels` and `makeItem().labels.toArr()` stay loops. (Round
  4 leaves it to W2.3 9e00cfb's copy, KI-18.)
- **A field's default was never checked** (significant #5: `fielddefault`). A field's default
  is checked as a body is, the lambda-parameter rule included.
- **A template `Fx` parameter bound to an `Fx` an object holds** (significant #6: `fxparam3`
  freed its own capture; `fxparam2` printed `other`). It is copied at entry
  (`const auto g = gRef_;`), as a `kira::Fn` parameter already was; both print the captured
  string.
- **Found this round, not listed.** A view of storage read after an effect that may move it
  (`viewbefore`, `viewlocalmut`, `viewreassign`) or handed to a call that may
  (`viewparamgrow`) printed freed heap bytes and is refused (KI-17); W2.5 accepts all four. `theOopGoldensNeedNoGuard` now fails when a golden emits
  no files, and checks every `Ref_`, where `Ref_ = ` never matched a copy line.
- **No old probe changed.** 132 probes (the earlier rounds', the verifier's and this round's)
  ran on the trial with W2.3 and W2.5 before and after the change: only the ones above differ
  (and `lentalias`, OD-4). The 27 that compile among the changed and neighbouring ones
  (`viewlend*`, `mut*`, `recv*`, `fxparam2/3`, the rewrites, `uaflist`, `uaf3`, `stralias2`,
  `genalias4`, `selfkill2`) are ASan-clean on MSVC.

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
