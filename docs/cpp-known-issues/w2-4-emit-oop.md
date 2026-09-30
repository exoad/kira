# W2.4 (classes, traits, generics): known issues

The C++ backend's classes part: `CppClassEmitter.kt`, `CppGenericsEmitter.kt` and the tests
under `src/test/kotlin/net/exoad/kira/cpp/oop/`. Each entry says what the issue is, where it
lives, how to reproduce it, and why it is safe to leave for now. "Measured" means the probe
was run through this branch's CLI (`build/install`), then g++ 13.2 and zig c++ 0.15 with the
goldens' warning flags and `-Werror`, and MSVC 14.44 with `/fsanitize=address`.

Round 4 (copy by default, `50-round4.md`) deleted the lifetimes analysis, `CppClassLifetimes.kt`.
Every entry that described it is closed below in one line; the history sections at the end
keep the rounds that built it.

## Round 7: a Maybe of a Maybe keeps both levels

- **The finding (round 6, significant, D32).** A `Maybe` of a `Maybe` of a class collapsed to one
  level. rt.hxx made `Maybe<C>` the very `std::shared_ptr<C>` that `C` is
  (`MaybeOf<std::shared_ptr<U>>::type`), so `kira::Maybe<kira::Maybe<kira::Rc<Node>>>` was one
  shared_ptr, and a stored null (Some(None)) read as nothing there (None). The verifier's b10
  (`Map<Str, Maybe<Node>>.get` of a key mapped to null) printed 0/1/0, then "unwrap of an empty
  Maybe", exit 127, on g++, zig c++, MSVC and MSVC ASan. Kira's value is 1/1/0/1. Stack.pop and
  peek, Queue.dequeue, Deque.popFront and popBack, Map.remove and a generic `Maybe<T>` at
  `T = Maybe<C>` return the same type.
- **Fix, in the runtime; the emitter's spelling is unchanged.**
  - rt.hxx: `Maybe<C>` is `kira::MaybeRc<C>`, the nullable Rc as a type of its own. It derives
    from `std::shared_ptr<C>` and adds no member, so one level is still a null handle of the
    same size, which reads, compares, unwraps, upcasts and converts to and from `kira::Rc<C>` as
    before. A Maybe of it is `kira::Nested<kira::MaybeRc<C>>`.
  - core.hxx: `kira::Nested<M>`, a `std::optional<M>` with a constructor and an assignment from
    `kira::none` that are not templates, so `none` is the OUTER empty Maybe. A plain optional
    takes `none` as an M through its converting template. `Maybe<Maybe<T>>` of a value is
    `Nested` too: `Maybe<Maybe<Int32>> x = null` did not build on any compiler ("conversion from
    'const kira::None' ... is ambiguous"), the same finding's value twin.
- **Kept and dropped from the stopped round-7 fixer's edits.** Kept: `Nested` (core.hxx),
  `MaybeRc` and its `MaybeOf` (rt.hxx), and its rt_test rows, reworked. Dropped: `kira::MaybeArg`
  and CppTypeSpeller's `kira::MaybeArg<...>` around every Maybe template argument. It changed
  the emitted text, and it still collapsed wherever C++ deduces T from a `Maybe<C>` value, which
  was still a plain shared_ptr.
- **Tests.** New `CppMaybeNestingTest` has 4 tests. The text test checks that the spelling is
  unchanged. The run test builds b10 verbatim (its `fx main` made `pub fx run`) and two twins
  on gcc, clang and msvc, with static_asserts on the two C++ types:
  - b10s covers Stack peek and pop, Queue.dequeue, a generic `some<Maybe<Node>>`, `x = null`
    and Map.remove, and expects 2 2 1 0 / 1 0 / 1 2 / 0 1 2 0 / 1 0.
  - b10v is the value twin and expects 1 2 0 / 0 1 2 0.

  rt_test goes from 125 to 139 checks and gains 6 static_asserts, one of which replaces the old
  "`Maybe<C>` is `std::shared_ptr<C>`". They cover Map.get, Map.remove, Stack, Queue and Deque
  of `Maybe<C>`, assignments, upcast, Weak, dynamic_pointer_cast, and `Maybe<Maybe<Int32>>`
  with `none`. Mutation check: with `MaybeOf<std::shared_ptr<U>>::type` set back to the shared_ptr, 3
  of the 4 tests fail (the gcc, clang and msvc runs).
- **Measured** with this branch's CLI and runtime, through the verifier's probe.sh on g++ -O1,
  zig c++ -O1, MSVC /O1 and MSVC ASan. b10 prints 1/1/0/1, b10s
  2/2/1/0/1/0/1/2/0/1/2/0/1/0 and b10v 1/2/0/0/1/2/0 on all four, with 0 ASan reports. Before
  the fix, b10s printed 2/2/0/0/0/0/0/2/0/0/2/0/0/0 and b10v did not build. Before and after,
  the emitted `.kira.cxx` files are byte-identical. rt_test under MSVC ASan: 139 checks, 0
  failed, 0 reports.
- **No regression.** The 65 projects of round 6's replay that spell `kira::Maybe<kira::Rc<` or
  `kira::Maybe<kira::Maybe` were rebuilt from their emitted text against the trial runtime with
  this fix applied. On g++ -O1 -Werror and zig c++ -O1 -Werror, 62 print exactly what the replay
  recorded. The other 3 on each compiler did not build in the replay either:
  - gcc: k_edrop (W2.3's `-Wmaybe-uninitialized` minor), k_q7 and x5q_q7;
  - clang: k_q7, x5q_q7 and z_asg3.
- **Acceptance, on the branch.**
  - `./gradlew test --rerun-tasks --continue`: 1322 tests in 99 classes, 0 failures, 0
    skipped. That includes the oop classes' 121, CppGoldenEmitTest 13 and CppGoldenCompileTest
    79.
  - `bash examples/regenerate.sh --check`: all snapshots current.
  - `run.sh`: 57 passed. `goldens.sh`: 17 cases, 67 passed. `sys.sh`: 18 passed. `msvc.bat`:
    all passed.

## Copy by default, round 4

- **Merges.** `cpp/w2-5-rules` b5910b6 as 3165df3, then `cpp/w2-3-emit-exprs` c06e011 as
  5c3c53e, both `--no-ff` and signed. `git merge-tree` showed no conflict before either, and
  there was none: no resolution to record. On the two merges alone, `./gradlew test` ran 1349
  tests with 9 failures. 4 were CppCopyPolicyTest's (this package's lifetimes refusals, "the
  call through gfs[0], an Fx an object holds" and "the loop over gl, storage an object holds",
  refused W2.3's policy programs). 5 were CppClassLifetimesTest cases asserting this package's
  `mut`-argument refusal, which W2.5's rule M now makes at typing.
- **Deleted (50-round4 3.1).** `CppClassLifetimes.kt` whole (1406 lines: the snapshots, the
  self-holds, every refusal, and the third effect analysis under them). In `CppClassEmitter`:
  `copiedParams`, `snapshotParams`, `isReference`, `copiedAtEntry`, `referenceNames`,
  `copyLine`, `templateFxCopy`, `valueText`, `holdLine`, `reportRefusals`, `declGuards`,
  `valueCopy`, `refuseTraitHold`, the hold and copy lines of `methodDefinition` and
  `inheritedDefinition`, the refusal calls on field defaults, `initially` and `finally`,
  `CppClassFacts.lifetimes`, and the clause that gave a class `kira::Shared` because a method
  held itself. `CppGuards` and `CppClassesPart.guards` (`CppEmitContextImpl`), and the guarded
  parameter's rename and prologue in `CppDeclEmitter.definition`. `CppClassLifetimesTest.kt`
  (1443 lines). A class body now reads its parameters and its receiver as they are: every Kira
  caller keeps invariant I through W2.3's `CppCopyPolicy` (a `const&` argument is storage
  nothing changes during the call, or `T(e)`; a handle receiver is `kira::Rc<C>(h)->m(...)`
  unless W2 or W3 keeps the slot still; an `Fx` value's callee is `kira::Fn<...>(f)(...)`).
  `mutArgRefusals` and `overlappingMutArgs` are W2.5's rule M (`rules.exclusivity.mut`) and
  D37's `rules.exclusivity.argument`. The derived `const` and `refusedUnderInitializer` stay:
  neither is a lending point.
- **The seam's classes half (KI-25).** W2.3's head still spells `this`, `self`, upcasts,
  constructions, type-parameter receivers and explicit type arguments itself, with a note that
  "at the merge this delegates" to this package. The round-3 trial did that in its seam
  commit (c10000b), which is on no branch. This round applies its classes and generics half:
  `CppLowering.upcast`, `classThis`, `selfPointer` and `classConstruction` call
  `CppClassesPart.upcast`, `thisValue`, `selfReceiver` and `construct`; `paramReceiver` and
  `explicitTypeArgs` call `CppGenericsPart.receiver` and `typeArguments`; an escaping lambda's
  `[self = ...]` calls `CppClassesPart.selfCapture` (`CppClosureEmitter`). W2.3's `ownRc` and
  its own construction body go. Without it, five round-1 and round-2 probes (initcall2,
  initlam, fincall2, finlamthis, initlamthis) compiled and exited 127 with `std::bad_weak_ptr`
  on g++ and zig: W2.3's `this` never asks `refusedUnderInitializer`. With it, the five are
  `cpp.unsupported` as in round 3, and a construction runs R-D and OQ-2.
- **Goldens.** chain and classes are `emit: required` again (as the round-3 trial had them),
  with `expected/` regenerated and read line by line. chain gains the one range copy 4.1
  predicts (`kira::List<kira::Rc<Behaviour>>(loaded)` in `Chain::has`). classes gains the D33
  spill of `Animal::describe` (KI-1's hunk, the literal reading). sender stays `emit: pending`,
  and its `expected/` gains the two `Fx` callee copies 4.1 predicts (`write`, `clock`). Every
  other `expected/` file is byte-identical. Emitted with the CLI, forward still needs W2.6.
- **Tests.** New `CppClassCopyTest`, 17 tests:
  - one module of 26 programs, one per CppClassLifetimesTest shape (uaflist, uaf3, mutthis,
    mutparam, recvfree, implicitcall, genalias4, an override taken by `const&`, selfkill2,
    capthis, a class template's detach, a trait call that drops the caller, a trait default
    that drops its object, chain's `has`, a STABLE `mut` argument, fxself, lamparam, a view of
    a `List` parameter, loopfresh, fxparam2 and 3, capparam, capparamfree, genrecv, a
    type-parameter field receiver, dispstr, a trait receiver in a field and as a parameter,
    alb). Each value is written from Kira's semantics; all 26 print it on gcc, clang and msvc.
  - The text check: no `Ref_ =`, `keepAlive_` or `weak_from_this().lock()`; 17 copies and holds
    pinned at their call sites, and the two lends (a STABLE `mut` place, a local handle).
  - Rule M rows (externmut, an `Fx` value, externarr over five holders, a dispatched call given
    a held `Fx`; a harmless lambda accepted), and the rules that stay: the loop rule (bagloop2,
    fielddefault), D37 (`swap(mut x, mut x.label)`), order clause 3 (`item.greet(replace(v))`).
  - The construction refusals and the view `cpp.internal` backstop, moved unchanged.
  - 7.3's static check over every generated file under `src/test/resources` and over chain,
    sender and classes emitted with the standard parts.

  CppClassShapeTest's 7 pins of `vRef_` assert the reference read as it is and no `Ref_`.
  CppClassCompileTest keeps its 50 checks and their values; 7 of its C++ driver calls now keep
  contract 5.4.4 by hand (`kira::Str(...)`, `std::int32_t(...)`, `kira::Rc<C>(...)` around an
  argument the call may write, or a receiver it may drop), because a C++ caller that breaks it
  now reads freed memory.
- **Replay (50-round4 6.6 and 7.1), on this branch's CLI.** Every accepted probe printed the
  same value on g++ and zig, and MSVC ASan reported 0 on each of the 81 it built.
  - Round 1 (`ver-w24r1/probes`, 33): 26 accepted. The 7 refused are allowed
    (`rules.view.write`), externmut and fincall (`rules.exclusivity.mut`), initcall
    (`rules.exclusivity.receiver`), and initcall2, initlam and fincall2 (`cpp.unsupported`,
    under an initializer). Against round 3's log, one changed: trtdeflam (a trait default that
    frees its object, then makes a lambda) is accepted and prints the name, since the caller
    holds the object. externstr needs its C++ definition (`vr3w24/probes1`): the label and 63.
  - Round 2 (`ver-w24r2/probes`, 58): 28 accepted, 30 refused. Refused: externarr and
    externnested2 (`rules.exclusivity.mut`), forlocal (`rules.exclusivity.loop`), 23
    `rules.view.write` rows, finlamthis, initlamthis, initlamvirt, inittrait and traitthis
    (`cpp.unsupported`), and fielddeflam and refself (the probes' own type errors). The same
    set as round 3. ctordefaults 1 2 3 4 5 6, ctordefaults2 1 2 3, ctordefaults3 1 2 4 3 5 6,
    allowed2 6 6 5 12 4 12 112, earlyview 1006 1006. externnested and externfnval, which failed
    to compile on round 3's trial (no `kira/ffi.hxx`), print the label and 63 here.
  - Round 3 and 4: rp1, rp1b, rp1c, rp1e, rp1f1-f4 and rp1g print the old text (Kira's), 0
    ASan (rp1b and rp1g were heap-use-after-frees). dispstr and dispstr2 print the label
    (w2-4 minor #1: accepted and copied). tw1-tw3, rcc_trait, rcc_virt, rp3 (101 102 203
    204), oq1 and proto1 (5) as round 3. gv1 and gv2 stay refused (`rules.view.*`). r4d p1,
    p3 (47 6 5) and p5 (`old-name...:1`), sc3v23 n3 (104 1501 501 501 501), al and alb
    (16000 each), ctororder, q7 with a main added (6000 6000 0) and r3v6 t20 (82) print Kira's
    value. The emitted p3 and p5 `.cxx` are byte-identical to r4d's hand-lowered p3fix and
    p5fix; p3's header differs in one line, `class Node final` where p3fix still derived
    `kira::Shared<Node>` for the hold this round deletes.
  - Not run here, since each needs an `@_extern` module and W2.6 is not merged: the r2v6 set
    (a1, b1, b1c, b1k, b1u, b9b, b9c), b1ki, and r3v6's t15, t7v, t7k, t7u and t18k. Every one is
    refused here with "the extern declaration ... is not lowered yet". They are the trial's.
  - 7.3's static check: 0 hits for `Ref_ =`, `keepAlive_` or `weak_from_this().lock()` in 37
    generated files under `src/test/resources` and in every emitted probe.
- **Acceptance, on the branch.** `./gradlew test`: 1318 tests in 98 classes, 0 failures, 0
  skipped. The oop classes run 117 (CppClassCopyTest 17, CppClassShapeTest 91,
  CppClassCompileTest 4, gcc, clang, msvc and zig-aarch64, CppOopGoldenShapeTest 5);
  CppGoldenEmitTest 13 and CppGoldenCompileTest 79. `bash examples/regenerate.sh --check`:
  all snapshots current. `run.sh` 57 passed; `goldens.sh` 17 cases, 67 passed (chain 16,
  classes 15 and sender 10 checks on gcc, clang and msvc); `sys.sh` 18 passed; `msvc.bat` all
  passed.
- **Round 3's findings this package owns.**
  - w2-6 #3 [owner w2-4], the entry snapshot keyed to an unrelated class: closed by copying at
    the call, and kind 1 is deleted. t20 prints 82 (`lenAfterF(kira::Str(g), cb.f)`), 0 ASan.
    CppClassCopyTest's rows cover the same shapes over classes. t15, t7v, t7k, t7u and t18k go
    through an `@_extern` module (see above).
  - w2-4 #0 [owner w2-5], rp1*: closed by the copy at the call (W2.3's policy, W2.5's
    CONFINED). Measured above.
  - w2-4 #1 [owner w2-6], the missing `kira/ffi.hxx`: W2.6's fix. On this branch no
    prototype call spells `kira::ffi::` (W2.3's `externCallText`), so the four probes print
    the label and 63; the finding is to be checked on the trial with W2.6 merged.
  - Minors. #0 (sender `emit: pending`): still pending; its `expected/` is current. #1
    (dispstr refused): accepted and copied, and it prints the label. #2 (`lambdaParamRefusals`):
    deleted, with lamparam as a run row. #3 KI-22, #4 (an override's own default, a spec
    question for the typer) and #5 KI-23: unchanged.

## Known issues

### KI-25. The seam's externs half is W2.6's, not applied here

- **What.** The round-3 trial's seam also routed `externCallText`, `externConstant`, every
  field read (`CppExternsPart.field`) and an extern used as a value
  (`CppExternEmitter.globalName`) to W2.6's parts. This branch has no W2.6, so those stay
  W2.3's. Only the classes and generics half is applied (round 4, above).
- **Where.** `CppExprEmitter.kt` and `CppClosureEmitter.kt`, W2.3's files. The hunks are
  `upcast`, `classThis`, `selfPointer`, `ownRc`, `paramReceiver`, `explicitTypeArgs`,
  `classConstruction` and the `[self = ...]` capture, none of which W2.6's half touches.
- **Why it can wait.** It is the integrator's to apply once W2.6 is merged, as in round 3.
  The trial must apply the externs half only, or the classes half conflicts with itself.

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

- **What.** Round 4 edits `CppExprEmitter.kt` and `CppClosureEmitter.kt` (W2.3's) for the
  seam's classes half (KI-25), and deletes only the guard plumbing from `CppEmitContextImpl.kt`
  (the TOUCHES file: `CppClassesPart.guards` and `CppGuards`) and `CppDeclEmitter.kt` (W2.2's:
  the rename and the prologue in `definition`), as 50-round4 6.2 asks. Earlier rounds edited
  `CppTypeSpeller.kt` (`spellUnder`, the parameter-leaf hook, the `X* const&` rule),
  `CppDeclEmitter.kt` and `CppDeclEmitterTest.kt`, and `CppEmitContextImpl.shadows`.
- **Why it can wait.** Each is the smallest change at the seam. W2.6's round 4 changes
  `CppExprEmitter`'s extern functions, other hunks of the same file.

### KI-6. What was not measured

- **gcc 11.4**, the design's floor, is not installed locally. gcc 13.2, zig clang 0.15 and
  MSVC 14.44 were used.
- **zig-aarch64 only compiles.** `CppClassCompileTest`'s aarch64 case builds the classes and
  the driver but runs nothing (no emulator). The checks run on gcc, clang and MSVC.
- **CppClassCopyTest runs without ASan.** Its 26 programs ran under MSVC `/fsanitize=address`
  as a probe (`lifetimes`, 0 reports); the test itself builds gcc, clang and plain MSVC.
- **Why it can wait.** None of these gaps can hide a wrong program the other toolchains would
  not show; clang's left-to-right order is in the test.

### KI-22. A field default's lambda that calls a later `pub` function does not compile

- **What.** `sink: Fx<...> = fx(v) { return total(v) }` puts the lambda in the constructor's
  default argument, in the class body in the header, and the header declares the `pub`
  function `total` after the class: g++ says 'total' was not declared in this scope, zig 'use
  of undeclared identifier'. The same shape with a private helper is moved into `impl_` and
  works (second-class round 1's verifier, minor).
- **Where.** The header's order (W2.2's design 4.2 placement) against a default argument the
  classes part spells in the class body.
- **Why it can wait.** It is a loud compile error, not a wrong program; the rewrite is to
  declare the function before the class.

### KI-23. A field whose default is not PURE takes a `std::optional<T>` from C++

- **What.** R-D and OQ-2 run such a default inside the constructor, so its parameter is
  `std::optional<T> x_` (`= std::nullopt` in the trailing run). A C++ caller that constructs
  the class gives a value (`std::make_shared<Three>(7)` converts) or leaves it out; for a
  `Maybe` field it must say `std::make_optional<kira::Maybe<X>>(kira::none)`, since a bare
  `kira::none` converts to the parameter's own empty optional.
- **Where.** `ClassLowering.constructorParams`, `memInitializers` and `construction`.
- **Why it can wait.** A C++ caller's mistake is a compile error, not a wrong program, and
  bibo's C++ builds no such class today.

### KI-24. A struct's field default runs before a given value (superseded by W2.9)

- **What.** W2.2 lowers a struct's field defaults to default member initializers, so
  `S { b = next() }` with `a: Int32 = next()` declared first runs a's default first. The
  `structdefaults` probe prints `1 2` on g++, zig and MSVC; R-D's order is a=2, b=1.
- **Where.** W2.2's `CppDeclEmitter` struct lowering, not this package's.
- **Why it can wait.** It exists only in struct code: superseded by W2.9 (no struct).

### KI-26. A prvalue class receiver whose finally is impure dies after the full-expression

- **What (round 6 minor, pre-existing).** E-DROP spills only COPIED receivers. In
  `gs = mk().name()`, where Res's finally sets `gs = "fin"`, the temporary dies after the store,
  so the program prints fin, where the design (2.5) gives made.... In the same way,
  `mk().name().length() > 0 && mark()` logs mF where the design gives Fm. This is the receiver
  twin of KI-16's prvalue argument row.
- **Reproduce.** scratchpad/v6w24r/atk/b3 prints fin on all four builds.
- **Why it can wait.** The value is deterministic, the same on every compiler, and ASan is
  clean. Only the order of a finally against the end of the statement is late. Not in round 7's
  narrow scope.

### Closed in round 4

- KI-1 (chain and classes differ by one D33 spill each): both are `emit: required` with the
  spill in `expected/`.
- KI-7 (a struct's `this` and receiver unguarded), KI-16 (receiver paths relied on W2.3's
  handle copy): a receiver is W2.3's policy's (copied unless W2 or W3), the classes part
  guards nothing.
- KI-8 (kira:sync made the analysis conservative), KI-10 (a hold delayed `finally`), KI-11
  (four refusals a lowering could replace), KI-12 (constructions counted as releases), KI-13
  (the guards relied on W2.5 for globals), KI-14 (`keepAlive_` and `vRef_` could meet a C++
  name), KI-17 (`overlappingMutArgs`), KI-18 (a range over a temporary): deleted with
  `CppClassLifetimes.kt`. The four shapes of KI-11 are CppClassCopyTest run rows (bagloop2
  stays refused by W2.5's loop rule). KI-17's shape is D37's `rules.exclusivity.argument`.
- Earlier: KI-5 (every commit is signed), KI-9 (MutabilityPass fixtures), KI-19
  (CppHoisterTest's twelfth refusal).

## Open decisions

### OD-2. What C++ supplies, and what a C++ caller owes

- **What.** An `@_extern` function, a bodiless `pub` prototype, an `@_opaque` method, a C++
  override of a Kira trait (the chain driver's `Greedy`) and an `Fx` a C++ caller builds (the
  sender driver's `write`) are C++ code the checker does not see.
- **The contract.** 30-second-class.md 5.4: such a callee writes Kira storage only through
  its `mut` arguments and its receiver, and runs Kira code only through what it is given.
  What it may run is W2.5's `CallReach` (`mayRunAnything`, `confined`); the classes part
  asks nothing. New in round 4, 5.4.4: C++ that calls a Kira function or `Fx` passes each
  non-`mut` argument as storage nothing changes until the call returns, and holds the
  receiver. A Kira body reads its parameters and `this` with no guard, so a C++ caller that
  breaks it reads freed memory (CppClassCompileTest's driver keeps it by hand).
- **The option left for the user.** Take a dispatched call's receiver as holding an `Fx` a
  C++ override may run. The receiver is copied at the call already (never CONFINED), so this
  would change only rule M's answers.

### OD-3. A field that is neither `require` nor defaulted, skipped at construction

- **What.** The typer lets `X { n = 1 }` leave out `pub c: Other` when `c` is neither
  `require` nor defaulted. D38 value-initializes it. Where that value is not one Kira has (a
  class, trait, `Ref` or system class handle, an `Fx`, or a struct or tuple holding one) the
  classes part refuses the construction (the `nullfield`, `nullfield2` and `nullfx` probes
  crashed). An enum field left out the same way is value-initialized to 0, which is not an
  entry of an enum whose values start elsewhere.
- **The question.** Should the typer require every field without a default at construction
  (D38 already says Kira requires a `require` field), which would make both the refusal and
  the enum's 0 unreachable?
- **Current behaviour kept.** The construction is refused where the value would be a null
  handle or an empty function; the enum case is value-initialized as D38 says.

### OD-5. Decision 4b's "impure" read literally, not as "has hidden writes"

- **What.** 4b's "any impure call, when the place lies in a mutable class" is read as
  `effect(C) == IMPURE` (from second-class round 2), W2.5's to implement. This package writes
  no view rule, and none of its tests depends on the softer reading.
- **The option left for the user.** The softer reading, `hidden(C)` not empty. Not
  implemented.

## Routed to other packages

- **The integrator.** Apply only the externs half of the round-3 seam (KI-25), and keep chain
  and classes `emit: required` (they are this branch's now). CppClassCopyTest runs on the
  trial as it is; its extern rows are typer and text rows only.
- **W2.6.** Round 3's w2-4 #1 (no `kira/ffi.hxx` where a prototype call spells `kira::ffi::`)
  is yours; on this branch proto1, externstr, externnested and externfnval print the label and
  63 because nothing spells `kira::ffi::`. The t15, t7v, t7k, t7u and t18k runs (w2-6 #3) and
  the r2v6 set need your parts.
- **W2.3.** None of the policy's class paths needed a change: the 26 programs print Kira's
  value with the copies where 2.0 puts them. One reading to confirm: a class `mut fx` receiver
  (`h.item.unwrap().grab(h)`) is copied as a handle (`kira::Rc<Item>(...)`), never bound.
- **bibo (W2.8).** Hand-written C++ that calls Kira code keeps contract 5.4.4. The chain and
  sender drivers pass locals and hold their objects in locals today (CppGoldenCompileTest
  runs them), and nothing checks it.
- **W2.9.** An immutable class lowered to a C++ value must keep R-D's construction order
  (KI-24). A value class's receiver is W2.3's policy's (P2, W2), not this package's.

## Fixed in second-class round 3 (for the record)

Round 2's verdict named two significant issues (w2-4 #0 construction order, #1 what a callee
C++ supplies may run) and five minors; section 4.2 of `40-round3.md` named the 7 tests W2.5's
rules turned red, and W2.5's round 3 (f86261f) turned two more. This round merges
`cpp/w2-5-rules` f86261f first (cdaf753, clean), so every test here runs against the rules.
Measured on the branch (the whole `./gradlew test`: 1209 tests, 0 failures, 0 skipped; the oop tests 150, the compile test on all four toolchains) and on a trial of this
round's tree with W2.3's head `8bac10a` (W2.3 with the same W2.5 merged) merged without a
commit and round 4's `rewire.py` applied: 18 probes through its CLI, each on g++ 13.2 and zig
c++ 0.15 with `-Werror`, and MSVC 14.44 `/fsanitize=address`. The trial's whole `./gradlew
test` with the three OOP goldens at `emit: required` runs 1321 tests with 39 failures, none in
this package's classes: 33 CppHoisterTest cases and 4 on the evalorder golden (40-round3 4.1,
W2.3's round-3 work against W2.5's round-3 rules) and KI-1's two hunks; `CppGoldenCompileTest`
runs 79 with 0 failures.

- **Construction order, R-D, and OQ-2 (w2-4 #0; w2-4 minor #2, now the user's decision).** A
  field default that is not PURE (`CppClassFacts.pureDefault`: EffectsPass's PURE, or
  `operandRank`'s) is no C++ default argument any more. Its constructor parameter is
  `std::optional<T> x_` (`= std::nullopt` in the trailing run), the construction passes
  `std::nullopt` when it leaves the field out and `std::make_optional<T>(v)` when it gives one,
  and the field's own mem-initializer runs the default:
  `x(x_.has_value() ? std::move(*x_) : static_cast<T>(default))`. C++ runs the superclass's
  constructor, then the members in declaration order, so the given values run first (at the
  call, ordered by the existing D33 spill), then the defaults in declaration order, a
  superclass's fields and its `initially` before the subclass's fields: Kotlin's order, the
  user's OQ-2 answer. A PURE default stays a default argument, so no golden changes (the
  goldens' constructor defaults are `0`, `5`, `-1` and `kira::List<...>{}`). A `Ref` or a
  system class is the runtime's and keeps its constructor. Probes: ctordefaults prints
  `1 2 3 4 5 6` (Two 1 2 3; Kid seen=4, k=5, after=6: the design's probe), ctordefaults2
  `1 2 3`, ctordefaults3 `1 2 4 3 5 6` (Kid 1 2; `Mid { y = next() }` y=3, then x=4, z=5, w=6),
  the same on g++, zig and MSVC with 0 ASan reports (round 2: `3 2 1` on g++ and MSVC, and
  seen=5, k=4 everywhere). Tests: `CppClassCompileTest` gains 7 runtime checks (50 now: Three,
  Late, High, Mix and a given Mix, a C++ caller's `make_shared<Three>(7)`, and a `Maybe` field
  given null). With the rule reverted (`pureDefault` always true), 6 of 50 fail on gcc and
  MSVC and 2 on clang; with it, 0 of 50 on gcc, clang and MSVC, and the aarch64 build
  compiles. Without the explicit `std::make_optional` wrap, no toolchain compiles the Slot
  construction. Shape tests: the deferred middle default (`Duo`), trailing defaults, the
  subclass after its superclass's `initially`, a superclass's optional forwarded, the wrapped
  null, and 6.4's static check that no golden constructor default is a call.
- **What a callee C++ supplies may run, R-C and R-G (w2-4 #1).** `fxArgs`/`fxArguments` are
  gone. `mayBeSuppliedByCpp` is W2.5's `FnSymbol.suppliedByCpp` (or the EXTERN kind) plus a
  VIRTUAL or TRAIT dispatch; `cppRuns` gives what such a call may run: a lambda written as an
  argument runs its body, a function named as a value its body, and any other argument whose
  type `CallReach.mayHoldFx` (W2.5's one predicate) runs everything, a class field's `Fx`, an
  `Arr`, `List`, `Maybe` or tuple of one, a type parameter. A body C++ supplies directly (an
  extern or `@_opaque` method) may also run what its receiver holds; a dispatched call's
  receiver is left to OD-2. A call through an `Fx` value the analysis cannot see into adds
  what its arguments reach to the pool (a C++-made closure, OD-2). A bodiless function C++
  does not supply (the typer refuses one) may do anything. externnested (a `Wrap` holding the
  callback) copies `s` at entry and prints the label and 63 on all three, 0 ASan reports (was
  a heap-use-after-free); externarr (`bumpAll(mut h.item.count, [fx() Void { c.reset() }])`) is
  refused as a `mut` argument the call may free (was accepted and a heap-use-after-free).
  Tests: each of the five holders (a class field, an `Arr`, a `List`, a `Maybe`, a tuple)
  refuses the `mut` argument and copies the parameter at entry, a `List<Int32>` copies
  nothing, and a trait call given a held `Fx` refuses the `mut` argument. With round 2's
  predicate put back, 3 of those tests fail.
- **R-B replaces a refusal.** At a call C++ supplies directly, `cppCalleeArgRefusals` no
  longer refuses a `Str`, container, `Maybe`, `Result`, tuple or struct argument in storage an
  object holds: W2.6 copies it at the call (40-round3 R-B). It still refuses what R-B does not
  copy, a class, trait, `Ref` or `Weak` handle (`inspect(c.item, ...)`), and every such
  argument of a dispatched call, whose override a C++ class may write. Tests: the `Str` field is
  accepted, the handle and the dispatched `Str` refused. On this branch alone (no W2.6), the
  accepted `Str` shape is not yet copied: the integration order puts W2.6 before W2.4.
- **R-A reads.** Every place the lifetimes analysis reads (a loop's range, a C++-supplied
  callee's argument and receiver, a type-parameter receiver, a call through an `Fx`, what a
  call keeps in use) is `TypedModel.readPlace`, so `gl.get(0)` is the place `gl[0]`; writes
  stay `model.place`.
- **Construction effects.** Each field default is a body of the summaries, and a construction
  that leaves the field out carries its effects (it runs inside the constructor now).
- **Section 4.2's tests.** The four PROG shape tests and the compile test's five plain-`fx`
  writers are `mut fx`, and so are the methods they override (`types.override.signature`: an
  override is `mut` exactly when what it overrides is). `aMethodThatWritesItsReceiverIsNotConstEvenWithoutMut`
  is now `aPlainFxThatWritesItsReceiverIsRefusedSoConstFollowsTheDeclarations`: the typer
  refuses the plain writers (`rules.mutability.this` twice, `.method`). The lifetimes test W2.5's
  alias rule refuses first, and the two its round-3 loop rule now refuses first (bagloop2 and
  its field-default twin, q6), assert the checker's code, and assert the classes part's own
  refusal with the `exclusivity` pass off (`OopTestSupport.withoutRulePass`).
- **The derived `const` stays, for four shapes.** 4.2 asked to delete the inference. For a
  plain `fx` that writes its own state it is dead now (the typer refuses the program), but the
  rules let four shapes through that a `const` method cannot lower: a lambda writing through
  the captured `self`, a field passed `mut` (`grab(mut n)`), a view of a `mut` field (the typer
  lends a `MutView` in any class body and the statement part spells `kira::mutView(items)`),
  and a trait default body calling a `mut fx`. Narrowed to lambdas, round 1's `allowed2`
  stopped compiling on g++ and zig (`no matching function for call to 'mutView'`); with the
  inference whole it prints `6 6 5 12 4 12 112` on all three, 0 ASan reports. The shape test
  pins `grabIt` and `viewAll` as not `const`.
- **Round 2's probes, unchanged.** ctorwrong2 `old 7`, ctororder the label and 7, ctorthis the
  name and 7, genrecv the two names, genrecv2 the name, externstr the label and 63, ctorinlam
  the label and 7 twice; externmut refused; `allowed` refused by `rules.view.write` (correct
  under the literal reading); all the same on g++, zig and MSVC ASan (0 reports).
- **Round 2's minors.** KI-1 stays (the trial's golden numbers are in KI-1). The `allowed`
  note is corrected above. OQ-2 is decided and built (first item). KIRA_EXTERN_CHECK before the
  class it names (externnested2) is W2.6's, and on this trial the `@_extern` declarations are not
  lowered at all without W2.6. The order rule's over-refusals (earlyview, ctorinlam's `own`) are
  W2.5's F1: earlyview is accepted and prints `1006 1006` on all three, 0 ASan reports.

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
