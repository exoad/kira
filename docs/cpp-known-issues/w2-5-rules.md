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
`Fx` parameter (`for x in LOG { h.f() }` with `h.f` adding to `LOG`).

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
a pure binding given an `Fx`.

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
- `callOf` has no `ArrayIndexExpr` case: open, moot until W2.9's member `@_op_get_` parses;
  then `g[i]` on a user class that returns a view of its receiver needs the receiver origin and
  its call in the span, as `b.all()` has.
- Design text (a): `use(this.buf.view(), log.note())` is refused now, as 3.3 says. (b) A method
  calling its own non-escaping `Fx` parameter with a view of `this.data` (`f(data.view())`) is
  refused (an `Fx`-value call is IMPURE); 30-second-class 3.2's "nothing here" should say so,
  since the caller's span never contains that view.
- For W2.3: a D33/E3 spill of a construction inside a PLACE view's span ends the temporary at
  the `;`. A class whose `finally` is IMPURE makes that construction IMPURE here, so the view is
  refused; a pure `finally` running early frees nothing a view can point into.

## Full `./gradlew test` fails 3 tests outside this package's OWNS

**What.** `CppDeclFixesTest.privateDeclarationsAPublicOneNamesGoInTheHeadersImplNamespace`,
`CppDeclFixesTest.aParameterNamedLikeAFieldOrAModuleDeclarationIsRenamed` and
`TyperBodyCorpusTest > decls` fail on this branch. All three pass on `cpp-backend` and fail here
because W2.5's rule passes correctly refuse constructs their fixtures use: the first two hit
`rules.const.call` and `rules.mutability.param`, and the third is a `NamingPass` warning on a
parameter name in W2.2's `decls` golden.

**Where.** `CppDeclFixesTest.kt` (W2.2's OWNS) and `src/test/resources/cpp-golden/decls`.

**Why it is safe to defer.** The rule passes are doing their job; the fixtures were written
before those rules existed and need updating by whichever package owns them (W2.2 for
`CppDeclFixesTest`, W2.1/W2.2 for the `decls` golden). Status: emit: pending.

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

D33/D44 evaluation order questions belong to whichever package owns the emitter's
evaluation-order contract (W2.3/W2.4); current documented behaviour is kept.

Closed in the second-class rounds: convergence round 2's blanket refusal of every borrowing
argument to a virtual, trait or `Fx`-value callee dissolves (no callee can keep a view; such a
call only matters as an IMPURE call in the span of a view of a shared or global place), and "an extern parameter's
non-retention is assumed" is contract 5.4.1 (an extern reads a second-class argument only during
the call and keeps no pointer from it).
