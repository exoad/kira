# Known issues: w2-3-emit-exprs

The C++ backend's expression, statement, closure and binding lowering: `CppExprEmitter.kt`,
`CppStmtEmitter.kt`, `CppClosureEmitter.kt`, `CppHoister.kt` (with `CppEscapes`, now only the
`Fx` escape fallback) and `CppBindingTable.kt`, the tests under
`src/test/kotlin/net/exoad/kira/cpp/exprs/` and the `evalorder` golden. Each entry says what the
issue is, where it lives, how to reproduce it, and why it is safe to leave for now. "Measured"
means built with the goldens' warning flags and `-Werror` on g++ 13.2, zig c++ (clang) and MSVC
`/W4 /WX`, and run.

## Views are second-class, round 2: decision 4b read literally

A first round-2 fixer was killed mid-work by a machine restart. Its uncommitted edit to
`CppHoister.kt` was reviewed hunk by hunk and all of it was kept: `mayKeepAlive`, `provedPure`,
`constructionRank` and the dispatch guard in `isPureCall`. This round finished it and added the
tests. Nothing was discarded.

- **Round 1's finding, fixed: E2 missed an owner.** A fresh struct holding the only `Ref` to a
  list, used as the receiver of a method that returns a view through that `Ref`
  (`sum2(Wrap { Ref<List<Int32>> { [...] } }.items(), next())`), was emitted as
  `const kira::View<std::int32_t> t0_ = Wrap{...}.items();`. The `Wrap` died with that
  declaration. gcc printed 823559285 and other garbage where Kira gives 3601, and MSVC's ASan
  reported a heap-use-after-free. `ordered` found owners with `ownsStorage || isPointerLike`,
  and a struct whose only fields are a `Ref`, a class or a trait owns no storage by value.
  `CppHoister.mayKeepAlive` now counts every fresh value as an owner, except a scalar, an enum
  and a second-class value. It is the same test in `rangeMayDangle`'s stdlib-call case.
  - Emitted: `const Wrap t0_ = Wrap{...};`, then `const kira::View<std::int32_t> t1_ =
    t0_.items();`.
  - Measured: 3601 on gcc, clang and msvc, with 0 MSVC ASan reports (scratchpad `r2w23/p2`).
  - Pinned: `CppHoisterTest.aViewOfATemporaryIsFormedInsideTheLambdaThatHoldsItsOwner`
    (`positionalOwner`) and its compiler run.
- **The named-field form is now pinned** (round 1's minor note). `Wrap { r = Ref... }.items()`
  was safe only because `scan` ranks a named construction's field-name identifiers as READS.
  It is now an owner by `mayKeepAlive` too. It is pinned beside the positional form
  (`namedOwner`), and both check 3601 on gcc, clang and msvc.
- **Ranks no effects table can make pure (decision 4b read literally: what is not proved pure
  is impure).** The following ranks are IMPURE whatever `TypedModel.effects` or `fnEffects`
  says:
  - a virtual, trait, `Fx`-value or extern call;
  - a call of a bodiless prototype, or a construction through call syntax (`provedPure`);
  - a call handed a `MutView`, or whose callee declares a `MutView` parameter (`handsMutView`,
    new this round). A write through a `MutView` of a place is a write to that place, so
    `sub(p[0], zero(p.from(0)))` left unordered printed 8 on gcc and MSVC, where Kira gives 0;
  - a construction whose class, or a superclass, has an `initially` or `finally` block, or
    whose left-out field defaults are IMPURE (`constructionRank`).
  Pinned:
  - `CppHoisterTest.anFxCallAndACallHandedAMutViewAreImpureWhateverTheEffectsTableSays` emits
    with a table that calls every call and callee PURE. Its control, `sub(ticks, next())`, is
    left alone. The `Fx` and `MutView` calls are still spilled.
  - `impureDefault` (`ticks * 100 + Stamp { a = 1 }.k`, where the default of `k` calls
    `stamp()`) reads `ticks` first and gives 1011 on gcc, clang and msvc.
  - A class `initially`/`finally`, a virtual or trait call and an extern cannot be emitted on
    this branch (classes are W2.4's, a struct `initially` is refused by D30, FFI is W2.6's). Their
    rule is the same one line in `provedPure` and `constructionRank`.
- **Tests and golden lines that relied on the softer reading were rewritten.** Each viewed a
  shared place (a mut global, a `const&` parameter, a place through a `Ref`) with an impure call
  in the view's span. In each, the impure sibling now runs before the view is formed, or the
  viewed place is a local:
  - evalorder `sumTail`: `total(tail(xs, nextSize()))` became `total(tailAt(nextSize(), xs))`.
    Its output is unchanged, 9.
  - `CppHoisterTest`:
    - `lentFromParameter` became `tailAt(nextSize(), xs)`;
    - `viewKeptNowhere` became `countAfter(nextSize(), gl)`;
    - `arrViewBeside` became `plusView(growGls(), garr.view())`;
    - `convertedRefStorage` became `plusSize(nextSize(), tail(makeRef().value, 1))`, which now
      checks 91;
    - `elementWriteSeen` became `minus(ls, setAt(mut ls[0]))` with `ls` a local. Design 30 3.3
      allows it: the place is private, and the element write does not contain `ls`. It still
      gives 55. `minus(gl, setFirst())` was refused under either reading.
  - `CppExprRowsTest` f3: `sumV(tailV(gl, nextSize()))` became `sumV(tailAtV(nextSize(), gl))`,
    still 55.
  Kept, because the view's span holds no impure call:
  - `total(garr.from(nextSize()))`: the index is part of the view's formation, before the span;
  - `total(gbag.head(nextSize()))`;
  - `sub(gv.view().get(0), setGv())`: the view is consumed by `get` before `setGv` runs;
  - `sub(ws[0], poke(ws.from(0)))`, with `ws` a local.
- **Measured this round.**
  - `CppHoisterTest`: 38 tests, 0 failures. Its views module runs 13 checks on gcc, clang and
    msvc, 13 passed each, with 0 MSVC ASan reports (`r2w23/viewsasan.sh`).
  - The evalorder golden: 0 ASan reports, output identical to `expected.txt` (`r2w23/eoasan.sh`).
  - No other golden's `expected/` changed.
- **KI-13 is left to the rule** (below): it stays `cpp.unsupported`, and ViewPass should refuse
  it. The literal reading is OD-4.

## Views are second-class (decision 4b, design 30), round 1

The user replaced the view provenance analysis with one rule: a view (`View`, `MutView`, `CStr`,
`Unsafe`) is only an argument, a receiver or a return derived from the function's own view
parameters or receiver (scratchpad `kira-plan/30-second-class.md`). ViewPass (W2.5) checks it.
This emitter now refuses no view; it only lowers them.

- **Deleted:** `CppHoister.kt` went from 2098 lines to 967: `checkViews`,
  the `Str` default and `Maybe` copy temporaries, `storageMoves`, `Source` and `sources`, the
  kept-temporary and kept-receiver refusals, the branch-local refusal, `mutViewRoots` and
  `throughMutView`, `object CppLending` (its `reallocates` and `borrowable` moved into the
  hoister as `reallocates` and `ownsStorage`), and every part of `CppEscapes` but `fxEscapes`
  (`ViewScan`, `viewEscapes`, `dispatchStores`, `fxArgumentKeeps`, `lendsMutView`, the body
  enumeration). The code `cpp.view-lifetime` is gone, with its callers in `CppExprEmitter.kt`
  and `CppStmtEmitter.kt`.
- **E1:** a place a view is formed of is `PlaceMode.LENT`, never copied: a place the typer
  converts to a `View` (`Coercion.ToView`), or the receiver of a call whose result is
  second-class. The old D33 snapshot of `countOf(gl, nextSize())` is gone. `minus(ls,
  setAt(mut ls[0]))`, with `setAt` writing 50, gives 55 as Kira does; a snapshot gives 6. (Round
  1 measured this as `minus(gl, setFirst())`, which the rule refuses; round 2 rewrote it.)
- **E2, E3:** a call whose result is second-class is an `Operand.Chain` of its enclosing call.
  Its receiver and arguments are leaves of the spill that call opens, the owner of a view of a
  temporary is spilled into an owning typed temporary (`const kira::List<std::int32_t> t0_ =
  makeList();`, then `kira::view(t0_)`), and a chain call with an effect of its own is copied
  into `const kira::View<T> tN_` after its parts. A chain copied that way names every owner in
  its parts, even a PURE one (`kira::List<std::int32_t>{5, 6, 7}`), which a first version of
  this round missed: it would have been a temporary of the view's own declaration. No lambda
  returns a view.
- **Backstops (`cpp.internal`, never a user diagnostic once W2.5 merges):** a local, an
  assignment target, an array literal, a construction's field, or a stdlib receiver whose type
  is or holds a view; a `return` or a `for` range viewing a temporary; a `return` viewing a
  local or a parameter that is no view; an if-expression with statement branches whose value is
  a view. Each message names the `rules.view.*` code ViewPass refuses it with.
- **Measured.** `CppHoisterTest.theViewsOfTemporariesRunLeftToRightOnEveryCompiler` runs 10
  checks (13 from round 2) on gcc, clang and msvc, all passed; the same module under MSVC `/fsanitize=address`
  has 0 reports (scratchpad `sc31/viewsasan.sh`). The evalorder golden passes on gcc, clang,
  msvc and zig-aarch64, and under MSVC ASan with 0 reports and output identical to
  `expected.txt` (`sc31/eoasan.sh`).
- **Goldens rewritten to the allowed form.** `text`: `word` returned `Maybe<View<Char>>`, now
  `wordEnd: (s, w) Maybe<Size>`, the offset past the word; the driver slices
  `s.from(kira::unwrap(...))`, and `expected.txt` is unchanged. `evalorder`: `tail` takes a
  `View<Int32>`; the view field (`Win`), `Holder<View<Int32>>`, the view and `MutView` locals,
  `List<MutView>` and the `MutView` lenders are gone (the rule refuses each), and so are
  `countOf(ys, pushTo(mut ys))` and `countOf(gl, growL())` (a write that moves the viewed list
  in the view's span, design 30 3.3). New: `sub(gv.view().get(0), setGv())`, `sub(ws[0],
  poke(ws.from(0)))`, and the E2 and E3 lines, 9998 and 8998. 64 expected lines became 50.
- **The converge verdict's open issue** (a fresh `Ref`, or one in a `Maybe` or a `Mutex`,
  whose field's view a callee keeps: h1, h5, h6, h7, h9, h10) is refused by the rule at
  `rules.view.type`: each keeps the view in a `Maybe<MutView<Int32>>` global or a
  `List<MutView<T>>`, types no program may write. Here each is `cpp.internal` at the store,
  pinned in `CppHoisterTest.aViewWhereTheRuleAllowsNoneIsAnInternalErrorAndAnOrderNoLoweringGivesIsRefused`.

Still in force from the earlier rounds, and pinned in `CppHoisterTest`: D33's spills, a read of
shared state copied before an effect, a bound place located after a sibling that may move its
container, a pure read through a view as READS, a local a sibling writes through a `mut`
argument or a handed `MutView` read first, a function used as a value taking its `Fx`
parameter as a `kira::Fn`, and a `for` range that may be a reference into a temporary copied
while it lives.

## Open decisions

### OD-1. A stdlib receiver beside an effect is read before it

- **What.** `gl.get(pushed())`, where `pushed()` appends to `gl` and returns the new index,
  reads `gl` before `pushed()` runs (D33, snapshot: the hoister copies the receiver). The
  copy has no element at that index, so the program panics with `kira: index out of range`.
  The JS backend prints 7, because a JS array is a reference and R19 says a place is never
  copied.
- **Where.** `CppExprEmitter.receiverOperand` / `receiverMode`: a receiver no view is formed of
  is a `PlaceMode.SNAPSHOT`.
- **Reproduce.** `evalorder/expected.txt` lines 22-23 (`gm.get(putKey())` gives 42 and
  `gl.get(setFirst())` gives 1) pin the snapshot rule.
- **The choice.** (a) Keep the snapshot. (b) Read a receiver in place, as R19 says of a place:
  the JS answer, which would flip those two lines to 99 and 50.

### OD-4. Decision 4b's "impure": literal, or "has hidden writes"

- **What.** Design 30 3.2 read "any impure call" as "a call with hidden writes". A call that
  only prints, throws or writes its own named `mut` arguments would then not refuse a view of a
  shared place in its span. From round 2 on, the literal reading is what everything is written
  against: `effect(C) == IMPURE`. The softer reading is not implemented.
- **The choice (the user's).**
  - (a) Keep the literal reading. It is simple, and round 1 broke `HiddenWrites` five ways.
  - (b) The softer reading. It needs a provenance analysis that does not break those ways.

OD-2 (when a kept view is made) and OD-3 (a view held across a statement that grows what it
views) are closed by decision 4b: no view is kept or held, a view is formed where the call uses
it (E1), and ViewPass refuses a write that moves the place in the view's span.

## Known issues

### KI-12. Until W2.5's ViewPass merges, this branch refuses no unsafe view

- **What.** The refusals this package made are deleted, as design 30 7.2 says. On this branch
  alone a view the rule refuses compiles unless a `cpp.internal` backstop catches it. Two that
  none catches: a view formed of a place a sibling moves in the same call (`viewPlus(gl.view(),
  growL())`, `growL` appending 64 elements), which E1 makes after the growth: 2022 on gcc, clang
  and msvc, memory-safe, where the view Kira's left to right makes would point at freed
  storage; and a callee that moves what its view parameter points into before reading it
  (`growThenSum(gl.view())`, whose body grows `gl`, then sums `v`), which dangles: gcc printed
  -1715043538 where clang and msvc printed 600. ViewPass refuses the second at the call
  (design 30 3.3: `growThenSum` writes `gl`, a global, in the view's span). Probes: scratchpad
  `sc31/k1`, `sc31/k3`. A callee whose own return breaks the return rule (`return
  xs.from(at)` with `xs: List<Int32>`, `sc31/k2`: gcc printed 1009792083, clang and msvc 13300
  before this backstop) is now `cpp.internal` at that return. The verifier's h1, h5, h6, h7,
  h9 and h10 all stop at `cpp.internal` through this branch's CLI (`sc31/h*`, emit exit 1).
- **Where.** W2.5's `rules/ViewPass.kt` (design 30 2.2 and 3.3).
- **Why it can wait.** Merge order (design 30 7.0): W2.5 merges before, or with, this branch.
  Nothing is released in between.

### KI-13. A view of a temporary in an if-expression's branch that D33 orders is not lowered

- **What.** `minus(if c { makeList().view() } else { gl.view() }, next())` is allowed by the
  rule (an if-expression is transparent in an argument, and a temporary needs no write check),
  but D33 copies the if-expression into `const kira::View<T> t0_ = c ? ... : ...;`, and the
  list dies with that declaration. It is `cpp.unsupported`, and so is a spilled branch whose
  own call returns such a view out of a lambda. With nothing to order,
  `total(if c { makeList().view() } else { gl.view() })` is lowered as one full expression and
  is safe.
- **Where.** `CppHoister.ordered` (Value) and `CppHoister.spill`.
- **Reproduce.** `CppHoisterTest`'s `branchViewsATemporaryBesideAnEffect`.
- **Why it can wait.** A rare shape with a one-line rewrite (an if statement), and it is a
  refusal, never a use after free.
- **Round 2: left to the rule, not lowered.** Round 1 named two ways out. Design 30 2.1's IF
  could refuse a branch with a TEMP origin, as RANGE does. Or the lowering could hold each
  branch's owner in a `std::optional` declared before the `?:`. The second covers only a
  branch whose own operands need no spill. A branch like `makeList().from(next())` would still
  need a lambda that returns a view. So the emitter keeps `cpp.unsupported`, and ViewPass
  (W2.5) should refuse an SC if-expression with a TEMP-origin branch as `rules.view.position`.
  That also refuses `total(if c { makeList().view() } else { gl.view() })`, which is lowered
  safely here. It is a simpler rule to state.

### KI-14. The emitter reads the typer's facts, not `TypedModel.viewOrigins`

- **What.** Design 30 2.3 has W2.5 record each view's origins in `TypedModel.viewOrigins` for
  the emitter. That table does not exist on this branch. E1 decides LENT from the typer's
  `Coercion.ToView` and the call's second-class result type, which are the PLACE-origin facts,
  and `CppHoister.viewsTemporary` walks a view's receiver and view arguments for a TEMP origin
  (the E3 and E4 checks and the backstops). The walk trusts the return rule: a callee's view
  points only into its receiver and view arguments.
- **Why it can wait.** Both are the same facts `viewOrigins` holds. After the merge they can
  read it; nothing else changes.

### KI-15. `writtenBy` keeps the `MutView` source of a handed argument

- **What.** Design 30 7.2 lists `lentMutView` for deletion, reasoning that `writtenBy` sees a
  sibling's write through a `MutView` without it. It does not: `sub(p[0], zero(p.from(0)))`
  needs `p.from(0)` traced to `p` to read `p[0]` first. It stays, under the same name, as the
  named write of design 30 3.2. `throughMutView` (a `MutView` held in a variable) is deleted: no
  variable holds one.
- **Where.** `CppHoister.writes`.
- **Reproduce.** `CppHoisterTest.aLocalASiblingWritesIsReadBeforeTheWrite`; evalorder's
  `sub(ws[0], poke(ws.from(0)))` gives 0.

### KI-16. A spilled temporary's own temporaries end at its declaration (a question for W2.4)

- **What.** A D33 spill ends every C++ temporary made in a typed temporary's initializer at that
  declaration, before a later sibling runs. In `sub(len(makeObj()), next())`, a class handle
  `makeObj()` returns, whose `finally` has an effect, would be dropped before `next()` runs.
  C++ with no spill drops it at the end of the full expression. If that is also where Kira
  drops it, the spill runs the `finally` too early. Ranking such an operand IMPURE would not
  help, since the spill is what moves the drop. This is a reading of the lowering, not
  measured: classes are not lowered on this branch.
- **Where.** `CppHoister.copied`, together with W2.4's class lifetimes.
- **Why it can wait.** It needs W2.4's classes. It moves when an effect runs, and never frees
  storage early.

### KI-1. `closures` stays `emit: pending`: it needs W2.4's class lowering

- **What.** The brief's byte-identity line lists `closures`. The CLI stops at
  `closures.kira:51:11: error: cpp.unsupported: the class 'Counter' is not lowered yet`.
- **Where.** `class Counter` in `src/test/resources/cpp-golden/closures/src/lang/closures.kira`.
  Classes are W2.4's (`CppClassEmitter`).
- **Why it can wait.** It depends on another package. Its `expected/` already compiles and runs
  on gcc, clang and MSVC, and compiles for zig-aarch64 (`CppGoldenCompileTest`).

### KI-2. With no EscapePass entry, `fxEscapes` is approximated rather than taken as escaping

- **What.** The model contract (design 3.1) says an absent `TypedModel.fxEscapes` entry means
  escaping. Where W2.5 has written no entry, `CppEscapes.fxEscapes` instead makes an `Fx`
  parameter a template parameter when the body only calls it, outside any lambda, in a
  non-virtual, non-trait method. `unilidar`'s `each` needs this to be `emit: required`.
  Design 30 7.2 says the fallback goes; it stays until W2.5 merges, because without it
  `unilidar` would no longer be byte-identical on this branch.
- **Where.** `CppEscapes.fxEscapes` / `scanFx`. It is read by `CppPlacement.isNonEscapingFx`
  (W2.2's file) and `CppClosureEmitter.prepare`.
- **Reproduce.** `CppHoisterTest.anFxParameterOnlyCalledIsATemplateParameterAndOnePassedOnIsNot`.
- **Why it is safe.** A template parameter and a `kira::Fn` make the same call, and a function
  used as a value, or a parameter with a default, is always a `kira::Fn`. W2.5's entries win
  wherever present.

### KI-4. W2.4's `chain` and `classes` goldens differ from this branch by one D33 spill each

- **What.** With this branch merged, `chain` and `classes` differ from their `expected/` by one
  D33 spill each: a class field read beside a virtual call, `kira::cat(name, " says ",
  sound())`, is copied first because `sound()` may change `name`.
- **Why it can wait.** The spilled code is correct and compiles on every toolchain. The
  integrator regenerates those two `expected/` files at the merge.

### KI-5. gcc 11.4, the design's floor, is not measured on this branch

- **What.** The branch is measured on g++ 13.2, zig c++ (clang) and MSVC, and compiled for
  aarch64. gcc 11.4 (the board, `jack@bibobox`) is not.
- **Why it can wait.** This round adds no C++ language feature: typed temporaries and
  `kira::View` temporaries in IIFEs, all C++11. Someone with the board runs
  `kira/cpp/tests/goldens.sh` and `run.sh` there before the merge.

### KI-6. Four earlier commits are unsigned

- **What.** 41575c6, 71b8c4b, 42f1b7f and fbf0f61 show `%G? = N` (gpg had "No pinentry").
- **Why it can wait.** Rewriting published branch history is the merger's call.

### KI-7. Files outside this package's OWNS and TOUCHES

- **What.** Earlier rounds edited `kira/cpp/kira/rt.hxx` and `kira/cpp/tests/rt_test.cxx` (two
  `kira::list` overloads), `CppPlacement.kt`, `CppDeclEmitter.kt` and `CppEmitContext.kt`
  (W2.2's), `examples/regenerate.sh`, `examples/cpp-legs.txt` and three plumbing tests. This
  round edits `src/test/resources/typer/body/positive/views.kira` (W2.1's fixture): its
  `v: View<Char> = out.view()` becomes `m: Size = out.view().size()` with the same `@type`
  check on `out.view()`, since `CppTyperFixturesTest` emits it and a view local is now
  `cpp.internal`. It also rewrites the `text` golden's source, driver and `expected/`, which
  design 30 section 8 gives this package.
- **Why it can wait.** Each change is small and covered by tests. The merger routes them.

### KI-8. Until EffectsPass lands, a container read beside any user call is copied

- **What.** EffectsPass (W2.5) has not merged, so every user call is impure. A `Str`, struct or
  container read beside a user call, that no view is formed of, is deep-copied (D33), and every
  user call returning a view is copied into a `kira::View` temporary after its parts (E3).
- **Why it can wait.** A cost, not a correctness issue. EffectsPass's `PURE` entries remove
  them, except where the hoister ranks a call IMPURE whatever the table says (round 2 above):
  a virtual, trait, `Fx`, extern or bodiless call, a call handed a `MutView`, and a
  construction with an `initially`, a `finally` or an impure default.

### KI-10. A `for` range is copied unless it is proved not to dangle

- **What.** `CppHoister.rangeMayDangle` copies a range that may be a reference into a
  temporary (`kira::List<std::int32_t>(kira::at(makeLists(), 0))`, `makeRef()->value`) in its
  own range expression. It copies unless the range is a place found through a variable or a
  value C++ returns by value, including a stdlib call given a temporary whose binding returns a
  new container. A view range is never copied: its storage is a view parameter's or a
  literal's (design 30 E5).
- **Why it can wait.** A copy of a prvalue is elided, so the cost falls only on a real
  reference, and it is never a refusal.

### KI-11. The CLI cannot reach R6's skipped middle default, so only the rows test covers it

- **What.** R6 fills a skipped middle default in at the call (`three(1, c = 9)` becomes
  `three(1, 2, 9)`). From source, the C/JS semantic analyzer's named-argument check refuses
  `f(1, c = 5)` before the typer binds it.
- **Where.** `KiraSemanticAnalyzer.checkNamedArguments` and `core/NamedArguments.bind`
  (frontend code). The emitter's side is `Slot.Filled`.
- **Why it can wait.** Nothing is emitted wrongly. `CppExprRowsTest`'s `r6` row checks
  `r6::skipMiddle() == 129` on gcc, clang, msvc and zig-aarch64.
