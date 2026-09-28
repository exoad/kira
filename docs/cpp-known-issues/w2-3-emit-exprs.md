# Known issues: w2-3-emit-exprs

The C++ backend's expression, statement, closure and binding lowering: `CppExprEmitter.kt`,
`CppStmtEmitter.kt`, `CppClosureEmitter.kt`, `CppHoister.kt` (with `CppEscapes`, now only the
`Fx` escape fallback) and `CppBindingTable.kt`, the tests under
`src/test/kotlin/net/exoad/kira/cpp/exprs/` and the `evalorder` golden. Each entry says what the
issue is, where it lives, how to reproduce it, and why it is safe to leave for now. "Measured"
means built with the goldens' warning flags and `-Werror` on g++ 13.2, zig c++ (clang) and MSVC
`/W4 /WX`, and run.

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
  second-class. The old D33 snapshot of `countOf(gl, nextSize())` is gone. `minus(gl,
  setFirst())`, with `setFirst` writing `gl[0] = 50`, gives 55 as Kira does; the snapshot gave 6.
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
  checks on gcc, clang and msvc, 10 passed each; the same module under MSVC `/fsanitize=address`
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
  refusal, never a use after free. The fix is a design choice: design 30 2.1's IF could refuse
  a branch with a TEMP origin, as RANGE does, or the lowering could hold each branch's owner in
  a `std::optional` declared before the `?:`.

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
  them.

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
