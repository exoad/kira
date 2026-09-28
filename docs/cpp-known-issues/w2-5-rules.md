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
  message names an `Fx`-value callee as written; the loop rule counts a node that may run an IMPURE
  `finally` as a write of every shared place (q7), and a write through another handle of the same
  class as a write of the iterated field (q6). Each has a test.
- **Section 4's own four**: CppDeclFixesTest's `helper` is `@_const`, `setX` takes `mut x`;
  TyperBodyCorpusTest allows NamingPass warnings; RulesCorpusTest exempts a golden whose
  `case.yaml` says `pins: spills`. The full suite on this branch: 1059 tests, 0 failures.

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

## Round 3's sweep of the other three branches

Measured in scratch clones, each branch head merged with fe27877 plus this round's rules (W2.3 also
with the seam's CppHoister hunk, 5.1), full suite:

- **W2.6 (76f9ad2)**: 1173 tests, 11 fail, exactly 40-round3 4.3's CppExternEmitterTest eleven.
- **W2.4 (4064c26)**: 1195 tests, 9 fail: 4.2's seven, and two loops the checker now refuses first
  (`CppClassLifetimesTest.aLoopOverAnObjectsListWhoseBodyMayGrowItIsRefused`,
  `aFieldsDefaultLambdaIsCheckedAsABodyIs`: `for s in b.items { c.grow() }` with `c` another handle,
  `rules.exclusivity.loop`, round 2's q6). They expect the checker's refusal now, W2.4's staying a
  backstop tested with the rule passes off, as 4.2 does for the alias case.
- **W2.3 (9740456)**: 1168 tests, 37 fail: evalorder in three tests (192, 195, 222, 389, 393) plus
  `noGoldenOperandGroupNeedsASpill` (evalorder's `case.yaml` needs `pins: spills`); 31 CppHoisterTest
  cases on the shared `hoist:cases` module (98, 102, 253, 257, 273: the five receiver functions move
  to their own module); `aViewWhereTheRuleAllowsNone...` (rows to rewrite, 4.1); and
  `aViewOfATemporaryIsFormedInsideTheLambdaThatHoldsItsOwner`, whose `refOwnerSpilled` assertion
  expects `makeRef()` spilled, which EffectsPass proves PURE (the temporary lives to the end of the
  full-expression; the lowering is safe). CppExprRowsTest's 37 all pass.
- Every golden and resource: refused only at evalorder's five lines; the negative fixture
  `typer/body/negative/captures.kira` keeps its 91 `rules.view.type` and 129 `rules.const.type`.
- Earlier rounds' probes (156 projects): no line refused before is accepted now except F1's and F2's
  own (evalorder 143, 231, 233, 236, 259; the hoister cases 245, 362, 367, 373; views 107,
  optimistic 29; r2v23 m9 24-26, the qualified-global twins R-E makes W2.3 copy; r2v6 a5, a6, c1,
  the `mut Unsafe<T>` calls W2.6 lowers). Newly refused: R-A's (c2, c2b, c2c, c2d, c4, c4b, q10-q14,
  r3d q1, q2), q6, q7, and r2v6 b1, b1c, b1u, b1k by `rules.exclusivity.alias`, whose index spelling
  (`gstrs[0]` beside a lambda that replaces `gstrs`) the rule always refused. b1k is 40-round3 6.2's
  "Kira-function control, still 82": with W2.4's entry snapshot the alias rule over-refuses a Kira
  callee handed a direct lambda; narrowing it to C++-supplied callees is a round-4 question, not done
  here.

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
iterated, q6) and its miss of a `finally` a drop runs (q7). R-C's `mayRunAnything` would close the
third for the loop rule, at the cost of refusing every loop over shared storage whose body calls an
extern or a trait method with an object argument; W2.4's emitter refuses such loops today.

**Where.** `HiddenWrites.of` / `lambdasRun` in `RuleSupport.kt`.

**Why it is safe to defer.** None of the three affects a view: the view rule reads EffectsPass,
and each is IMPURE there (a write through a local copy of a class reference is not a local
write, an operator overload has its body's effect, a call through a field, a global or an `Fx`
value is `FN_VALUE`). What remains is D33/D37's exclusivity of non-view places, where W2.3's
hoist copies a class-handle receiver into a typed temporary beside an IMPURE sibling
(`resetAlias` is IMPURE); that dependency has no pin across the two packages and is recorded
unchanged from rounds 3-5.

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

## Conservative approximations in EffectsPass

**What.** (1) Anything that may drop a handle of a class whose `finally` is IMPURE is IMPURE,
even where that handle cannot be the last: every by-value parameter, local, construction, call
result, array literal and closure whose type may hold one, counting a trait, a type parameter,
an `Fx`, a stdlib handle and a generic superclass's fields as any class (only when some class
has an IMPURE `finally`). (2) Any `initially` in a class's ancestry makes its construction
IMPURE. (3) A pure binding that compares or hashes elements (`contains`, `Map.get`,
`containsKey`, `containsValue`) over a user class, trait or type parameter is IMPURE, and so is
a pure binding given an `Fx`. (4) Round 3: in a loop over a shared place, any node that may run an
IMPURE `finally` (it may drop such a handle, or calls what may, including a call R-C says may run
anything) is a write of the iterated place, whatever that `finally` writes (`FinallyRuns` in
ExclusivityPass.kt); and a write through any handle of a class is a write of the same field reached
through any other handle of it (`mayBeSame`).

**Where.** `Effects` and `Drops` in `EffectsPass.kt`.

**Why it is safe to defer.** Each over-approximates, so it can only refuse a view that is
safe, or make the C++ emitter spill an operand it need not (D33), never accept a view that is
not safe. No golden or example has a class with an IMPURE `finally`.

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
