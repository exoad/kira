# Known issues — w2-5-rules

One entry per known minor issue: what, where, reproduction, and why it is safe to defer.

Rounds 1-4 of convergence patched EscapePass's view provenance analysis; it never converged.
Decision 4b (views are second-class, design 30-second-class) replaced it in the second-class
round: `rules/ViewPass.kt` (the ten `rules.view.*` codes, `TypedModel.viewOrigins`). Deleted:
EscapePass's view half (`Lends`, `Provenance`, `viewFields`, the view parts of `Flows`) and its
three codes `rules.escape.view-return`, `view-store`, `view-field`; `TypedModel.viewEscapes`;
`RuleSupport`'s `mayBorrow`, `holdsClosure`, `viewInside`, `classPublishesThis` and
`ViewAliases`; `TraitSymbol.thisEscapes` (round 4's, read only by `classPublishesThis`); the
typer's `types.view.temporary` (a temporary lives to the end of its full-expression). Every
significant issue of the last converge verdict is refused by the rule, each with a test in
`ViewPassTest`: Q1, Q1d, Q9, Q2, Q3, Q3b, Q7b, Q7c, Q11 at `rules.view.return`; Q1e, and Q4,
Q4c, Q4d, Q5, Q5b, Q5c, Q6, Q6b, Q6c as written (a view local) at `rules.view.local`, and with a
view parameter instead at `rules.view.capture`; Q7, Q4b, Q10 at `rules.view.type`.

**Round 2 (second-class): decision 4b is read literally.** Round 1's verifier broke 3.2's
"hidden writes" reading five ways (a `finally` run by a drop, a `MutView` formed inside a
callee, an `Fx` nested in an extern argument, an extern's `Fx` arguments, construction order),
all of them provenance analysis again. `rules.view.write` now refuses a view of a shared place
or a `mut` global when any call or node in its span is `Effect.IMPURE` (30-second-class 3.3
with 3.2's one-line swap); a private place is still refused only by a named write that contains
it. EffectsPass is conservative on exactly the shapes that broke: an extern or bodiless
prototype, a call through an `Fx`, trait or virtual method, a construction with an `initially`
or an impure field default, anything that may drop the last handle of an object whose `finally`
is IMPURE (`Drops`: a by-value parameter, a local, a construction, a call result, an array
literal or a closure whose type may hold one; a trait, a type parameter, an `Fx` or a handle
may be any class), and a callee that writes through a `MutView` it is lent. Deleted:
`HiddenWrites`' view summary (`Hidden`, `summary`, `construction`, `drops`, `fnHidden`,
`finallyHidden`, `callHidden`, `bodyHidden`, `magicHidden`, `fxArgument`, `initHidden`,
`summarise`) and its per-program memo; `NamedWrite` and `named` moved into ViewPass;
`walkOutsideLambdas` is `AstScan.outsideLambdas`. `HiddenWrites` keeps only the place lists
ExclusivityPass reads. Tests: `ViewPassTest.theShapesRound1BrokeAreImpureSoTheViewBesideThemIsRefused`
(P2a, P2d, P2e, P2f, P2g, P12, P12b, P12d, P13a, P13b, P13d and two new drop shapes, with a
pure-`finally` control), `anyImpureCallInTheSpanOfASharedOrGlobalViewIsAWrite`,
`theProbesW24AndW23LeftToThisPassAreRefused` (w2-4's S1-S3 probes and w2-3's h1, h5, h6, h7,
h9, h10, k3, each whole), `EffectsPassTest.whatDropsTheLastHandleOfAnObjectWithAnImpureFinallyIsImpure`
and `externsPrototypesDispatchInitializersAndMutViewWritesAreImpure`.

**Round 3 (40-round3, with the user's OQ-1 READ FIRST).** One rule per group, each written once:

- **R-A, a lent result is a place.** `rules/LentPlaces.kt`, the pre-pass that runs first, fills
  `TypedModel.lentPlaces`; every reader asks `TypedModel.readPlace(e)` (lent, else assignable).
  `xs.get(i)`, `m.unwrap()`, `r.unwrapErr()` (`Rules.ACCESSORS`) are `xs[i]`, `m.value`, `r.error`,
  through a lender too (`xs.view().get(0)` is `xs[0]`, `mv.from(0).get(0)` is `mv[..]`), and so is
  an index or field read off one (`ks.get(0).child`). ViewPass's `storage`/`formed`, ExclusivityPass
  (every operand, loop, D37), EffectsPass's READS and MutabilityPass read it. A lent result on a
  second-class receiver that is no place (`mk().view().get(0)`, `pick(a.view(), b.view()).get(0)`)
  takes the receiver's origins plus the step, recorded in `viewOrigins` for the first-class result
  too. Closes w2-5 #0 (q11, q13a, q13b, q14a), w2-6 #2 (c2, c2b, c2c), w2-6 #3 (c4, c4b) and r1
  w2-5 minor #2 for the built-in containers (`xs.view()[0]` is a place): `LentPlacesTest`.
- **R-C, which `Fx` a call may run.** `CallReach` in `RuleSupport.kt`: `mayHoldFx`, `givenFx`,
  `bodyUnknown`, `mayRunAnything`. EffectsPass asks `givenFx`; W2.4 and W2.6 read the rest
  (`SharedPredicatesTest.mayHoldFxOverEveryKindOfType`, `whatACallMayRun...`).
- **R-G, C++ supplies the body.** `FnSymbol.suppliedByCpp` in `analysis/types/ExternFacts.kt`;
  ViewPass's `isExtern` is it, EffectsPass makes its calls IMPURE, `CallReach.bodyUnknown` reads it
  (`SharedPredicatesTest.theReadersOfSuppliedByCppAgree`). A bodiless private prototype is no
  longer an extern here (the design's R-G: `pub` only).
- **F1, `rules.exclusivity.order` narrowed** (section 3.2). Allowed: a by-value argument, an operator
  operand or a compound target of an immutable value reading the written place (D33 copies it;
  OQ-1): evalorder's 143, 231, 233, 236, 259. Refused: a reference receiver a sibling may rebind; a
  `mut` place a sibling also writes, and two named writes of one place; interim until W2.9.8, the
  receiver of a container or mutable value beside a named or hidden write of its place, implicit
  `this` included (192, 195, 222, 389, and 393, which round 2 missed). Measured with this CLI on
  W2.3's evalorder golden: refused at exactly 192, 195, 222, 389, 393.
- **F2**: a second-class argument is never a named write (`ViewPass.named`). **F3**: an
  if-expression with a TEMP origin in a branch is `rules.view.position`.
- Round 2's minors: `Drops` keys its path by the substituted type (PairAB/PairBA); the capture
  message names an `Fx`-value callee as written; the loop rule counts a write through another handle
  of the same class as a write of the iterated field (q6). Each has a test. (Its q7 clause, a node that
  may run an IMPURE `finally`, went in round 4: the emitter iterates a copy instead.)
- **Section 4's own four**: CppDeclFixesTest's `helper` is `@_const`, `setX` takes `mut x`;
  TyperBodyCorpusTest allows NamingPass warnings; RulesCorpusTest exempts a golden whose
  `case.yaml` says `pins: spills`. The full suite on this branch: 1059 tests, 0 failures.

**Round 4 (50-round4, COPY BY DEFAULT: the user's choice after round 3). Rules tests 185, full suite
1070, 0 failures each; `regenerate.sh --check` current.** The lowering copies every
first-class value unless a whitelisted shape proves the lend safe; this package decides only what
cannot be copied (views, W4; `mut` places, W7) and answers the one question W2.3's policy asks of a
callee (CONFINED). Section 6.3's five items:

- **CONFINED** (2.3). `EffectsPass`'s second fixpoint, `Confinement`, fills `TypedModel.fnConfined`,
  `lambdaConfined` and `defaultConfined` and sets `dropsImpureFinally`; `CallReach.confined(rc, model,
  receiverType)` answers 2.3's table from them (and `CallReach.construction` a `C { ... }`), and no
  other file answers it (`SharedPredicatesTest.noOtherFileDefinesWhetherACallMayRunCode`). A body is
  CONFINED when every node outside its lambdas is a read, a throw, a write of its own storage (its
  locals, its `mut` parameters, the elements of its `MutView` parameters, a value `mut fx`'s `this`,
  through value steps), a CONFINED call whose written operands are its own, a call of its own `Fx`
  parameter (not a `mut` one, which it may have reassigned: `swapRun`), or a CONFINED construction,
  and nothing may drop the last handle of an object whose `finally` is IMPURE. `RUNS_OPERATORS` and
  `holdsUserType` moved from EffectsPass into `CallReach`.
  Tests: `SharedPredicatesTest.confinedOverOneCalleeOfEachKindBothAnswers` (16 callees, both answers
  where the kind has two), `aConstructionOrADropThatMayRunCodeIsNotConfined`.
- **Rule M**, `rules.exclusivity.mut` (2.7), moved in from W2.4's `mutArgRefusals`: a `mut` argument
  or `mut fx` value receiver must be PRIVATE, STABLE, or passed to a CONFINED call with no other
  written operand that may hold it either way (`Rules.mayHold`, moved from ViewPass as W2.6 did).
  Tests: `ruleMAcceptsAPrivateOrStableMutPlaceWhateverTheCalleeRuns`,
  `ruleMAcceptsAGlobalPassedToAConfinedCallAndRefusesItBesideCodeItCannotSee`,
  `ruleMRefusesW24sExternShapesAndAnExternHandedAnyLambda` (externmut, externarr as a List, a class
  field and an `Fx` value; round 5 adds the lambda that writes nothing),
  `ruleMRefusesTwoMutOperandsOfAConfinedCallWhenOneMayHoldTheOther`.
- **Deleted**: `rules.exclusivity.alias` (`aliasing`, `Passing`, `passing`), order clause 1
  (`Role.LIFETIME`, `lifetimeOf`, the lifetime branch of the touch conflict), and `FinallyRuns` with the
  loop rule's finally clause. The seven tests that asserted them now assert acceptance and, where the
  copy depends on it, that the callee is not CONFINED (the alias rows of w2-4 #0 included:
  `theAliasRowsOfW24sVerifierAreAcceptedAndTheirCalleesAreNotConfined`). Kira's value on these
  programs is W2.3's to run: its policy copies what this package says it may not lend.
- **O1, O2** (2.4), closing w2-5 #0. O2: `LentPlaces.base` keeps a receiver-less root through a
  reference step (`pick(h).items`) as the base of a lent result, so `pick(h).items.get(0)` is the place
  `pick(h).items[0]` records. O1: ViewPass names the storage behind a reference-typed expression that
  is no place (`pick(h)`, `h.me()`, `me()`, `pickM(h).unwrap()`) as `ViewOrigin.Referent`, shared
  like a `Stored` place through a handle, never TEMP. Every row of round 3's a2/a2i, a3b/a3c, mx
  (callref, pickM) and tw is refused in both spellings (14 rows in
  `ViewPassTest.storageBehindAReferenceTypedExpressionThatIsNoPlaceIsSharedInBothSpellings`; the
  matrix: 64 of 64 pairs agree, the 4 call rows round 3 accepted are refused). A value temporary
  (`mk().items` of a struct) stays TEMP (`aLentResultOnAValueTemporaryIsStillATemporary`).
- **EscapePass reads `opCalls`** (w2-5 minor #1): `Keeper {} + f` hands `f` to `@op_add`'s parameter
  (`anFxHandedOnAsAnOperatorsOperandEscapesWhenTheOverloadKeepsIt`, probe esc2 now `rules.view.capture`).

Where this branch reads the design differently, each for a reason measured on a probe:

- **STABLE has two more anchors.** 2.7 lists a PRIVATE root, a class `this` and the object behind a
  PRIVATE handle slot. A `mut` parameter and a value `mut fx`'s `this` are anchors too (`fwd(mut x)`
  forwarding to a callee that runs hooks): rule M kept that storage still at the caller for the whole
  call (PRIVATE, STABLE or CONFINED there, and a CONFINED callee's calls are CONFINED), so it moves
  only through the parameter itself. Without it, every helper that forwards its `mut` parameter to a
  callee that is not CONFINED is refused. A trait's `this` in a default body is one as well (a class
  object held for the call, or a value `this` its caller kept still): W2.4's
  `aStructTakesATraitsDefaultBodyAsItsOwnMember` and CppClassCompileTest's shapes call a `mut fx` on it.
- Round 4's three other readings (row 1 over the declared parameter type only, an extern handed a
  CONFINED lambda is CONFINED, the drop test on every stdlib binding) were wrong: round 4's verifier
  measured a heap-use-after-free through the first two and an over-refusal from the third. Round 5
  replaces them with 2.3 as written (below).

**Round 5 (narrow: round 4's w2-5 #0-#3). Rules tests 189, full suite 1074, 0 failures each;
`regenerate.sh --check` current.** `CallReach.confined` now matches 2.3's table:

- **Row 1 charges every `Fx` argument**, one given for a type parameter included (`hooks.add(f)`,
  `pass<Fx<...>>(callIt, h)`), and a call of an `Fx` value also charges an argument of a type parameter
  (or of an unknown type), which may be an `Fx` (`mayHoldFx`). So a generic body that hands its `T`
  to its own `Fx` parameter (`pass<T>`'s `f(x)`, `Box<T>.run`'s `f(this.v)`) is not CONFINED: it runs
  whatever its caller gave for that `T`, and its caller's charge never saw it. Closes #0.
- **The extern row is `!mayRunAnything`** (what C++ supplies: an extern, a `pub` prototype, an
  `@_opaque` method): any argument that may hold an `Fx`, a lambda literal included, makes the call not
  CONFINED, since C++ may call the lambda with an `Fx` it kept (round 4's e1). The private `supplied`
  is gone. Closes #2.
- **Rule M follows** (#1): `setAfter(mut gls[0])` and `setAfter(mut gh.value.name)` are refused beside
  every one of those routes.
- **The drop test applies only to a binding that replaces or removes** (`CallReach.dropsHeld`: a
  `mut fx` binding or `MutView.set` not in `ADDS_ONLY`, or a free binding); `add`, `addAll`, `Set.add`,
  `push`, `enqueue`, `pushFront`, `pushBack` keep a copy and drop nothing, and a method that only reads
  drops nothing. atk/r1's `gres.add(Res { n = 1 })` and `gfx.add(fx () Void { trace(7) })` are
  accepted again. Closes #3.

Tests (the verifier's probes): `ExclusivityPassTest.aGenericHandingItsTToItsFxAndAnExternHandedALambdaAreNotConfinedSoEveryUseIsCopied`
(gen `generic`, `genlambda`, `genparam`, `box`, `extern` `_val`: `route`, `lenAfter` and `sumAfter` not
CONFINED at any of the four uses), `ruleMRefusesAGlobalElementAndAGlobalHandlesFieldBesideEachRoute`
(the same routes' `_m1`, `_m2`: 10 programs, one `rules.exclusivity.mut` each),
`ruleMAcceptsAnAddToAGlobalThatDropsNothingAndRefusesWhatReplacesOrRemoves` (atk/r1 whole, with
`set`, `removeAt`, `clear` and an `add` of a lambda that writes a global refused),
`SharedPredicatesTest.anFxGivenForATypeParameterIsChargedAndAGenericThatRunsItsTIsNotConfined`; and
`confinedOverOneCalleeOfEachKindBothAnswers` flips `extFx` of a CONFINED lambda to false.

Measured on b82cdf0 plus this diff (a scratch clone; the trial untouched), gcc 13.2, zig c++ (clang
20), MSVC /O1 and MSVC /fsanitize=address:

- **gen.py over all 17 routes, 51 projects**: every route copies or refuses. 16 routes copy all four
  uses and print 47/6/47/47 (`initially` also prints the route's own `trace(x.n)`, 0, between them);
  `none` lends all four (the one CONFINED route) and is right; rule M refuses both `_m1` and `_m2` of
  all 16 writer routes and accepts `none`'s, which prints the written value. 0 ASan reports.
- **atk**: e1 47/6, r1 1/1, s2, c2 (47 x3; MSVC /WX still fails on the parser's `as Int32 +`), o1 all
  right with 0 ASan reports. g2 is refused (rule M at `setAfter(mut gl[0])`, and at `hooks.add`).
- **The 7.1 replay, 830 projects**: 508 accepted, 322 refused (round 4: 510, 320). No project went
  from refused to accepted. Every must-stay-refused row keeps its code. Changed: r4d_p3 and r4d_p5 are
  newly refused (below); w25r2 r1_p5a and r1_p5b gain a rule M refusal at `KEPT.add` (already refused);
  r1_p2e loses its `GFS.add` refusal (#3; `GFS.clear` stays refused); fincall (both copies) loses its
  rule M refusal at `yard.dead.add(this)`, and stays refused by D37's `rules.exclusivity.receiver`.

What 2.3's row 1, read literally, refuses (a consequence of the design, not a hole): a `mut` global
container handed an `Fx` that is not CONFINED (`hooks.add(fx () Void { gs = "new" })`, `KEPT.add(k)`)
is refused by rule M, because `add` is handed an `Fx` value. The refusal is safe but not needed for
safety, since a binding only keeps what it is given; the programmer's fix is the one the message
gives (`mut hs = hooks`, `hs.add(...)`, `hooks = hs`). It refuses r4d/p3 and r4d/p5 (7.1 lists both as
round-4 probes to run), atk g1, g4 and g5 at their `hooks.add` in `main`, and, on the trial, W2.3's
`CppCopyPolicyTest` program (`gfs.add`, `hooks.add` in `arm`: 4 test cases). Charging such an `Fx` only
where the callee can run it (row 1 without the `t is KType.Fn` term; the `T` charge at an `Fx`-value
call and the extern row are what close #0-#2) was measured too: all 51 generated cases right, g1, g4,
g5 47/6, p3 47/6/5, p5 `old-name...:1`, 0 ASan reports, and `CppCopyPolicyTest` green. The choice is the
design's to make; this branch follows the task's reading of row 1.

On the trial, fix 2 also flips W2.4's `CppClassCopyTest.aMutArgumentAnExternsHarmlessCallbackCannotReachIsAccepted`
(an extern handed a lambda that writes nothing), which asserted round 4's reading: now refused by rule M,
as 2.3's extern row requires.

## Round 3: where this branch reads the design differently, and why

- **Clause 3 covers operators too.** 3.2 lists "(not an operator, not `[]`)". DECISIONS 2 makes `a
  == b` the call `a.@_op_eq_(b)` and `a += b` the call `a = a.@_op_add_(b)`, and OQ-1 limits "read
  first" to immutable values, so a container or mutable value on the left of an operator (`gl ==
  resetL()`, `GA == resetA()`) is Q4's like a method receiver, and refused until W2.9.8. `[]` is
  already Q4 (`kira::at` reads when it runs). No golden, resource or test of W2.3, W2.4 or W2.6
  has the shape (measured: their suites with this branch merged, below).
  `ExclusivityPassTest.aContainerOrMutableValueAsAnOperatorsReceiverIsHeldToo`.
- **R-A rule 2's PARAM origin becomes SHARED.** 2.2 keeps a PARAM origin unchanged; but the callee
  may itself write through a `MutView` parameter (`total(mv.from(0).get(0).view(), clobber(mv))`
  frees the viewed list), so an element inside a view parameter is shared storage, as the index
  spelling `mv[0]` always was. Rule 1 comes first anyway: `mv.get(0)` is the place `mv[..]`.
- **A `MutView` lent in a later operand is a write for ViewPass.** F1 drops the order clause that
  refused q10c (`total(xs[0].view(), clobber(xs.view()))`, a heap-use-after-free of a private
  local); ViewPass now counts a `MutView` lent in a later operand (`CallOperand` "a MutView of", or a
  `MutView` receiver a mutator writes) as a named write of what it is lent from. Within the
  consuming call itself it is D37's (`rules.exclusivity.argument`).
- **A `mut fx` on a lent result is refused** (`rules.mutability.method`). 2's "refused exactly as
  today" held for `mut xs.get(0)` (the typer) but not for `ys.get(0).add(1)`, which MutabilityPass
  took for a temporary while `kira::at` hands back a reference into `ys` (C++ grew `ys[0]`, or failed
  to compile on a const one).
- **`mayHoldFx(View<Fx<...>>)` is true.** The design lists second-class types as false; a
  `View<T>` hands the callee the `T`s it views, so it follows its element like a container.
- **F2 is tested white-box.** This branch's typer does not yet let a view reach a `mut Unsafe<T>`
  (W2.6's CallResolver does, keeping `byRef`); `ViewPassTest.aSecondClassArgumentIsNeverANamedWrite`
  sets `byRef` on the binding as W2.6 does and reruns the passes. The end-to-end test is W2.6's.

## Round 4's sweep of the other three branches

Measured in scratch clones (`scratchpad/r4w25/clone-*`): each branch head with this round's diff
applied (the mayHold hunk W2.6 made the same way resolved to this branch's), full suite:

- **W2.3 (09747ae)**: 1190 tests, 0 failures.
- **W2.6 (d190ada)**: 1191 tests, 1 failure:
  `CppExternEmitterTest.aByReferenceArgumentThatIsAPlaceIsCopiedWhenTheCallMayWriteIt` asserts the
  deleted `rules.exclusivity.alias` on the direct lambda (50-round4 6.5 flips it to accepted and
  copied: W2.6's).
- **W2.4 (965b682)**: 1220 tests, 5 failures, all `CppClassLifetimesTest`, all W2.4's to delete or move
  (6.6): four `mutArgRefusals` shapes (externarr as
  `aMutArgumentAnExternMayFreeThroughAnFxAnArgumentHoldsIsRefused`, externmut, the `Fx` value,
  `aDispatchedCallGivenAHeldFxMayRunAnything`) now refused first by rule M at the same line, and
  `aTraitReceiverAnObjectHoldsThatTheCallbackMayReplaceIsRefused`, which expects the deleted alias
  rule.
- **The round-3 trial (c10000b)**: 1464 tests, 6 failures: exactly the five W2.4 ones and the W2.6 one.
  CppClassCompileTest and CppClassShapeTest pass (a trait's `this` is STABLE).

Probes (`r4w25/sweep.py`, 580 projects: w25r3ver/p's 236, the W2.4 verifier's 231, w26r3's 30,
sc3v23's 79, r4d's 4), emitted with the round-3 trial's CLI and with the trial plus this round's rules,
every error line compared:

- **Newly refused**: 27 `rules.view.write` lines, all w2-5 #0's family (a1, a1b, a2, a3, a3b, a5,
  mx and mxa's callref and pickM call rows, tw's two `me()` rows); 2 `rules.view.capture` (esc2, esc3:
  EscapePass reads `opCalls`); 2 `rules.exclusivity.mut` that nothing refused before (r1-p2e's
  `GFS.add`, `GFS.clear`: a Dropper's IMPURE `finally`). 61 more `rules.exclusivity.mut` lines are W2.4's
  `mutArgRefusals` refusals moved to rule M at the same line (the rc_*_b_* matrix, s_mutparam), and 3
  W2.4 `cpp.unsupported` lines surface where a deleted typer refusal used to stop first (tw1 44, q7 24
  and 37: W2.4's lifetimes, deleted by W2.4 in this round).
- **Newly accepted**: 30 `rules.exclusivity.alias` lines (sc3v23 n2, rp1, rp1f2, tw1, al, b1ki, r2v6
  a1, b1, b1c, b1k, b1u, b9b, b9c, q5), 1 order clause-1 line (f1r 70, `k.plus(rebind(mut k))`) and 2
  loop lines (q7, the `finally` clause). Each is a copy W2.3's policy must make; r2v6 b1k is 40-round3
  6.2's Kira-function control again.
- r4d p1, p3, p4, p5 are accepted by both, as the design expects (their fixes are W2.3's copies).

## R-C's limit: a callback C++ stored earlier

**What.** `mayRunAnything` answers from the call's own arguments and receiver. An extern that kept an
`Fx` from an earlier call (a C++-side callback registry: `register(fx() { GS = ... })`, then
`pump()`) runs it from a later call given nothing that may hold one, which R-C calls "only its named
writes". EscapePass already makes an `Fx` passed to an extern escaping.

**Why it is safe to defer.** It needs a C++ library that retains Kira closures across calls; none of
bibo's externs does, and the extern contract (5.4) does not yet say whether one may. The fix is a
contract line or an `@_retains` marker on the extern that `bodyUnknown` reads, W2.6's to decide.

## Issue 8 partly covered: three ways a plain `fx` can still write its own state

**What.** The spec rule "methods that modify instance state must be marked `mut`"
(MutabilityPass) only catches a plain `fx` that assigns a field or calls its own `mut fx`
directly on `this`. Three more shapes write the object's state through a plain `fx` and type
clean:

1. Passing its own field as a `mut` argument to a helper that writes it:
   `pub fx a: () Void { bumpI(mut count) }` on a class with `pub mut count: Int32`.
2. Lending a `MutView` of its own `Arr`/buffer field to a callee that writes through it:
   `writeU32Le(buf.from(0 as Size), 0 as Size, 5 as UInt32)`. (Round 5's third shape, a
   `MutView` local written through, is `rules.view.local` now.)
3. A trait's plain default `fx` calling the trait's own `mut fx` on itself:
   `pub trait T { pub mut fx step: () Void; pub fx go: () Void { step() } }`.

**Where.** `MutabilityPass.kt` (`mutMethod`); `Body.thisMutable` (`StmtChecker.kt:440`)
already treats a whole class body as writable, which is looser than the rule pass and is what
makes the C++ side self-consistent.

**Why it is safe to defer.** A false negative in a lint, not a miscompile: W2.4's
`writesReceiver` drops `const` from the emitted method whenever it sees a `mut` argument or a
`MutView` lend that reaches the receiver's storage, so the C++ compiles and runs correctly
either way. Routed to W2.4/W2.1 per the round-5 verdict; unchanged.

## ExclusivityPass's place lists miss three shapes

**What.** `HiddenWrites.of` (the place lists ExclusivityPass reads) misses: a callee that copies a
class reference into a local and writes through the local (`t: Sc = sc; t.k = K {}` is not
charged to `sc`, so `sc.k.plus(resetAlias(sc))` types clean); an `@op_*` overload's body (a
sibling write hidden behind a user `a + b`); and a lambda reached through a field, a global or an
`Fx` parameter (`for x in LOG { h.f() }` with `h.f` adding to `LOG`). Round 3 closed the loop
rule's share of the first shape for two handles of one class (`g.items` written while `h.items` is
iterated, q6). Round 4 deleted the `finally` clause (q7). The loop rule treats a receiver-less root
through a handle (`for x in pick(h).items { g.reset() }`) as a temporary, where it refuses the same
body over `h.items`; clause 3 likewise sees no write of `pick(h).items` through another handle.

**Where.** `HiddenWrites.of` / `lambdasRun` in `RuleSupport.kt`.

**Why it is safe to defer.** Under copy by default none of these is a memory error: the view rule
reads EffectsPass, where each is IMPURE (a write through a local copy of a class reference is not a
local write, an operator overload has its body's effect, a call through a field, a global or an `Fx`
value is `FN_VALUE`); a loop whose body is IMPURE iterates a copy unless its range is PRIVATE (W6);
and a receiver handle is copied in Kira's order (50-round4 1.6). What they miss is D37 as a language
rule: a loop that changes what it iterates out of sight iterates the copy, which is Kira's value
semantics, and clause 3 goes when W2.9.8 lowers Q4.

## `rules.view.store` fires only beside a typer mismatch today

**What.** No implicit conversion of this typer takes a view into `Any` or a trait value
(`View<T>: Iterable<T>`), so each program the store rule refuses (`count(v)` into an
`Iterable<Char>` parameter, `K { it = v }` into a `Maybe<Iterable<Char>>` field,
`bitCast<UInt64>(v)` into `Any`) also carries the typer's `types.assign.mismatch` or
`types.bitcast.type` at the same node.

**Where.** `ViewPass.position`, `Ctx.Arg`/`Ctx.Kept`/`Ctx.Return`;
`ViewPassTest.aViewIsNeverPassedWhereAFirstClassValueIsKept`.

**Why it is safe to defer.** Two errors on one refused program, never a miscompile. The rule
is there for W2.9's boxing (DECISIONS Q1 A: a value used as a trait value boxes a copy), where
the mismatch goes away and the store refusal is the one that stays.

## Consequences of the rule a programmer will meet (not bugs)

- Decision 4b read literally: a view of a shared place (a field of a class, a `const&` or `mut`
  parameter, anything behind a `Ref` or handle) or of a `mut` global is refused beside ANY
  impure call in its span, the consumer included, whatever that call writes:
  `total(this.buf.view(), log.note())` with `note` bumping an `Int32`, `readHeader(buf.view(),
  mut h)` with `buf` a `List` parameter (`readHeader` writes its `mut` parameter), a `trace` of
  a `+` whose other operand does, `fill(this.raw.from(0), v)` (`fill` writes through the
  `MutView`). The remedy: run the impure call in its own statement first, take a view
  parameter, or view a local. A private place (a local, a by-value parameter) is untouched.
- A view from a virtual or trait method on a class receiver (`total(h.all())`, `h: Viewer`) is
  `rules.view.write`: the call forms the view of its receiver and is in its own span (3.1), and a
  dynamic call is IMPURE. The remedy is a view parameter, or a final method.
- A view of a shared or global place passed to a virtual, trait or `Fx`-value call
  (`sink.write(buf.view())` with `buf` a `List` parameter) is `rules.view.write` for the same
  reason; a view parameter (`sink.write(buf)` with `buf: View<UInt8>`) or a local's view passes.
- `f(xs.view(), mut xs)` and `sb.add(sb.view())` are refused by both passes:
  `rules.view.write` at the view, `rules.exclusivity.argument`/`receiver` at the operand.
- Rule M (round 4): a `mut` argument that is a global, an element of a container that is not the
  caller's own, or storage behind a second handle (`h.item.count`) is refused when the callee is not
  CONFINED: it runs an `Fx` it was not handed as a CONFINED literal, a dispatched method, an extern
  given what may hold an `Fx`, a hook from a global list, or a drop that may run an IMPURE
  `finally`. `bumpAfter(mut G)` with `bumpAfter` running hooks is refused, `inc(mut G)` is not. The
  remedy is the message's: copy into a local, pass it `mut`, store it back.

## Conservative approximations in EffectsPass

**What.** (1) Anything that may drop a handle of a class whose `finally` is IMPURE is IMPURE,
even where that handle cannot be the last: every by-value parameter, local, construction, call
result, array literal and closure whose type may hold one, counting a trait, a type parameter,
an `Fx`, a stdlib handle and a generic superclass's fields as any class (only when some class
has an IMPURE `finally`). (2) Any `initially` in a class's ancestry makes its construction
IMPURE. (3) A pure binding that compares or hashes elements (`contains`, `Map.get`,
`containsKey`, `containsValue`) over a user class, trait or type parameter is IMPURE, and so is
a pure binding given an `Fx`. (4) A write through any handle of a class is, for the loop rule, a
write of the same field reached through any other handle of it (`mayBeSame`). (5) Round 4's
CONFINED: a stdlib binding whose receiver or argument may drop the last handle of an object with an
IMPURE `finally` is not CONFINED, pure or not (`List.add` included), and neither is any method of a
system handle (`Thread`, `Mutex`, `Suite`, a socket, `Any`, `Exception`); a Kira body is not when it
writes through any handle, its own locals' included (`h.n = 1` with `h` a local).

**Where.** `Effects` and `Drops` in `EffectsPass.kt`.

**Why it is safe to defer.** Each over-approximates, so it can only refuse a view or a `mut`
argument that is safe, or make the C++ emitter spill or copy an operand it need not (D33, W3), never
accept one that is not safe. No golden or example has a class with an IMPURE `finally`.

## `return x as Int32 - 1` ends the return at `as` (round 3's minor, not this package's)

**What.** The parser ends the expression after `as T`: `return GL.size() as Int32 - 1` is emitted as
`return static_cast<std::int32_t>(GL.size()); static_cast<void>(-1);`, a silent wrong value (round 3's
e1 row 7: 2 for Kira's 1). ReturnPathPass does not flag the unreachable statement after the return.

**Where.** The frontend parser (W2.9.1's); `ReturnPathPass` could report a statement after a `return`.

**Why it is safe to defer.** It is the parser's (W2.9.1), not a rule's; a warning here would only
surface it. Unmeasured on this branch: whether `(x as Int32) - 1` parses as meant.

## Round 1's minor findings

- 1.3 view-safety ignored dispatch: fixed. A generic call dispatched at run time is view-safe
  only when the method and every override with a body are
  (`ViewPassTest.aDispatchedGenericIsViewSafeOnlyWhenEveryOverrideIs`: P5a, P5b, a control).
- An `@_opaque` class's bodiless methods returning a pointer: fixed, `rules.view.extern` at the
  declaration (`anExternNeverReturnsAPointer`).
- `callOf` has no `ArrayIndexExpr` case: closed for the built-in containers by R-A (round 3):
  `xs.view()[0]` is the place `xs[0]` (`readPlace`), and `a[i]` and `a.get(i)` get one verdict.
  Still open for a user class's `[]` (W2.9's member `@_op_get_`, not parseable yet): `g[i]` that
  returns a view of its receiver will need the receiver origin and its call in the span, as `b.all()`
  has.
- Design text (a): `use(this.buf.view(), log.note())` is refused now, as 3.3 says. (b) A method
  calling its own non-escaping `Fx` parameter with a view of `this.data` (`f(data.view())`) is
  refused (an `Fx`-value call is IMPURE); 30-second-class 3.2's "nothing here" should say so,
  since the caller's span never contains that view.
- For W2.3: a D33/E3 spill of a construction inside a PLACE view's span ends the temporary at
  the `;`. A class whose `finally` is IMPURE makes that construction IMPURE here, so the view is
  refused; a pure `finally` running early frees nothing a view can point into.

## Closed in round 3: the 3 tests outside OWNS that failed on this branch

`CppDeclFixesTest.privateDeclarationsAPublicOneNamesGoInTheHeadersImplNamespace` (`rules.const.call`:
`helper` is `@_const` now, emitted `constexpr`), `aParameterNamedLikeAFieldOrAModuleDeclarationIsRenamed`
(`rules.mutability.param`: `setX` takes `mut x`, `std::int32_t& x_p`) and `TyperBodyCorpusTest > decls`
(the decls golden's NamingPass warnings are its subject: the test allows NamingPass warnings and no
error), as 40-round3 4.4 assigns them to this package.

## Fixtures outside OWNS rewritten into the allowed form this round

The rule refuses shapes some fixtures used; each was rewritten, not the rule loosened:
the text golden (`word` returning `Maybe<View<Char>>` is `wordEnd` returning `Maybe<Size>`,
source, hand-written `expected/` and driver, `expected.txt` unchanged: 30-second-class 8, which
gives the rewrite to W2.3, whose version wins on merge if the two differ); the typer's body
fixtures (a view local in `views.kira`, a view global in `generics.kira`, the removed
`types.view.temporary` in `more.kira`, and `rules.view.*` added where a negative line now also
breaks the rule); `CppDeclFixesTest`'s `View<Later>` struct field; `CppTypeSpellerTest`'s
prototypes returning a `View` and an `Unsafe` (an extern never hands back a pointer, 5.2) and
its `Unsafe<Unsafe<Int32>>` (spelled now from a `KType` the test builds, since Kira can no longer
write it).

## Open decisions

**The softer reading of "impure", for the user (not implemented).** Decision 4b says a
full-expression that forms a view of a place must not also mutate it or anything that could
alias it, "any impure call, when the place lies in a mutable class". ViewPass reads that
literally (round 2): any IMPURE call. 30-second-class 3.2 proposed reading "impure" as "has a
hidden write that can move storage", which would allow `total(this.buf.view(), log.note())`
with `note` bumping an `Int32`, a print in the span, and `readHeader(buf.view(), mut h)` with
`buf` shared. Deciding that needs the provenance summary round 1 broke five ways, so it stays
the user's call; the literal reading refuses every such program, and each has a
statement-split remedy.

D33/D44 evaluation order: settled for this package by 40-round3 3.2 and the user's OQ-1 (READ
FIRST: an immutable value's operand is read before the call; Q4, read when the call runs, applies to
containers and mutable values). The interim refusal of a container or mutable-value receiver beside a
write of its place goes when W2.9.8 lowers Q4 (evalorder's 192, 195, 222, 389, 393, with 99, 50, 210,
210, 210). OQ-2 (KOTLIN'S ORDER for a subclass's defaults) is W2.4's lowering and asks nothing of the
rules.

Closed in the second-class rounds: convergence round 2's blanket refusal of every borrowing
argument to a virtual, trait or `Fx`-value callee dissolves (no callee can keep a view; such a
call only matters as an IMPURE call in the span of a view of a shared or global place), and "an extern parameter's
non-retention is assumed" is contract 5.4.1 (an extern reads a second-class argument only during
the call and keeps no pointer from it).
