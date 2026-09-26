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
  a00af85 the two messages were the same, and round 3's tree had 2 failures of 1113.)
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
  the evalorder golden and W2.5's rules against W2.3's and W2.2's fixtures. The round's 8 new
  tests pass there; those whose input W2.5 refuses first (`rules.exclusivity.order`,
  `rules.exclusivity.loop`, `rules.escape.view-return`) accept that, which is the same
  outcome.
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
  and `globaltrait` through a trait call) still prints freed heap bytes on g++, and a view
  stored in a generic class's field that a caller reads through another function (`genview`)
  prints garbage. W2.5 refuses them (`rules.exclusivity.alias`, and EscapePass's
  `rules.escape.view-field`). The same holds for a place passed twice to one call directly
  (`fxparam`: `callIt(b.f, b)`, which prints the right string since round 3's copy), which W2.5
  refuses as `rules.exclusivity.alias`, and for a loop whose body changes what it iterates
  through the variable the range is reached from (`loopreassign`: `for s in it.labels { it =
  Item {} }`, freed heap bytes on W2.3 alone), which W2.5 refuses as `rules.exclusivity.loop`:
  the classes part's loop rule covers a change made through another handle (`bagloop2`), which
  W2.5 cannot see. A view kept in a global, or in a class field of another object, is D5's
  and W2.5's EscapePass's (`rules.escape.view-store`, `rules.escape.view-field`): the view
  check follows the views one body keeps in its own locals.
- **No longer on the list (round 4).** `loopview`, `loopview2`, `viewrecv`, `viewrecv2`
  (unsafe on W2.3 alone; W2.5's `rules.exclusivity.loop` and `.order`), `refview` (W2.5's
  `rules.escape.view-store`), and `lentlocal`, `lentlocal2` (W2.5's
  `rules.escape.view-return`) are refused by the classes part itself now (see "Fixed in
  convergence round 4").
- **Where.** `CppClassLifetimes.collectHeld` and `holdable`, against W2.5's ExclusivityPass
  and EscapePass.
- **Reproduce.** `globalalias`, `globaltrait`, `genview` and `loopreassign` on a trial with W2.3
  9e00cfb alone print garbage on g++; on a trial with W2.3 and W2.5 each is refused (measured
  in round 4 over all 186 probes: these four are the only probes that are unsafe on W2.3 alone
  and refused only with W2.5).
- **Why it can wait.** The C++ backend is shipped with W2.5's rules: the cpp target runs them
  (design 3.4). This entry records that the classes part's guards are not safe without W2.5
  merged, so W2.5 has to be merged no later than this package.

### KI-14. `keepAlive_` and `<param>Ref_` could meet a C++ name in scope

- **What.** The hold's local is `keepAlive_` and a copied parameter's reference is `vRef_`.
  Kira's lexer refuses both spellings for a Kira name (an underscore is allowed only in
  UPPER_SNAKE_CASE), and W2.3's synthesized names are lowercase stems, so neither can meet a
  Kira name. A C++ name in scope can: a namespace-scope `keepAlive_` from an FFI header.
- **Where.** `ClassLowering.bodyName`.
- **Why it can wait.** The result is a loud `-Wshadow` compile error, not a wrong program.

### KI-15. A parameter a returned view may point into stays the caller's reference

- **What.** A parameter C++ takes by `const&`, of a type a view can point into, that the body
  takes a view of when a view may leave the call (`return xs.view()`) is never copied at entry:
  the copy was what the view pointed into, and it died at return (convergence round 3's
  significant #1). The reference sees what the caller's storage holds when the view is taken.
  Where that cannot be right, the body is refused by name: a read of the parameter after an
  effect that may free or move what it names, and a read of its value (not a view of it) after
  an effect that may change it (`lentval`: `trace(xs.size())` after `b.grow()`, with `c = b`,
  would read the grown List of 3 where Kira's parameter has 2; round 2's copy printed 2, then
  freed heap bytes through the view). Taking the view after such an effect is kept, and the
  view shows the caller's storage (OD-4).
- **Where.** `CppClassLifetimes.lentParams`, `Guard.lent`, `lentRefusal`. Whether a view may
  leave the call is approximated from the body and the program (its return type, a `mut`
  parameter, a parameter that reaches storage a view can be kept in (a `Ref` box or a generic
  class of a view, an `Fx`, a type parameter: round 4's `lentref`), a throw, a lambda, a view
  handed to C++, and any global or class field that can hold a view, an `Fx` or a type
  parameter), meant to cover every route W2.3's `CppEscapes.viewEscapes` counts (a callee that
  keeps a view keeps it in one of those).
- **Reproduce.** `viewlend`, `viewlend2`, `viewlend3` print 1 2 on g++, zig and MSVC ASan
  (clean); `lentval` is refused; its rewrite (`lentvalok`, the size read first) prints 1 5.
- **Why it can wait.** Where the approximation says a view may leave and none does, a copy is
  skipped that was harmless, and a read the copy made right is refused by name instead. It is
  never unsafe. Sharing W2.3's `viewEscapes` once both are merged would make it exact.

### KI-16. Receiver paths through a handle rely on W2.3 copying the `kira::Rc`

- **What.** A call whose receiver lies one handle away from a parameter or a field of `this`
  (`it.labels.add(h.take())`, `other.labels.add(h.take())`), with an argument that frees that
  object, is safe because W2.3 copies the `kira::Rc` of the receiver path before the
  arguments run. The classes part guards only `this`, which W2.3 cannot copy: a method whose
  argument may free its own object holds itself (`recvfree`, `implicitcall`).
- **Where.** W2.3's receiver ordering (`CppExprEmitter.receiverOperand`) and
  `CppClassLifetimes.Walker.call`.
- **Reproduce.** `recvfree2` and `recvfield` print the right values on g++, zig and MSVC ASan
  on a trial with W2.3 a00af85, and on g++ and zig on one with 9e00cfb (round 4).
- **Why it can wait.** It is measured safe on the W2.3 this package merges with. If W2.3
  stops copying a handle receiver, the same refusal as a `mut` argument's
  (`CppClassLifetimes.mutArgRefusals`) extends to it.

### KI-17. More refusals that a different lowering could keep

- **What.** Convergence round 3 refuses, by name and with the rewrite that compiles:
  - a view of storage the body does not own, or of its own local, read after an effect that
    may move what it points into (`viewbefore`, `viewlocalmut`, and `viewreassign`, where the
    local the view was reached from is pointed at a new object: freed heap bytes), or handed to
    a call that may (`viewparamgrow`): "take the view after the call" (`viewbeforeok` prints 3
    and 1), or view a local copy. A `List` method the analysis cannot tell from a resizing one
    (`items.set(0, v)`) counts as one that may move it;
  - a `mut` argument inside another `mut` argument of the same call (`f(mut x, mut x.label)`),
    which W2.5 refuses first as `rules.exclusivity.argument`.
- **Where.** `Walker.readView` and the view check in `Walker.call`, `overlappingMutArgs`.
- **Why it can wait.** Refusing is correct under the convergence policy; each rewrite is a
  local. (Round 3's refusal of a range-for over a temporary is gone: W2.3 9e00cfb copies such a
  range, KI-18.)

### KI-18. A range-for over storage a temporary keeps relies on W2.3 9e00cfb or later

- **What.** Round 3 refused `for s in makeItem().labels` and every range reached from a
  temporary through a handle, an element or an accessor, because C++'s range-for kept the
  `kira::Rc` temporary alive only to the end of its initializer. W2.3 9e00cfb copies such a
  range in the range expression (`kira::List<kira::Str>(makeItem()->labels)`,
  `CppHoister.rangeMayDangle`) and refuses a view of one (`cpp.view-lifetime`, the verifier's
  `loopfreshview`), so the classes part no longer refuses it: the refusal made the evalorder
  golden and two CppHoisterTest cases fail on that W2.3 (round 3's significant #3). On W2.3
  a00af85, which copies nothing, `loopfresh` and `loopfreshview` are a heap-use-after-free.
- **Where.** `CppClassLifetimes.loopRefusal` and `temporaryRange`, against W2.3's
  `CppStmtEmitter` range-for.
- **Reproduce.** On a trial with W2.3 9e00cfb (with and without W2.5): `loopfresh`,
  `loopfresh_nf`, `loopfresh_r3`, `loopfresh2` (an element's field, a `Maybe`'s value's field,
  a List in a fresh object's List) and `loopfresh3` (bodies that build and drop objects) print
  every label on g++ and zig c++ and are ASan-clean on MSVC; `loopfreshview` is refused by
  W2.3. The copied range's temporary is destroyed at the end of the range's initializer, so an
  Item's `finally` runs before the first iteration (`freed` is printed first), as D34's C++
  order has it for a temporary; a local (`loopfreshok`) prints `freed` after the loop.
- **Why it can wait.** It is safe on the W2.3 this package merges with; the entry records the
  order: W2.3 at 9e00cfb or later has to be merged no later than this package.

### KI-19. The rewire sends W2.3's `classConstruction` here before W2.3's own view check

- **What.** The verifier's `rewire.py` returns from W2.3's `CppExprEmitter.classConstruction`
  into the classes part's `construct` at its first line, above W2.3's
  `hoister.refuseTemporaryView(f.expr, "the object it is stored in")`, so W2.3's refusal of
  `Ref<View<Int32>> { value = makeList().view() }` never runs, and
  `CppHoisterTest.aTemporaryTheLoweringMakesOrStorageAPathMovesIsRefusedWithWhatToWriteInstead`
  finds 11 of its 12 `cpp.view-lifetime` refusals. Rounds 3 and 4 did not cause it: the
  failure message is byte-identical on d410f69 (the verifier's measurement), 70f3e7f and this
  round's tree, each with W2.3 9e00cfb.
- **Where.** The integration's rewire, against W2.3's `classConstruction`.
- **Reproduce.** On a trial with W2.3 9e00cfb, `./gradlew test --tests
  net.exoad.kira.cpp.exprs.CppHoisterTest`.
- **Why it can wait.** The program is refused either way: the classes part's construction now
  refuses a heap object given a view into a temporary itself (`cpp.unsupported`, `refview`,
  round 4). The test needs the rewire to place its redirect after W2.3's check line (or the
  check to move where both parts call it), which is the integrator's one-line change.

### KI-20. Where round 4's view rules refuse more than they must

- **What.** The view check (`CppClassLifetimes.Walker.viewSources` and the checks that use it)
  follows a view by what it may point into, and is conservative in these known ways, each of
  which refuses a program that is safe and never accepts one that is not:
  - A view a call returns through a handle, `this` or a global (`b.all()`, `viewOf(b)`,
    `allNums()`) may point anywhere those reach, so any write outside the body's own locals of
    a type that moves a buffer or holds an object refuses a later read of it, even a write to
    a field of an unrelated object (`v = b.all(); c.name = "x"; v.get(0)`).
  - A local `Ref` box of a view (`Ref<View<Int32>>`) may be given a view by anyone who holds
    the box, so from its declaration on any such write refuses a read of it, and a call that
    may have such an effect refuses handing it over (`lentref`'s `main`: `stash(c.items, b,
    r)` stores its view after growing `b.items`, which is safe, and both lines are refused).
  - A local container of views holds what any element points into; reading one element, or
    calling a method on the container, reads them all.
  - A lambda that captures a view is checked at each call of the local it was given to, when
    that local is not `mut`, is only ever called, and only by its own body; any other lambda
    (passed to a Kira function, stored, captured by another lambda) is taken to run after
    every later effect of the body. A call through an `Fx` value that is not such a local may
    run any lambda or function value of the program, the standard library's included.
  - Whether a view may leave a call is decided for the whole body (KI-15's list without the
    program's globals and fields): in a method that holds itself, or a function that copies a
    handle parameter, a view of that object's storage handed to a `View` parameter
    (`total(items)`) is refused when the body also builds a lambda or has an `Fx` parameter.
  - A view returned through a local's handle is refused even when another owner keeps the
    object (`x: Bag = b; return x.items.view()`); W2.5's EscapePass refuses it too (D5).
  - A heap object given a view is refused when the view is of a value no place names that
    owns a buffer, which includes an accessor's result the statement part sees as a reference
    (`Ref<View<Int32>> { value = m.unwrap().view() }` with `m: Maybe<List<Int32>>`).
  - With a `finally` anywhere (KI-12), a construction counts as a release, so a method that
    builds an object and returns a view of its own field holds itself and is refused.
- **Where.** `CppClassLifetimes`: `movesReached`, `sharesViews`, `Walker.keepViews`,
  `Walker.capture` and `runCaptured`, `lentParams` (`leaves`), `Walker.returned`,
  `temporaryViewed`.
- **Why it can wait.** Refusing is correct under the convergence policy, and each refusal
  names the local copy or the reordering that compiles. Precision here means per-field write
  sets and an escape analysis over the call graph, which W2.3's `CppEscapes` and W2.5's
  EffectsPass are; sharing them once all three are merged would narrow these.

### KI-21. What the view check does not follow, and leaves to W2.3 and W2.5

- **What.** A view a Kira callee stores through a `mut` argument (`point(mut v, xs)`,
  `keep<View<Int32>>(mut h, gl, k)`) is followed into class storage only: what the call's
  operands reach through a handle, `this`, or a place through a reference. A view of a local
  or a global stored that way and read after the caller grows that storage is W2.3's open
  decision OD-3 (a view held across a statement that grows what it views). The union that
  would follow it cannot tell a `mut` argument the callee replaces from one it keeps, and it
  refused the evalorder golden's `keep<View<Int32>>(mut h, gl, growL())`, which W2.3 makes
  safe by taking the view after `growL()` (its OD-2, ASan-clean).
- **Where.** `CppClassLifetimes.Walker.keepViews`.
- **Reproduce.** `viewmutarg` (the class form, `point(mut v, b)` storing `b.items.view()`) is
  refused; the same with a local List is accepted on W2.3 9e00cfb and is W2.3's OD-3 case.
- **Why it can wait.** It is W2.3's open language decision, recorded in its ledger with the
  options (a borrow rule in ExclusivityPass, views only of storage that never moves, a counted
  view); the classes part's own storage, reached through a handle, is followed.

## Open decisions

### OD-4. A view lent from a value parameter while the caller's storage changes

- **What.** Kira's parameters are values, and D5 lets a function return a view derived from
  one, which W2.3 lends from the caller's own storage. When the callee changes that storage
  through another handle before taking the view (`lentalias`: `firstOf(c.items, b)` with
  `c = b` and `firstOf` growing `b.items`, then returning `xs.view()`), the view shows the
  caller's storage as it is then: its size is 3, where the parameter's value had 2 elements.
  It is memory-safe (the view is taken after the growth; MSVC ASan is clean).
- **The options, for the user.**
  1. The view is of the caller's storage, as lending says (current): it shows the change.
  2. Refuse a returned view of a parameter the body's effects may reach (this refuses
     `viewlend`, `viewlend2` and `viewlend3`, which print 1 2 today).
  3. Leave it to W2.5's exclusivity rules, as a borrow of `c.items` while `b` is written.
- **Current behaviour kept.** Option 1. A read of the parameter's value after such an effect
  is refused (KI-15), so the difference shows only through the view.

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
- **W2.3.** See KI-11 and KI-17 for the refusals its lowering could replace, KI-16 for the
  receiver copy the classes part relies on, KI-18 for the range copy it relies on (9e00cfb or
  later), and KI-21 for a view of a local stored through a `mut` argument (its OD-3).
  `viewcapture2` (a lambda capturing a view of a local List that then grows) needs no routing:
  the classes part's capture check refuses it.
- **Integration.** KI-19: the rewire's redirect in `classConstruction` has to follow W2.3's
  `refuseTemporaryView` line.
- **W2.5.** KI-13: the guards are not safe without W2.5's alias and escape rules merged.
  OD-4 names W2.5's exclusivity as one way to decide a view lent while its source changes.

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
