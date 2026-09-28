# Known issues — w2-5-rules

One entry per known minor issue: what, where, reproduction, and why it is safe to defer.

Rounds 1-4 of convergence patched EscapePass's view provenance analysis; it never converged.
Decision 4b (views are second-class, design 30-second-class) replaced it in the second-class
round: `rules/ViewPass.kt` (the ten `rules.view.*` codes, `TypedModel.viewOrigins`) and the
view rule's summary in `HiddenWrites` (`Hidden`, `HiddenWrites.summary`). Deleted: EscapePass's
view half (`Lends`, `Provenance`, `viewFields`, the view parts of `Flows`) and its three codes
`rules.escape.view-return`, `view-store`, `view-field`; `TypedModel.viewEscapes`;
`RuleSupport`'s `mayBorrow`, `holdsClosure`, `viewInside`, `classPublishesThis` and
`ViewAliases`; `TraitSymbol.thisEscapes` (round 4's, read only by `classPublishesThis`); the
typer's `types.view.temporary` (a temporary lives to the end of its full-expression). Every
significant issue of the last verdict is refused by the rule, each with a test in
`ViewPassTest`: Q1, Q1d, Q9, Q2, Q3, Q3b, Q7b, Q7c, Q11 at `rules.view.return`; Q1e, and Q4,
Q4c, Q4d, Q5, Q5b, Q5c, Q6, Q6b, Q6c as written (a view local) at `rules.view.local`, and with a
view parameter instead at `rules.view.capture`; Q7, Q4b, Q10 at `rules.view.type`.

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

## ExclusivityPass's place lists miss three shapes the view rule's summary does not

**What.** `HiddenWrites.of` (the place lists ExclusivityPass reads) misses: a callee that copies a
class reference into a local and writes through the local (`t: Sc = sc; t.k = K {}` is not
charged to `sc`, so `sc.k.plus(resetAlias(sc))` types clean); an `@op_*` overload's body (a
sibling write hidden behind a user `a + b`); and a lambda reached through a field, a global or an
`Fx` parameter (`for x in LOG { h.f() }` with `h.f` adding to `LOG`).

**Where.** `HiddenWrites.of` / `lambdasRun` in `RuleSupport.kt`.

**Why it is safe to defer.** None of the three affects a view: the view rule reads
`HiddenWrites.summary`, which counts any write through a reference as `heap` (the local alias),
summarises operator overloads like any static callee, and makes every call through a field, a
global or an `Fx` value `any`. What remains is D33/D37's exclusivity of non-view places, where
W2.3's hoist copies a class-handle receiver into a typed temporary beside an IMPURE sibling
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

- A view from a virtual or trait method on a class receiver (`total(h.all())`, `h: Viewer`) is
  `rules.view.write`: the call forms the view of its receiver and is in its own span (3.1), and a
  dynamic call may write anything (3.2). The remedy is a view parameter, or a final method.
- A view of a shared or global place passed to a virtual, trait or `Fx`-value call
  (`sink.write(buf.view())` with `buf` a `List` parameter) is `rules.view.write` for the same
  reason; a view parameter (`sink.write(buf)` with `buf: View<UInt8>`) or a local's view passes.
  This replaces round 2's blanket refusal of every borrowing argument to such a callee.
- A struct `mut fx` called on the implicit receiver names all of `this` (3.2), so
  `total(items.view(), tick())` is refused even when `tick` writes only an `Int32` field;
  ExclusivityPass refuses the same call (`rules.exclusivity.order`).
- `f(xs.view(), mut xs)` and `sb.add(sb.view())` are refused by both passes:
  `rules.view.write` at the view, `rules.exclusivity.argument`/`receiver` at the operand.

## Conservative approximations in `HiddenWrites.summary`

**What.** (1) Every function with a body is charged with the writes of every class's `finally`,
not only the classes whose handles it may drop. (2) A stdlib binding without `pure: true` is
`any` when a type argument is a user class, struct, trait or type parameter, whatever the
binding does with it. (3) A call of the checked body's own non-escaping `Fx` parameter is `any`
in the check of that body (its writes are charged at the body's call sites only when a summary
is built).

**Where.** `HiddenWrites.summarise`, `magicHidden`, `callHidden` in `RuleSupport.kt`.

**Why it is safe to defer.** Each over-approximates, so it can only refuse a view that is
safe, never accept one that is not; only a view of a shared or global place with such a call
in its span is affected, and no golden or example has one.

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

**3.2's reading of "impure", flagged for the user.** Decision 4b says a full-expression that
forms a view of a place must not also mutate it or anything that could alias it, "any impure
call, when the place lies in a mutable class". ViewPass reads "impure" as "has a hidden write"
(30-second-class 3.2), so `trace(total(this.buf.view()))` passes because `trace` only prints.
It also counts a hidden write only when the written value can hold storage
(`Rules.mayHoldStorage`): a callee that bumps an `Int32` field moves nothing a view can point
into (`ViewPassTest.aHiddenWriteOfAScalarFieldOrAPrintMovesNothing`). The literal reading is one
line: refuse when `model.effect(C) == IMPURE` instead of when `hidden(C)` holds the place.

D33/D44 evaluation order questions belong to whichever package owns the emitter's
evaluation-order contract (W2.3/W2.4); current documented behaviour is kept.

Closed this round: round 2's blanket refusal of every borrowing argument to a virtual, trait or
`Fx`-value callee dissolves (no callee can keep a view; such a call only matters as a hidden
write that may move a shared or global place in a view's span), and "an extern parameter's
non-retention is assumed" is contract 5.4.1 (an extern reads a second-class argument only during
the call and keeps no pointer from it).
