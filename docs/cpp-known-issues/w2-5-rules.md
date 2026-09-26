# Known issues — w2-5-rules

One entry per known minor issue: what, where, reproduction, and why it is safe to defer.
Fixed in convergence round 1: the two significant issues from the last verdict (the
generic-instantiation false positive in EscapePass, and the field-through-a-magic-call
use-after-free gap in ExclusivityPass) — see the round's commit, not this file.

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

**Reproduction.** `sc.k.plus(resetAlias(sc))` with `resetAlias` as above: no diagnostic, but
`kira::at`/plain pointer semantics mean the emitted C++ has the same dangling-reference shape
as `resetDirect`, which g++ demonstrates prints a destroyed object (round 5's
`scratchpad/w25r5v/*.cxx`, not carried into this tree).

**Why it is safe to defer.** Narrower than it sounds: it requires the callee to (a) take the
class by value (not `mut`, which is already tracked), (b) copy it into a local, and (c) write
through that local's field — three conditions together, and the far more common direct-write
and mut-argument shapes (issues 3 and 10 from round 5) are caught. Closing it needs
`HiddenWrites`'s per-callee walk to track a local's aliasing of a by-value class parameter the
same way `ViewAliases` already tracks a view local's aliasing of a view parameter, which is a
distinct piece of analysis worth doing on its own rather than folding into this round's UAF
fix. Disclosed unchanged from round 4 and round 5.

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

## Open decisions

None routed to this ledger this round; D33/D44 evaluation order questions belong to whichever
package owns the emitter's evaluation-order contract (W2.3/W2.4), not to the rule passes here.
