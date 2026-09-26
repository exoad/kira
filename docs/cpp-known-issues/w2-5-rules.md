# Known issues — w2-5-rules

One entry per known minor issue: what, where, reproduction, and why it is safe to defer.
Fixed in convergence round 1: the two significant issues from the last verdict (the
generic-instantiation false positive in EscapePass, and the field-through-a-magic-call
use-after-free gap in ExclusivityPass) — see the round's commit, not this file.
Fixed in convergence round 2: two silent-miscompile gaps in EscapePass, both use-after-free
under policy 1 — `keptArgs` returning nothing for a virtual, trait-dispatched or `Fx`-value
callee (CallKind.VIRTUAL/TRAIT/FN_VALUE never satisfied `analysed(rc)`, and the magic-mutator
fallback needs `fn.body == null`, which none of the three ever have), and a class object's field
write being treated as this body's own storage unless reached through `this`, a global or a
`mut` parameter, when D29 makes every class parameter (`mut` or not) and every plain local
assignment a shared handle to an object the caller or something else may already hold — see the
round's commit, not this file.
Fixed in convergence round 3: round 2's own fresh-local exemption (`EscapePass.freshClassLocals`
/ `classHandleOutward`) was itself unsound — it exempted every place whose *root* walked back to
a locally-constructed class, not just the local itself, so `o.inner.f = ...` (a field of a fresh
local's own field), `o.inner.keep(...)` (a method call kept through that field), `xs[0].f = ...`
(an element of a fresh `List` local, which is `ClassKind.MAGIC` and never a user class to begin
with), a class that publishes `this` from its own `initially` block or any of its own methods,
and a `mut`-argument rebind of the local itself (`rebind(mut c)` doing `c = GC.unwrap()`) all
typed clean while lowering to a use-after-free (confirmed by hand-lowering to C++ and running
it: every hole read a freed `Str`). `classHandleOutward` now only exempts a place that *is*
`Place.Local` itself, never one reached by walking a place's root through a field or an index;
`freshClassLocals` now also requires the constructed type to be `ClassKind.CLASS` with
`ClassSymbol.thisEscapes` false (computed to a fixpoint before this pass's own fixpoint runs),
and counts a `mut`-argument binding of the local as a reassignment. Eight regression tests added
to `EscapePassTest` (round 3 section) reproduce the reported F1/F2/F3/F4/F6/F7/F8/F9 shapes and
fail without the fix. See the round's commit, not this file. (The round-3 commit message and this
file both said "Nine" here; corrected in round 4 — `EscapePassTest` shows 8 new `@Test` methods
for F1/F2/F3/F4/F6/F7/F8/F9, and the round's own count elsewhere in this file, 151 + 8 = 159, was
already right.)
Fixed in convergence round 4: three more silent-miscompile gaps, all use-after-free under policy
1, all found by hand-lowering to C++ and running. (1) `freshClassLocals` read only the
constructed class's *own* `ClassSymbol.thisEscapes`, so a subclass local stayed "fresh" when an
*inherited* method, an ancestor's `initially`, or a trait's default body published `this` —
`ClassSymbol.thisEscapes` is set on whichever symbol's own body does the publishing (`Flows`'
`b.owner`), never on a subclass that merely inherits it. (2) `thisEscapes` was the *only* place
that flag was read, so a closure handed to a field at construction (`CB { f = fx() ... }`) was
never checked at all when the class publishes `this` — `freshClassLocals`/`classHandleOutward`
only ever see a *later* write through an already-existing local (`c.f = ...`), and construction
takes no such place. (3) `RuleSupport.mayBorrow` walked only the declared type's own fields and
gave up on a trait (`else -> null`), so upcasting a class holding a view-capturing closure to a
trait-typed or subclassed-base-typed local, parameter, or container element stopped provenance
tracking outright, even though the concrete object might be any implementor or subclass.
Fixes: `TraitSymbol.thisEscapes` (new field, `Symbols.kt`) records a trait default body
publishing `this`, alongside `ClassSymbol.thisEscapes`; `RuleSupport.classPublishesThis(cls)`
(new) is the union of `cls.thisEscapes`, every superclass's, and every trait's (transitively,
through trait parents) that `cls` or an ancestor implements — `freshClassLocals` now reads this
instead of `cls.thisEscapes` directly. `EscapePass.Flows` gained `markThisEscapes(owner)`,
replacing the old class-only `classOwner()`, so both a direct `this`-as-value use and an
escaping lambda's `Capture.This` mark whichever symbol (class or trait) actually owns the body.
A new `Provenance.constructionEscapes`, run over every `ObjectInitExpr`, refuses a borrowing
field value given at construction when the constructed class's `classPublishesThis` is true,
independent of anything the caller's body does with the resulting local afterward. `RuleSupport.
inside` gained a `throughOpenTypes` parameter, set only by `mayBorrow`'s own call (not by
`viewInside`/`mutViewInside`/`holdsClosure`, so the declared-class-field view-holding checks are
unchanged): a trait-typed or subclassed-class-typed type is now treated as "may hold what
`wanted` looks for" outright, rather than answered from a declaration that cannot see what a
concrete implementor or subclass actually adds. Nine new regression tests added to
`EscapePassTest` (round 4 section) reproduce PA1/PA2/PA3/PA4 (upcast opacity) and PB1/PB2/PB3/
PB6/PB7 (ancestor/trait `this`-escape, including the construction-time case); each one fails
without its fix (verified against the pre-fix tree) and passes with it. `./gradlew test` remains
at the same three pre-existing failures outside this package's OWNS (now 1053 total = 1044 + 9).
See the round's commit, not this file.

## Issue 8 partly covered: three ways a plain `fx` can still write its own state

**What.** The spec rule "methods that modify instance state must be marked `mut`"
(MutabilityPass) only catches a plain `fx` that assigns a field or calls its own `mut fx`
directly on `this`. Three more shapes write the object's state through a plain `fx` and type
clean:

1. Passing its own field as a `mut` argument to a helper that writes it:
   `pub fx a: () Void { bumpI(mut count) }` on a class with `pub mut count: Int32`.
2. Lending a `MutView` of its own `Arr`/buffer field to a callee that writes through it, or
   building one locally and writing through it: `writeU32Le(buf.from(0 as Size), 0 as Size, 5
   as UInt32)`, or `mv: MutView<UInt8> = buf.view(); mv.set(0 as Size, 7 as UInt8)`.
3. A trait's plain default `fx` calling the trait's own `mut fx` on itself:
   `pub trait T { pub mut fx step: () Void; pub fx go: () Void { step() } }`.

**Where.** `MutabilityPass.kt` (`mutMethod`, `~line 54-57`); `Body.thisMutable`
(`StmtChecker.kt:440`) already treats a whole class body as writable, which is looser than
the rule pass and is what makes the C++ side self-consistent (see next paragraph).

**Reproduction.** Any of the three snippets above types with zero diagnostics under STRICT;
`ZR5RegressionProbeTest`'s R11 case in scratchpad (round 5) covers the direct-write shape
only, not these three.

**Why it is safe to defer.** This is a *false negative* in a lint, not a miscompile: W2.4's
`writesReceiver` already drops `const` from the emitted method whenever it sees a `mut`
argument or a `MutView` lend that reaches the receiver's storage, so the generated C++ compiles
and runs correctly either way — a `fx` that in fact mutates state is emitted as non-`const`
regardless of whether MutabilityPass flagged it. The gap is only that Kira's own diagnostic
under-reports the spec's naming rule; it never lets an unsafe or wrong program through. Closing
it requires MutabilityPass to re-walk a plain `fx`'s body for `mut`-argument writes of its own
fields and `MutView` lends of its own fields, and to look inside a trait's own default bodies
for calls to its own `mut fx` — a body-level analysis pass similar to what EscapePass already
does for views, not a one-line fix, so it is deferred rather than rushed. Routed to W2.4/W2.1
per the round-5 verdict; unchanged this round.

## HiddenWrites does not follow a local alias of a class reference inside a callee

**What.** `HiddenWrites` charges a callee's write to the argument bound to the parameter it
writes through, but only when the callee writes that parameter (or a field of it) directly. A
callee that first copies the reference into a local and writes through the local is invisible:
`pub fx resetAlias: (sc: Sc) Int32 { t: Sc = sc; t.k = K { n = 5 }; return 1 }` is not charged
to `sc`, so `sc.k.plus(resetAlias(sc))` types clean under STRICT even though it is the exact
same use-after-free shape as `resetDirect` (`sc.resetK()`), which HiddenWrites does catch.

**Where.** `HiddenWrites` (rebasing pass in `ExclusivityPass.kt`'s support code) and
`MutabilityPass`'s `writesObjectOnly`/`HiddenWrites.of`.

**Reproduction.** `sc.k.plus(resetAlias(sc))` with `resetAlias` as above: no diagnostic from
`HiddenWrites`.

**Why it is safe to defer.** Narrower than it sounds: it requires the callee to (a) take the
class by value (not `mut`, which is already tracked), (b) copy it into a local, and (c) write
through that local's field — three conditions together, and the far more common direct-write
and mut-argument shapes (issues 3 and 10 from round 5) are caught. It is also, on the real
emitter, not meant to stay a dangling-reference shape at all, which corrects this entry's
earlier claim of an unqualified "safe today": W2.3's `CppExprEmitter.receiverOperand`/
`heldByReference` (41575c6) copies a class-handle receiver into a typed temporary whenever a
sibling operand is IMPURE, and `EffectsPass` rates `resetAlias` IMPURE (it writes a field
through a class reference, `t.k = ...`), so once that hoist is in place, `sc` in
`sc.k.plus(resetAlias(sc))` is copied into its own temporary before `resetAlias` runs and the
`.k.plus(...)` read is sequenced against that copy, not against whatever `resetAlias` does to
the shared object. But that hoist lives on W2.3's own branch and is **not merged into
cpp-backend** as of this round — `git log --oneline -- '**/CppExprEmitter*'` on `cpp-backend`
does not show 41575c6 — so on the code this branch actually builds against today, the safety
this entry describes does not yet exist; it holds only once W2.3 lands with that hoist. Nothing
pins the dependency: there is no shared constant, `static_assert`, or cross-package test that
would fail loudly if W2.3 merged without it or changed the hoist's condition, so a silent
regression on either side would reopen a real UAF with no diagnostic to catch it. The gap this
entry actually tracks is `HiddenWrites` itself never seeing the alias, `EffectsPass`'s IMPURE
call needing to classify every callee that could do this (unverified by a shared invariant
either), and this W2.3 dependency having no pin across the two packages. Closing the analysis
gap needs `HiddenWrites`'s per-callee walk to track a local's aliasing of a by-value class
parameter the same way `ViewAliases` already tracks a view local's aliasing of a view parameter,
which is a distinct piece of analysis worth doing on its own rather than folding into this
round's UAF fix. Disclosed unchanged from round 4 and round 5; the "safe because" reasoning
corrected in round 2 of convergence and corrected again (the W2.3 merge dependency and the
missing pin) in round 3.

## `@op_*` overloads and an `Fx`-called-through-a-field are outside HiddenWrites and EscapePass

**What.** Two provenance gaps, both disclosed unchanged since round 4: `HiddenWrites` does not
look inside an `@op_*` operator overload's body for its hidden writes (so a sibling write
hidden behind `a + b` where `+` is user-overloaded is not charged the way a named call's is);
and neither `HiddenWrites` nor `EscapePass`'s capture tracking follows an `Fx` reached through
a field, a global, or an `Fx`-typed parameter back to what it closed over (only a local or
parameter holding the closure directly is tracked).

**Where.** `HiddenWrites.of` (skips `CallKind.OP_OVERLOAD` bodies) and `EscapePass`'s
`captured`/`borrowed` (only trace `Capture.Value` on a `LocalSymbol` or `ParamSymbol`, never a
closure read back out of a field/global/`Fx` parameter and then called).

**Reproduction.** None on file in this tree; both are analysis blind spots noted by inspection,
not confirmed failing snippets.

**Why it is safe to defer.** Both require plumbing this pass does not have yet (a call-graph
edge for operator overloads distinct from named calls; a "what does this field/global hold"
fact EscapePass does not compute for `Fx` values the way it does for locals), and neither has a
demonstrated miscompile — they are coverage gaps in an already-conservative pass, not a hole an
adversarial or ordinary program is likely to hit before W2.5's next round picks them up
deliberately.

## Full `./gradlew test` fails 3 tests outside this package's OWNS

**What.** `CppDeclFixesTest.privateDeclarationsAPublicOneNamesGoInTheHeadersImplNamespace`,
`CppDeclFixesTest.aParameterNamedLikeAFieldOrAModuleDeclarationIsRenamed` and
`TyperBodyCorpusTest > decls` fail on this branch. All three pass on `cpp-backend` (verified at
09ad744 from an archive copy) and fail here because W2.5's rule passes correctly refuse
constructs their fixtures use: the first two hit `rules.const.call` and
`rules.mutability.param`, and the third is a `NamingPass` warning on a parameter name in W2.2's
`decls` golden.

**Where.** The fixtures are `CppDeclFixesTest.kt` (W2.2's OWNS) and
`src/test/resources/cpp-golden/decls` (merged from W2.2/W2.1); this package's OWNS is
`compiler/analysis/types/rules/**` only — the rule refusals themselves are correct per the
brief's own diagnostic-naming table and are not this entry's subject.

**Reproduction.** `./gradlew test --tests CppDeclFixesTest --tests TyperBodyCorpusTest` fails
these three on `cpp/w2-5-rules`; the same command against a `cpp-backend` archive checkout
passes all three (`net.exoad.kira.types.rules.*` itself — 11 classes, 159 tests as of round 3
(151 before this round's 8 new `EscapePassTest` regression cases for F1/F2/F3/F4/F6/F7/F8/F9) —
is unaffected: `RulesCorpusTest` passes in full).

**Why it is safe to defer.** The rule passes are doing their job; the fixtures were written
before those rules existed and now need updating by whichever package owns them (W2.2 for
`CppDeclFixesTest`, W2.1/W2.2 for the `decls` golden), not by loosening a correct refusal here.
Fixing the fixtures is outside this package's OWNS and was previously only recorded in
`notesForOthers`, per convergence policy item 5 this is now on record here too, with the same
"emit: pending" status for whoever picks it up.

## Open decisions

D33/D44 evaluation order questions belong to whichever package owns the emitter's
evaluation-order contract (W2.3/W2.4), not to the rule passes here; current documented behaviour
is kept, per convergence policy item 4.

**The round-2 blanket refusal narrows accepted code more than a whole-program view would need.**
Routed to this ledger in round 3 (previously not recorded here). Since round 2, every view,
view-holder or closure over a local passed to a `VIRTUAL`, `TRAIT` or `FN_VALUE`-dispatched
callee is `rules.escape.view-store`, even on a program where no override or implementor of that
callee actually keeps the argument: the common `sink.write(buf.view())` on a trait, or
`send(frame.view())` / `f(buf.view())` through an `Fx`-typed callback parameter, are all refused
today. This is the correct, conservative call under policy 1 (no silent miscompile: the pass
cannot itself attribute a `VIRTUAL`/`TRAIT`/`FN_VALUE` call to one body, so it must assume the
worst), and it is not a bug — but it is a real, user-visible narrowing of what compiles, worth a
decision rather than silent acceptance: a future round could instead take the union of `Lends`
facts over every override and implementor actually declared in the whole program (which W2.5
already sees in project mode, per `ZC2VerifyProbeTest`'s p2/p2b runs) and refuse only when at
least one of them keeps the argument, refining the diagnostic instead of blanket-refusing the
call kind. Left as documented behaviour for now; not routed anywhere else. Flagged because W3's
bibo ports (carlink's `SendFn`-style callbacks) are expected to hit this shape directly.

**A view of a local passed to an `@_extern` parameter is accepted.**
`@_extern(cpp = "ext::keep", header = "ext.hxx") pub fx keep: (v: View<Char>) Void;` then
`keep(s.view())` with `s` a local types clean under STRICT — `EscapePass.calleeUnknown`
(`ExclusivityPass`'s and `EscapePass`'s treatment of `CallKind.EXTERN`) does not add it to the
callees round 2 made conservative (`unanalysable` covers `VIRTUAL`/`TRAIT`/`FN_VALUE` only,
deliberately not `EXTERN`), so an extern function's parameters are assumed not to retain what
they are handed, i.e. the FFI contract is "borrows for the duration of the call only." Whether
that assumption should instead be refused (require the caller to prove the extern body does not
retain the view, which Kira cannot see into) or documented as the contract every `@_extern`
declaration makes is a language decision for the user, not something this pass should guess at
by itself: an FFI boundary is exactly where a real C++ callee could do what the virtual/trait
case's fix now refuses for Kira-visible bodies, and the same repro shape (`escape_hole.cxx`'s
pattern, with `ext::keep` implemented to stash the pointer) would UAF identically if a real
extern ever did this. Not previously in this ledger.
