# Known issues: w2-6-ffi (copy by default, round 4)

One entry per deferred issue: what, where, how to reproduce it, and why leaving it is safe.
Fixed issues from the last verdict are not listed here (see the round's commit message).

## Round 4 (50-round4 6.5): copy by default at the FFI boundary

### The merges

1. `git merge --no-ff cpp/w2-5-rules` (b5910b6), signed as b2b0b2f. Two conflicts, both in the
   comment on the shared `Rules.mayHold` (RuleSupport.kt, and ViewPass's one-line delegate):
   both sides carried the same body since round 3 moved it there. W2.5's comment is kept
   (readers: ViewPass, rule M, W2.3's W3), because the R-B copy the old comment named is
   deleted this round. `rules/` is then byte-identical to w2-5's tip.
2. `git merge --no-ff cpp/w2-3-emit-exprs` (c06e011), signed as 879c819. One conflict, in
   `CppDeclEmitter.assembleHeader`: this branch's `extern "C" { }` block for `c =` headers, its
   tail of part includes (`kira/ffi.hxx`) and its extern checks after the module's own
   declarations are kept, and every include line goes through W2.3's new `includeLine`, so a
   binding's `<cmath>` is spelled with angle brackets in each of the three lists.

On the two merges alone: 1320 tests, 3 failures, each one this round changes on purpose:
`CppExternEmitterTest.aByReferenceArgumentThatIsAPlaceIsCopiedWhenTheCallMayWriteIt` (asserted
the deleted `rules.exclusivity.alias` on the direct lambda), and both `ExternDelegationTest`
tests (bodies lower now, so the body check runs and forward must be `emit: required`).

### What changed

- **Deleted: `copiedArguments`, `lentByReference`, `writable`** (round 3's R-B). The copy
  policy (W2.3's `CppCopyPolicy`) decides for an extern call as for every call, and
  `CppExternsPart.call(ctx, rc, receiver, args, copied)` takes its answer (the `copied` set,
  indices of `rc.args`) and spells it: `kira::ffi::in(kira::Str(e))`, `kira::ffi::CStrBuf(e).c_str()`,
  `T(e)` (`kira::Rc<C>(h)` for a handle). The parameter has no default, so no caller can forget it.
- **The extern half of the merge seam** (the trial did it in c10000b; W2.3's hooks said "at the
  merge this delegates"): `CppExprEmitter.externCallText`, `externConstant`, every field read
  (`CppExternsPart.field`) and an extern function named as a value (`CppExternEmitter.globalName`)
  go through the part. `externName` and `externMemberName` are gone from W2.3's file. The class
  and generic seams are W2.4's and the integrator's, untouched.
- **A bodiless `pub` prototype is called in Kira's own spelling** (`CppExternEmitter.proxied`):
  Kira declared its C++ parameters (`const kira::Str&`, `T&`, `const char*`, `const T*`), so a
  `Str` is passed as the `kira::Str` it is and a `mut` argument as the `T&` it binds, with no
  `kira::ffi::in`/`out`. Its `Unsafe<T>` and `CStr` parameters keep `.data()`, `.c_str()` and
  `CStrBuf`. `@_extern` callees and `@_opaque` methods keep the proxies (C++ declared those).
- **`kira/ffi.hxx` is asked for where `kira::ffi::` is spelled** (`externCallText`, through the
  new `includeWhereWritten`, which `use(binding)` now shares): the header when the code is
  header-placed, the source otherwise. A call of an `@_extern` asks for nothing more, because
  its own module's header asks for the file (its checks do) and every caller includes that
  header; this is what keeps forward's `forward.kira.cxx` byte-identical.
- **A `Str` place lent to a `CStr` is `.c_str()` of its own buffer however it is spelled**
  (`(kira::at(NAMES, 0)).c_str()`, `gp.name.c_str()`): w2-6 minor #0. A copy is `CStrBuf`.
- **An extern's result is a prvalue** (`CppExternsPart.resultIsTemporary`, read by W2.3's
  `CppCopyPolicy.kiraCall`, the one line this package adds to W2.3's file): an `@_extern` result
  through `kira::ffi::declared<T>`, which returns a `T` by value, and a prototype's, which Kira
  declared by value. A result kept as C++ gave it (a pointer; an `@_opaque` method's) is not.
  Without it forward gained `::bibo::Scan(kira::ffi::declared<::bibo::Scan>(car->scan())).ahead()`,
  a copy of a temporary (measured, CppGoldenEmitTest). A reading beyond 2.0's PRVALUE list,
  which names "a call of a Kira function".
- **forward is `emit: required`**, byte-identical, as ExternDelegationTest asks once bodies lower.

### Round 3's findings (sc-round3.json, round3.w2-6-ffi.verdict)

| Finding | End (50-round4 5.1) | Test, and the value measured |
|---|---|---|
| #0, R-B's `mayHold` one way only (t1 `ptNAfterMut(gp, mut o)` 5 for 1; t1u a heap-use-after-free) | correct: the policy's W3 tests `mayHold` both ways against every own `mut` operand, so `GP`, `GL`, `GLL` are copied | ExternCallRunTest rows viaN 1, viaL 1, viaS 82, viaI 1; `CppExternEmitterTest.aByReference...` (`Pt(GP)`) |
| #1, an if-expression over places reaches an extern by reference (t5 63 for 6; t11 a heap-use-after-free, 2 for 1) | correct: a ternary is no place and no prvalue, so it is `T(c ? a : b)` unless both branches are PRIVATE | rows r1L 6, r1F 1, r1P 1; r1S 82 and r1C 82 (a `Str` ternary is a prvalue); r1Local 6, lent `c ? a : b` |
| #2, a prototype's call spells `kira::ffi::in` where nothing includes `kira/ffi.hxx` (t13, t13b: no compiler built them) | correct: no proxy at a prototype, and the header asked for where `kira::ffi::` is spelled | `aPrototypeIsCalledInItsOwnSpellingAndAsksForFfiOnlyWhereItSpellsIt`; `everyPrototypeRowPrintsKirasValueOnEveryCompiler`: t13 24, t13b 4, a copied `Str` 82, a copied `CStr` 82 (its module includes `kira/ffi.hxx`), a `mut` 2 |
| [owner w2-4] #3, the kind-1 entry snapshot depends on an unrelated class (t20, t15, t7v, t7k, t7u, t18k) | W2.4's to delete (kind 1); W2.3's policy copies at the call instead, and the class-free probes are right here | t20 82 (round 3: 168), t7k 82 (168), t7u `6 6` and t7v 6 (heap-use-after-free), t18k 82 (1), and t18 164 (83: the value receiver is the copy Kira read); t15 and t20b need classes |
| w2-4 #1 [owner w2-6], the same missing `kira/ffi.hxx` (proto1, externstr, externnested, externfnval) | correct by #2's fix | as #2 |
| minor #0, a `Str` place that is no bare name always got `CStrBuf` | correct, above | `aCStrParameterTakesAStrAsSection72Says` (`(kira::at(::ext::NAMES, 0)).c_str()`) |
| minor #1, a spilled argument copied twice | correct: a spilled temporary is W1 (the policy's), never copied again | row sSpilled 82: `const kira::Str t0_ = GS;` then `lenAfterL(kira::ffi::in(t0_), t1_)` |
| minor #2, ExternCallRunTest ran hand-written texts | correct: every row is a Kira function lowered by the real expression emitter, checked by text and by value | ExternCallRunTest, 33 rows and 5 prototype rows |
| minor #3, `viaMutP(mut g, w)` refused naming "storage an object holds" | W2.5's rule M names the storage now | W2.5's ExclusivityPassTest |
| minor #4, the alias refusal set depends on the `Fx`'s spelling | gone with the alias rule: the direct lambda is accepted and copied | row sDirect 82, `lenAfterF(kira::ffi::in(kira::Str(GS)), []() -> void ...` |
| minors #5, #6, #7 | unchanged, below (not lowering questions) | - |

### Measured

- `ExternCallRunTest` (the real emitter, 33 rows in `app:w` plus 5 in two modules with no
  `@_extern`): every row's call text is the policy's (a copy where it copies, none where it
  lends), and every value is Kira's on gcc 13.2, zig clang 20 and MSVC 14.44.
- The same programs as scratch probes (`scratchpad/w26r4/p/ecall`, `eproto`), with MSVC
  `/fsanitize=address`: `82 82 82 82 3 2 83 82 82 82 82 6 6 82 82 82 82 82 82 82 10 24 1 1 82 1 82
  82 6 1 1 6 82` and `24 4 82 82 2` on all three compilers, 0 ASan reports.
- Round 3's own probes on this branch's CLI, g++ | clang | MSVC ASan: t1 without its class rows
  (t1s) `1 1 82`, t1u `1`, t5 `82 82 82 82 6`, t11 `6 6 6 1 1`, t13 `4 2`, t13b `4`, each the
  same on all three, 0 ASan reports. Round 3 printed `5 5 102`, `1662156576`, `.. 63`, `63 21 6
  1672576912 2`, and t13/t13b did not compile.
- The rest of round 3's w2-6 probes on this CLI: t5b 82, t6 `82 82`, t22 `82 82`, the same on all
  three with 0 ASan reports. Refused as in round 3: t4 (`types.init.not-constructible`), t7
  (`types.fn.foreign-value`), t9 (`cpp.no-body`), t10a-e (`types.lambda.assign-capture`; t10c
  now also `rules.view.write` and rule M's `rules.exclusivity.mut` for `viaMutParam(mut gl)`,
  a global passed `mut` to a call that runs an `Fx`), t16, t1b and t1c (D37's
  `rules.exclusivity.argument`), t17v (`rules.view.write`), t21 (rule M, naming "a global": minor
  #3). Not lowered here (classes): t2, t2k, t3, t14, t15, t17, t19, t20b. t5c is a parse panic
  in round 3 and here alike (the probe's own syntax).
- ffi tests: 116, 0 failures, 0 skipped (round 3: 112, 1 skipped, ExternDelegationTest's).
  Full suite: 1325 tests, 0 failures, 0 skipped. `examples/regenerate.sh --check`: all snapshots
  current, the C++ leg 2 of 2. `examples/ffi-mini/run.sh` ok; `examples/cpp/13-ffi-cpp/run.sh`
  ok. `kira/cpp/tests/run.sh` 66 passed, 0 failed (ffi_test 29/0); `msvc.bat` all passed
  (ffi_test 29/0, `KIRA_FFI_DRIFT=1` refused with Kira's message); `goldens.sh` 18 cases, 71
  passed, 0 failed (forward 6/0 and imgui-shape 12/0 on gcc, clang and msvc, and a zig-aarch64
  link each).

### Contract 5.4.4 (new): C++ that calls Kira keeps invariant I

C++ that calls a Kira function or an `Fx` passes each non-`mut` argument as storage nothing
changes until the call returns, and holds the receiver (a class's handle) for the call. That is
what lets a Kira callee read its parameters and its `this` with no guard (50-round4 2.0), and
what makes a by-value parameter PRIVATE in the callee. A C++ callback that hands Kira a
reference into storage it then changes from another path during the call (a C++ container the
same callback clears) breaks the contract, as a data race does: Kira cannot see it.

Contract 5.4's other lines, which the policy's W3 extern row reads (`!CallReach.mayRunAnything`):
5.4.1 an extern reads a second-class argument only during the call and keeps no pointer from
it; 5.4.2 it writes Kira storage only through its `mut` arguments and its receiver; 5.4.3 it
runs a Kira `Fx` only during a call that is given it, or given something that may hold it (a
handle to the C++ object that keeps it, which as a class or `@_opaque` receiver or argument
always counts). A C++ callback registry is reached through such a handle, never through a free
function given none.

### Open

- **The class half is not run on this branch.** Classes do not lower here (W2.4's): round 3's
  t1 rows `ptNAfterMut(h.p, mut h2.p.n)` and `firstAfterMut(h.xs, mut h2.xs[0])` (a `mut`
  operand through a second handle: rule M's STABLE accepts it, and the policy copies `h.p`
  because `h2.p.n` may lie in it, Kira's 1 1), t14, t17, t18k, t20/t20b, t22 and the
  `@_opaque` matrix. Their text is emitted by this code; the runs are the trial's.
- **A pure `@_opaque` class with no `@_extern(header = ...)` cannot be called.** Kira emits only
  `class Gauge;`, so `newGauge().lenOf(s)` is "invalid use of incomplete type" (measured, g++, the
  scratch probe eproto). Nothing names the header that defines the class. The call's text is
  right (`t0_->lenOf(kira::ffi::in(t1_))`, and the source includes `kira/ffi.hxx`). An opaque
  class is declared with `@_extern(cpp = ..., header = ...)` today (imgui-shape's `DrawList`).
- **A header-placed prototype call that spells `kira::ffi::`** asks for the include in the
  header (`includeWhereWritten`'s first branch); no test has such a call (a generic function's
  body calling a prototype with a copied `Str` for a `CStr`).
- **MSVC ASan is not in the gradle harness.** The 0-report figures are the scratch runs above.

## Round 3 (40-round3 5.6): R-G's typer switch and dispatch, F2's typer half

Round 3's R-B (`copiedArguments`, a copy made at an extern call on two conditions) is deleted in
round 4 (above); its rows are ExternCallRunTest's, now decided by the copy policy. What round 3
built and round 4 keeps:

### R-G, as built

`CallResolver.isExternLike` is `suppliedByCpp`, and `kindFor`/`free` give every such call
`CallKind.EXTERN`, which the expression part dispatches on, so it reaches `CppExternEmitter.call`
without a change to W2.3's file. `isExternCall` is `suppliedByCpp` too. A markerless prototype is
spelled as the module spells its functions (`ctx.qualified`), keeps Kira's own defaults, gets
no `declared<T>` (Kira declared its C++ type) and, since round 4, no proxy. A bodiless method of
a class is a slot, not an extern (round 2's `isExternLike` counted any bodiless `pub` method).

### Kept from round 3's decisions

- **3.3's "T may hold q" is `Rules.mayHold`** (RuleSupport.kt), one predicate for ViewPass, rule
  M and W2.3's W3.
- **A `View` of a captured variable is a `View`.** The typer made `xs.view()` a `MutView` for a
  captured `mut` local, and a read `sumV(xs.view())` inside a lambda was lowered `kira::mutView(xs)`
  over the lambda's const copy: g++ and clang rejected it (measured, probe f_viewcap). It is a
  `View` now; handing it to a writer is F2's refusal.
- **F2's typer half**: a `MutView` of a capture into `mut Unsafe<T>` is `types.lambda.assign-capture`
  (`aMutUnsafeTakesAMutViewOfALocalAndAMutViewOfACaptureIsRefused`).
- **The stdlib half of round 3's question** ("a stdlib binding given an `Fx`") is the copy
  policy's now (a `MAGIC` consumer given an `Fx` is not CONFINED), not this emitter's.

## Second-class views round 2: the two significant findings of round 1's verdict

Round 1's verifier (`wave2/sc-round1.json`, `"w2-6-ffi".verdict`) passed every acceptance
command but found two significant issues in round 5's own change, both this package's own
(neither is a `rules.view.*` shape `w2-5-rules`' `ViewPass` covers). Both are fixed this
round; the verdict's `minorToLedger` list is addressed below.

### Fixed: a plain `Str` argument bound by reference could dangle when the same call ran Kira code through an `Fx` argument

**What.** `CppExternEmitter.argument` lowered a `Str` parameter's argument as
`kira::ffi::in(text)` whichever way `text` names storage: a place (a global, a field through
a `Ref` or a handle, a local) is bound directly, `In{s = text}`, a `const std::string&` over
the place's own buffer. Kira passes a `Str` by value, so nothing about that binding is
observable from Kira's own side of a *pure* call — but contract 5.4's own point 3 lets an
extern run Kira code through the `Fx` arguments it is given, and that Kira code may reassign
the very place the `Str` argument names before the callee finishes reading it, freeing (or
reallocating) the buffer `In::s` still refers to.

**Where.** `CppExternEmitter.kt`'s `argument` (the `p.type == KType.Str` branch) and `call`
(the new `mayHoldFx` scan).

**Reproduction (round-1 verdict, probes u5/u8, trial CLI).**
`lenAfter(gs, fx() Void { gs = "" })` with `gs: mut Str` a global printed 0 for 82 on g++ and
clang, MSVC ASan a heap-use-after-free in the callee reading the freed buffer; the same
through `r.value` with `r: Ref<Str>`.

**Status: fixed this round.** `call` now computes `reenters` once per call: true when any
given argument's type is an `Fx`, or may hold one through value composition (an
`Arr`/`List`/.../`Maybe`/a `TupleN`'s type arguments, or a struct's fields) or through any
reference type this static check cannot see past (a class, a trait, `Ref`, `Weak` — the same
grouping `TypeFacts.isReference` uses; a generic `T` counts too, unknown). `argument` reads
`reenters` and, for a `Str` parameter whose given argument is a place
(`ctx.model.places[expr] != null`), copies it first: `kira::ffi::in(kira::Str(text))` — a
fresh `kira::Str` (`std::string`) a reassignment elsewhere cannot reach, its own lifetime
extended to the end of the C++ full-expression by ordinary temporary-lifetime rules, the same
pattern `CStrBuf`/`in` already rely on for a computed argument (E6, 30-second-class.md 3.4).
A literal or a computed expression is unaffected either way (nothing else can name it to
write it back), and so is any call with no `Fx` argument reachable in it — round 1-4's own
fixes are untouched. `CppExternEmitterTest.aStrArgumentThatIsAPlaceIsCopiedFirstWhenTheCallAlsoCarriesAnFx`
covers a global place, a local place (copied too — this fix does not try to prove a lambda's
copied captures cannot reach it, the conservative call), a literal (unaffected), the same
global with no `Fx` anywhere in the call (unaffected, the round 1-4 baseline), and an `Fx`
nested inside a struct argument's field (`mayHoldFx`'s recursion, not a direct `Fx`-typed
argument).

**Not this round's concern, restated.** The same aliasing shape on the `CStr`-from-named-`Str`
path (`.c_str()`, not `kira::ffi::in`) — `lenAfterC(gs, fx() Void { gs = "<longer>" + gs })` —
is round 1's own `minorToLedger` note "for W2.5": `ViewPass` is meant to refuse it by treating
a `Str` given to a `CStr` extern parameter as a view with origin `PLACE(gs)`. This package's
fix does not touch the `CStr` branch; carried below under "Deferred to other packages" so it
is not lost.

### Fixed: `Unsafe<T>` did not take everything design 1.4 and section 5 promise it does

**What, part (a).** Design 1.4: a non-`mut` `p: Unsafe<T>` takes "a `View<T>` argument (or
anything the typer converts to one, `Coercion.ToView`)" — the same conversion an ordinary
`View<T>` parameter accepts. `CallResolver.typeGiven`'s `Unsafe<T>` branch only ever checked
whether the argument's *own* static type already was `View<T>`/`MutView<T>`; a bare
`Arr`/`List` place or a `Str` literal fell through to the plain `Unsafe<T>` mismatch.

**What, part (b).** Design 30 section 5: "'Extern' here means every function whose body C++
supplies: an `@_extern` function or method, and a bodyless `pub` prototype a C++ file defines
... as W2.4 already groups them" — the decls golden's own
`peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;` has no `@_extern` marker at all.
`externCallee` (both call sites, `method` and `free`) was `fn.foreign is Foreign.Extern`
alone, so this convention — and the `CStr`-for-`Str` one beside it — never applied to such a
prototype.

**Where.** `CallResolver.kt`'s `typeGiven` (the `Unsafe<T>` branch, non-`mut` case) and the
new `isExternLike`; `CppExternEmitter.kt`'s `argument` (the `Unsafe<T>` branch now also reads
a recorded `Coercion.ToView`).

**Reproduction (round-1 verdict, probes u2/u10, trial CLI).** `sumP(ys, ys.size())` with
`ys: List<Int32>` refused `types.assign.mismatch "expects Unsafe<Int32>, but this is
List<Int32>"`; `lenBuf("abc")` the same against `Unsafe<Char>`; `peek(ys.view(), xs.view())`
against the bodyless `peek` above refused three ways at once (the `View` argument's mismatch,
plus `types.call.mut-missing`/`mut-not-place`/`mut-type` for the `mut` `MutView` one, since
neither the coercion nor the by-ref `MutView` exemption ever ran for a non-`Foreign.Extern`
callee).

**Status: fixed this round.** (a) `typeGiven`'s `Unsafe<T>` branch now also tries
`CoercionRules.fit(arg, t, View<element>)` before the plain mismatch; a fit records
`Coercion.ToView` exactly as a `View<T>` parameter would, and `CppExternEmitter.argument`
reads that coercion (alongside the pre-existing exact-type check) to decide the `.data()`
wrap. The `mut Unsafe<T>` case is untouched — still `MutView<T>`-only — since design 1.4
gives the "anything the typer converts" clause to the non-`mut` case alone. (b) `isExternLike`
is `fn.foreign is Foreign.Extern || (!fn.hasBody && fn.isPub)`, and both `externCallee` call
sites read it. A private bodyless function is a different, already-refused
(`CppDeclEmitter.refuseBodilessPrivates`, `NO_BODY_CODE`) shape, so `isExternLike` stays
`isPub`-gated and does not touch it. `CppExternEmitterTest.
aListOrArrPlaceOrAStrLiteralCoercesToUnsafeViaToView` (a List/Arr place, a Str literal, the
wrong element type still refused, the `mut` case still refused) and `.
aBodylessPubPrototypeIsAnExternForTheUnsafeConventionToo` (the bodyless `pub` prototype
accepted, a private one still refused) cover both.

**Touches `CallResolver.kt`, outside this package's OWNS/TOUCHES.** Same disclosure as the
round-5 entry below for this file (and the round-2 entry further down): 30-second-class.md
7.0 assigns "the `Unsafe<T>` coercion at extern calls" to this package explicitly, and
`isExternLike` is the same call sites' own `externCallee` decision, not a new one.
`./gradlew test` (997 tests: 994 + 3 net new, 0 failures) passes with it in place;
reconciling it with any independent change another package makes to the same file is
`cpp-backend`'s merge step.

### Deferred to other packages (round-1 verdict's `minorToLedger`, not this package's to fix)

Recorded here so they are not lost between rounds, per the verdict's own routing (neither is
this package's OWNS, and neither is one of the two significant findings assigned to it):

- **For `w2-5-rules`.** `lenAfterC(gs, fx() Void { gs = "<longer>" + gs })` — a named `Str`
  into a `CStr` parameter, lowered `gs.c_str()` — is a heap-use-after-free under MSVC ASan
  (measured: prints 6 where 82 is correct). `3.3`'s rule refuses it only if `ViewPass` treats
  a `Str` given to a `CStr` extern parameter as a view with origin `PLACE(gs)`, which the
  typer records no coercion for today. Include this shape in `ViewPassTest`. **Closed in round
  3 by R-B's copy** (`CStrBuf(gs).c_str()`, 82), not by ViewPass.
- **For `w2-3-emit-exprs`.** The current hoister's `lentArgument` makes every extern argument
  `LENT`, so `lenPlus(gs, change())` spills `change()` first and passes `kira::ffi::in(gs)`,
  printing 16801 where 8201 is correct (the Kira-function control `lenPlusK` snapshots `gs`
  and prints 8201 correctly). Design 7.2 replaces `lentArgument` with a read of
  `viewOrigins` in W2.3's commit B, after which a first-class `Str` argument is
  SNAPSHOT-copied.
- **A lambda capturing a `mut` local reaches a `mut Unsafe<T>` and fails at the C++ compiler,
  not at Kira.** `run(fx() Void { fillP(xs.view(), 3, 0) })` with the `mut` local `xs`
  captured is accepted by Kira (rc 0), and g++ then fails with "no matching function for call
  to `mutView`". The `Unsafe<T>` coercion's early return in `typeGiven`'s `byRef` branch skips
  `mutArgument`'s `writesCapture` check, and the `MutView` lending at `CallResolver` (the
  `.view()`/`.from()`/`.slice()` return-type override, ~line 296) treats a capture as a
  mutable place. This predates this round's fix (it is the pre-existing exact-`MutView`
  path, round 4's) and is not one of round 1's two significant findings for this package;
  should be a Kira diagnostic (`types.lambda.assign-capture`, the same one `mutReceiver`
  already reports for a `mut fx` receiver), not a C++ compile error. **Closed in round 3**:
  `types.lambda.assign-capture` (F2's typer half).

### The kept non-regression tests still model programs decision 4b's `ViewPass` will refuse once merged

**What.** `aLiteralANamedStrOrAConstantIntoACStrParameterIsNeverRefused` binds
`r: Maybe<CStr> = after(...)` and `aMutUnsafeOutBufferBesideAComputedStrArgumentIsNeverRefused`
declares `mut b: Unsafe<UInt8> = allocBuf(4)` — both shapes `rules.view.type`/
`rules.view.extern` refuse once `w2-5-rules`' `ViewPass` merges (a `Maybe<CStr>` result type,
an `Unsafe<T>` local). Design 7.4 itself only ever asked to keep "a literal, a named Str and
a constant into a CStr parameter" from those tests, over whatever first-class result the
callee's other tests already use elsewhere in this file, not literally the same
`Maybe<CStr>`/`Unsafe<UInt8>` callees. Not touched this round (not one of round 1's two
significant findings, and the tests are correct today on this branch alone, which has no
`ViewPass`); left here so the next round that merges `w2-5-rules` rewrites these two tests
over first-class results before they contradict the rule. **Closed in round 3**: both rewritten.

### The three unsigned round-2/3/4 commits are resolved

**What.** Round 5's verdict flagged `c387f55`, `b9961a1` and `43eb030` as still unsigned,
carried from an earlier round's ledger entry ("rebase-to-sign before the `cpp-backend`
merge"). `git log --format='%h %G? %s' -8` on this branch now shows every commit `G` (good
EDDSA signature) — the hashes changed (re-signing rewrites them), matching
`COORDINATION.md`'s note that "the branches were re-signed since, with identical trees."
Nothing to do this round.

## Convergence round 5: decision 4b, views are second-class (30-second-class.md)

### The three open significant issues (converge-result.json) are dissolved, not patched

**What.** `wave2/converge-result.json`'s three "significant" findings for this package were
all shapes `carriesPointer` missed: a collection of pointers (`List<CStr>` result, a `mut
List<CStr>` parameter — issue 1), the pointee of a bare `mut p: Unsafe<T>` out-buffer when
`T` itself carries a pointer (issue 2), and an `Fx` result or callback parameter that
returns/receives a pointer (issue 3). Each earlier round's fix to `carriesPointer` closed
the shapes the previous round's verifier found and missed the next ones (a `Maybe`, then a
class field, then `View`/`MutView`, then a `mut` argument or receiver, and now these three) —
the same open-ended list the design's own retrospective names in 30-second-class.md section
7.4: "chasing an ever-growing list of shapes."

**Where.** `CppExternEmitter.kt`'s `carriesPointer`, `call`'s `pointerEscapes` computation,
`receiverType`, `isTemporaryStr` and `refuseDanglingStr` (round 4's shapes, ~110 lines total,
per 30-second-class.md 7.4's line count).

**Status: resolved this round by deletion, per design 7.4.** All five are deleted outright,
not patched to cover the three new shapes. Decision 4b's rule (30-second-class.md) refuses
an extern's result, `mut` argument or receiver from ever being second-class *at the
declaration* (`rules.view.extern`, `rules.view.type` — a `List<CStr>` parameter or result
is `rules.view.type` on the `CStr` type argument alone, 1.2; an `Fx<..., CStr>` result or
callback parameter is `rules.view.extern`, 5.2), owned by `w2-5-rules`'s new `ViewPass`, not
by this emitter. Once that declaration-time refusal exists, nothing this emitter's
`argument`/`call` could see at a *call* site was ever reachable in a well-formed program, so
the heuristic this package kept re-patching is unneeded rather than merely incomplete: there
is no shape left for it to miss, because the shapes it existed to catch cannot be declared.
This closes all three open issues without adding a fourth patch to the list.

**Merge-order note (30-second-class.md 7.0).** In this branch alone (without `w2-5-rules`'s
`ViewPass` merged), the three shapes above are — same as before this round, and as every
shape `carriesPointer` never covered — simply unchecked: a `List<CStr>`-returning extern and
a computed `Str` argument feeding it compile with no diagnostic on this branch standalone.
This is expected, not a regression this round introduces: the design's merge order (7.0)
already requires `ViewPass` to land at `cpp-backend` before this package's deletions do, so
no build ever compiles the unsafe shape unchecked. `./gradlew test` (994 tests) and every
acceptance command in the brief pass on this branch alone regardless, since none of them
constructs these three shapes.

### New: `Unsafe<T>` takes a `View<T>`/`MutView<T>` argument at an extern call, lowered `.data()`

**What.** Design 1.4/5.5: `Unsafe<T>` is now extern-parameter-only (nothing else can declare
one, once `w2-5-rules`'s `ViewPass` lands `rules.view.unsafe`), so the only way left to call
something like `ImGui::InputText(label, char* buf, size)` is to pass a `View<T>` (a `const
T*`) or, for a `mut Unsafe<T>` (`T*` by value, not an out-parameter, table 5.1), a
`MutView<T>` — nothing else can ever hold an `Unsafe<T>` value to pass along unchanged.

**Where.** `CallResolver.kt`'s `typeGiven` (both the byRef and non-byRef argument branches),
and `CppExternEmitter.kt`'s `argument`.

**Status: added this round.** `typeGiven` accepts a `View<T>`/`MutView<T>` argument against
an `Unsafe<T>` parameter (non-`mut`: either; `mut`: `MutView<T>` only, matching element type,
and needs no call-site `mut` and no place, since the callee only reads the view's own
pointer) at an extern call only (`externCallee`), recording nothing beyond the argument's
own `View`/`MutView` type — the same convention the `CStr`-for-`Str` case just above it
already uses, so no new `Coercion` case is needed. `CppExternEmitter.argument` reads that
type back and lowers `(text).data()`; the exact-type case (an `Unsafe<T>` argument that
already is one) is untouched and still passes through as itself.
`CppExternEmitterTest.anUnsafeParameterTakesAViewOrAMutViewLoweredDotData` covers both.

**Touches `CallResolver.kt`, outside this package's OWNS/TOUCHES.** Same disclosure as the
round-2 entry below for this file: the change (18 lines) is needed for design 1.4/5.5's
`Unsafe<T>` calling convention, which 30-second-class.md 7.4 explicitly assigns to this
package ("CallResolver coercion at extern calls only, plus the lowering"). `./gradlew test`
(994 tests, 0 failures) passes with it in place; reconciling it with any independent change
another package makes to the same file is `cpp-backend`'s merge step, not a mid-round revert
here.

### `CppExternEmitterTest`'s round-3/4 refusal tests move to `ViewPassTest` (design 7.4)

**What.** Design 7.4: "the 3 round-4 tests and the round-3 refusal test become `ViewPassTest`
negatives on the declarations... their non-regression halves... stay." The four tests that
asserted `carriesPointer`/`refuseDanglingStr` refused a computed `Str` argument no longer
have anything to assert, since the check is deleted (`argument`'s doc, above).

**Where.** `CppExternEmitterTest.kt`.

**Status: done this round.**
`aComputedStrIntoAPointerCarryingResultIsRefusedNotALiteralOrANamedOne`,
`aLiteralOrAKiraStrConstantIntoAStrParameterOfAPointerCarryingResultIsRefusedToo` and
`aComputedStrEscapingThroughAMutParameterOrAMutMethodReceiverIsRefused` are deleted except
for their non-regression halves (a literal, a named `Str`, a Kira `Str` constant into a
`CStr` parameter; a literal into a scalar-returning call; a bare `mut Unsafe<T>` out-buffer
alongside a computed `Str`), consolidated into
`aLiteralANamedStrOrAConstantIntoACStrParameterIsNeverRefused` and
`aMutUnsafeOutBufferBesideAComputedStrArgumentIsNeverRefused`.
`aComputedStrIntoAViewOrMutViewResultIsRefused` had no non-regression half (both of its
cases were refused shapes) and is deleted outright; its two probes are now the
`w2-5-rules` verifier's `ViewPassTest` negatives (not this package's file). Net: 21 → 20
tests in this file (4 removed, 3 added — the third being
`anUnsafeParameterTakesAViewOrAMutViewLoweredDotData`, above); the full suite is 994 tests
(was 995), 0 failures.

## Open decisions

### The FFI contract only proves the buffer safe to the end of the full-expression, never beyond what the typed model can see

**Status: closed this round.** 30-second-class.md section 5.4 states the contract this
entry asked the user to pick a direction on, as three explicit rules rather than an
annotation mechanism: (1) an extern reads a second-class argument only during the call and
keeps no pointer from it — a C++ callee that retains one (an opaque handle storing a
`string_view`, a global setter, the reassigned-`Str` case below) is a seam bug the typed
model cannot see, full stop, not something Kira refuses; (2) an extern writes Kira storage
only through its `mut` arguments and its receiver; (3) an extern runs Kira code only
through the `Fx` arguments it is given. This is choice (a) from the two this entry
originally posed — the trust-boundary reading, not a `@_retains` annotation — decided by
the design rather than left open. Nothing in this package's code changes for it beyond the
deletions above, which already assume exactly this contract (`call`'s updated doc cites
5.2/5.3 directly). The original text is kept below for the record of what was open and why.

**What.** Round 3 and round 4's refusals (`CppExternEmitter.argument`, `carriesPointer`)
close every escape this package's typed model can trace: the call's own result, a `mut`
parameter, and a `mut` method's receiver. Design 7.2 promises a computed `Str`'s buffer only
"to the end of the full-expression," and nothing refuses a C++ callee that squirrels a
pointer to it away somewhere that promise does not reach and the typed model cannot see:
an `@_opaque` handle or a `kira::Rc<C>` result whose C++ object stores a `string_view` or a
`const char*` into what it was constructed from; a plain C++ global a setter-shaped `Void`
function writes into (ImGui's `io.IniFilename` is the standing example); or a `CStr` read
from a named `Str` that is later reassigned before the pointer is used (a `mut` global a
sibling call changes in between). None of these are call-shaped in a way `carriesPointer`
can see them through — the pointer leaves via C++ side effects the Kira type signature does
not name.

**Where.** `CppExternEmitter.kt`'s `carriesPointer`/`argument`, and design 7.2 (the buffer
lifetime promise).

**Why this is a decision, not a bug to fix here.** Proving either of these safe or unsafe
needs information the FFI's typed-signature contract does not carry (whether a C++ callee
retains a pointer past the call, which C++ has no way to declare short of reading its
source or hand-annotating every extern). Two ways forward: (a) leave the contract as "safe
to the end of the full-expression, and no further" and document the gap explicitly (an
`@_extern` author's responsibility to avoid these shapes, the same trust boundary the rest
of 7.2 already places on `header =`), or (b) add an annotation (`@_retains` or similar) an
extern author states when a parameter's buffer is retained, and refuse a computed `Str`
into any such parameter unconditionally regardless of the result shape. Nothing in this
package's brief asks for (b), and no in-tree example needs it; recorded here for the user
to pick a direction before an extern binding relies on it.

## Convergence round 4

### Issue 1 (fixed, not deferred): a literal or a Kira `Str` constant into a `Str` parameter of a pointer-carrying result was a heap-use-after-free

**What.** `isTemporaryStr` returned `false` for a `StringLiteral` and for a named,
non-extern `Str` global (which includes a Kira `Str` constant), on the theory that neither
builds a temporary. That is only true on the `CStr`-parameter path (`argument`'s own `when`
there passes a literal and a Kira constant through as the `const char*` they already are,
with no wrapper). On the `Str`-parameter path, every argument is wrapped in
`kira::ffi::in(text)`, whose parameter is `const std::string&`; a literal (a C string
literal) and a Kira `Str` constant (D12: `inline constexpr const char*`) are both
`const char*` there, and binding that to `in`'s reference parameter converts through a
fresh `std::string` temporary exactly as a computed expression would, dying at the same
full-expression's end. Verifier's reproduction (trial `27a2876` + W2.3 `7c0fb42`):
`afterS("hello, world, a literal long enough to live on the heap and not in the small
buffer", 44)` and `afterS(GREETING, 44)` (a `pub GREETING: Str` constant) both read 0 where
77/78 are correct on g++ and clang, and MSVC ASan reported a heap-use-after-free freed by
`~basic_string` in the same statement for each.

**Where.** `CppExternEmitter.kt`'s `isTemporaryStr` (previously lines 500-507).

**Status: fixed this round.** `isTemporaryStr` now returns `true` for a `StringLiteral` and
for a named `Str` global that is `externOf(sym) != null || sym.isConstant` (an extern
constant's read, already refused, or a Kira constant, newly refused); only a local, or a
plain non-constant global `Str` — already an lvalue of `in`'s own parameter type — reads
existing storage with no temporary built. `CppExternEmitterTest.
aLiteralOrAKiraStrConstantIntoAStrParameterOfAPointerCarryingResultIsRefusedToo` covers the
literal and the constant into `afterS`, plus a literal into a scalar-returning call staying
unrefused (the temporary is never read past the full-expression there).

### Issue 2 (fixed, not deferred): a pointer could also escape through a `mut` parameter or a `mut` method's receiver, uncaught by a return-type-only check

**What.** `call` computed `resultCarriesPointer` from `call.returnType` alone.
`kira::ffi::out(x)` lets an extern callee write a *new* value into any `mut` parameter, and
a `mut fx`'s receiver is written the same way; either is exactly as much an output channel
as the return value, and a computed `Str` argument feeding a call that writes a
pointer-carrying value through one of those dangled the same way (verifier's reproduction:
`afterInto: (s: Str, c: Int32, mut out: Maybe<CStr>) Void` called `afterInto(a + b, 44, mut
e)`; `setName: (mut o: Opts, s: CStr) Void` on a struct `Opts { name: Maybe<CStr>, n:
Int32 }` called `setName(mut o, a + b)`; `rename: (s: CStr) Void`, a `mut fx` on `Opts`,
called `o.rename(a + b)` — all compiled with no diagnostic, g++ read 0/0/6 and 6 where
74/74/80 and 80 are correct, and MSVC ASan reported a heap-use-after-free for each).

**Where.** `CppExternEmitter.kt`'s `call` (the `resultCarriesPointer` computation) and
`carriesPointer`.

**Status: fixed this round.** `call` now ORs three channels into one `pointerEscapes` flag:
`carriesPointer(call.returnType)`, any `byRef` parameter whose type `carriesPointer` and
which is not a bare `Unsafe<T>` (a `mut p: Unsafe<T>` is the caller's own buffer passed in
to be filled, not a value the callee produces — table 5.1 — so it alone never triggers the
refusal), and, for a `mut fx` call, the receiver's type (`call.receiver`'s typed-model type,
or the owning class when called through an implicit `this`). `CppExternEmitterTest.
aComputedStrEscapingThroughAMutParameterOrAMutMethodReceiverIsRefused` covers all three
escapes from the reproduction, plus a `mut Unsafe<T>` out-buffer alongside a computed `Str`
staying unrefused.

### Issue 3 (fixed, not deferred): `View<T>`/`MutView<T>` were not pointer-carrying types

**What.** `carriesPointer` covered `CStr`, `Unsafe<T>`, `Maybe<X>` and a struct field, but
not `View<T>`/`MutView<T>` — `kira::View`/`kira::MutView` is a borrowed ptr+len over
someone else's storage (core.hxx) with no fields of its own the recursive struct check
could see. A computed `Str` argument feeding a call whose result is a view was therefore
never refused (verifier's reproduction: a C++ seam `kira::View<char> tailOf(const
std::string&, std::int32_t)` declared `tailOf: (s: Str, at: Int32) View<Char>`, called
`t: View<Char> = tailOf(a + b, 7); trace(t[0])`; g++ and clang printed `' '` where `'w'` is
correct, and MSVC ASan reported a heap-use-after-free).

**Where.** `CppExternEmitter.kt`'s `carriesPointer` (previously lines 362-376).

**Status: fixed this round.** `carriesPointer` now returns `true` directly for `View<T>`
and `MutView<T>`, alongside `CStr` and `Unsafe<T>`. `CppExternEmitterTest.
aComputedStrIntoAViewOrMutViewResultIsRefused` covers both `View<Char>` and `MutView<Char>`
results.

### The pending-on-W2.3 entry did not name `13-ffi-cpp`

**Resolved in round 4.** With W2.3 merged into this branch, `examples/cpp/13-ffi-cpp/run.sh`
prints `13-ffi-cpp: ok` on the branch itself (measured), and regenerate.sh's C++ leg runs 2 of 2.

### The round report's CppExternEmitterTest count was off by one

**What.** An earlier round's report said `CppExternEmitterTest` has 19 tests;
`TEST-...CppExternEmitterTest.xml` lists 18 for that round (17 earlier tests plus
`aComputedStrIntoAPointerCarryingResultIsRefusedNotALiteralOrANamedOne`, which does run and
pass). Only the report's count was wrong; the test itself ran, passed, and is unaffected by
this round's fixes (still green, verified in this round's full run).

**Where.** The round-3 report text (not a file in this repo); `build/test-results/test/
TEST-net.exoad.kira.cpp.ffi.CppExternEmitterTest.xml`.

**Why it is safe to defer.** A reporting slip, not a code defect; the ledger records it so
a future count mismatch is not mistaken for a missing test.

### The round-3 regression test's `r: CStr = strip(nameOf(1))` local models a program the CLI frontend rejects

**What.** `aComputedStrIntoAPointerCarryingResultIsRefusedNotALiteralOrANamedOne`'s
`computedIntoCStrResult` case declares a bare `r: CStr = strip(nameOf(1))` local. The CLI
frontend rejects that shape today ("The type CStr was not found at this scope"), while
`Maybe<CStr>` locals and fields are accepted (`diagsOf` builds the `ResolvedCall` directly
through `CppExternEmitter.call`, bypassing the frontend's own type-resolution pass, so the
test still exercises the call-emission path it names even though the CLI could not compile
the program it is modeled on). `diagsOf` filters to "a computed Str argument" messages, so
the mismatch is silent unless someone tries to compile the literal source through the CLI.

**Where.** `src/test/kotlin/net/exoad/kira/cpp/ffi/CppExternEmitterTest.kt`,
`aComputedStrIntoAPointerCarryingResultIsRefusedNotALiteralOrANamedOne`'s
`computedIntoCStrResult` case.

**Why it is safe to defer.** The test's assertion is still true of what it actually
exercises (`CppExternEmitter.call`'s refusal), and no acceptance path routes this exact
source text through the full CLI. The inconsistency this surfaces — a bare `CStr` type
annotation rejected at local/field scope while `Maybe<CStr>` is accepted — is itself worth
a separate look (immediately below), since it is plausibly a scoping gap in the type
resolver rather than an intentional restriction (design 7.2 does not say a bare `CStr`
local should be refused).

### A bare `CStr` local or field annotation is rejected; `Maybe<CStr>` is accepted

**What.** `r: CStr = strip(nameOf(1))` as a local declaration is rejected by the CLI
frontend with "The type CStr was not found at this scope," while the identical shape under
`Maybe<CStr>` (`r: Maybe<CStr> = after(nameOf(1), 1)`, used throughout this file's tests) is
accepted. `CStr` is otherwise an ordinary magic type (a parameter, a return type, a struct
field), so a bare local/field annotation being the one shape the resolver does not find is
inconsistent on its face.

**Where.** Whatever scope-resolution pass rejects a bare `CStr` local/field annotation
(not this package's `OWNS`/`TOUCHES` files — not investigated further this round).

**Why it is safe to defer.** No acceptance command in this package's brief, and no example
in the tree, declares a bare `CStr` local or field; every in-tree use of `CStr` at that
position goes through `Maybe<CStr>` already. Fixing the resolver gap (if it is one) is
outside this package's own files and does not block anything this round needs.

## Convergence round 3

### A computed Str argument to a pointer-carrying extern result was a heap-use-after-free (fixed, not deferred)

**What.** `CppExternEmitter.argument` passed a computed `Str` (neither a literal, a
constant, nor a named `Str`) to a `CStr` parameter as `kira::ffi::CStrBuf(expr).c_str()`,
and to a `Str` parameter as `kira::ffi::in(expr)`. Both build a temporary that lives only to
the end of the call's own full-expression. When the extern function's result is a `CStr`,
an `Unsafe<T>`, or a `Maybe` or struct holding one, nothing proved the result did not still
point into that buffer once it was gone — and once read on a later statement (a stored
result), or once W2.3's hoister spills the call into its own statement for sitting beside an
impure sibling, it already did (verifier's reproduction, trial CLI `526467b` + W2.3
`7c0fb42`: `trace(lenM(after(a + b, 44), next()))` printed 1 on g++ and 75 on clang, where
75 is correct, and MSVC ASan reported a heap-use-after-free freed by `~CStrBuf`; the same
through a `Str` parameter (`in(a + b)`) and through a stored `m: Maybe<CStr> = afterS(a + b,
44)`).

**Where.** `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/cpp/CppExternEmitter.kt`:
`call` now computes whether the callee's result `carriesPointer` (a `CStr`, an `Unsafe<T>`
directly, a `Maybe<X>` recursively, or a class/struct with such a field, recursively) and
passes it to `argument`, which refuses (via `refuseDanglingStr`, `ctx.diag`,
`cpp.unsupported`) a computed `Str` argument into such a call instead of building the
temporary. `kira/cpp/tests/ffi_test.cxx` and `kira/ffi.hxx` are unchanged: the fix is
entirely at the Kira-compiler level, so the C++ side never sees the unsafe code at all.

**Status: fixed this round, not deferred (policy 1).** Per the convergence policy's choice
(a): the construct is refused with a diagnostic naming the callee and telling the caller to
name the `Str` first (`s: Str = a + b`, then pass `s`), rather than attempting a
lifetime-extension fix in `kira/ffi.hxx` or coordinating with W2.3's hoister — a spill is
not the only way the buffer's lifetime is too short (the stored-result case above needs no
hoisting at all), so narrowing what compiles is the only fix that does not miss a case.
`CppExternEmitterTest.aComputedStrIntoAPointerCarryingResultIsRefusedNotALiteralOrANamedOne`
covers a computed argument to a `CStr` parameter, to a `Str` parameter, and to a `CStr`
result directly (`strip(nameOf(1))`), and confirms a literal, a named `Str`, and a Kira
`Str` constant into the same callees are not refused.

### `ffi_test.cxx`'s `declared<T>(fake::Holder::N)` comment names the wrong ambiguity

**What.** The comment block above the file-scope `static_assert` on
`declared<std::int32_t>(fake::Holder::N)` describes the array/function ambiguity (round 2's
fix), not `Holder::N`'s own in-class `static const int` case (round 5's fix, re-tested in
round 2). That `static_assert` sits in an unevaluated `decltype`, so it cannot exercise the
odr-use the comment's neighboring test is named for; only `check()` in `main()` does, which
does catch it (verified: the link fails when the by-value `declared<T>` overload is
removed).

**Where.** `kira/cpp/tests/ffi_test.cxx`, the comment above the `Holder::N` `static_assert`.

**Why it is safe to defer.** The test itself is correct and exercises what it should
(`run.sh` and `msvc.bat` both build and run it, and removing the by-value overload breaks
the link as expected, measured this round); only the prose above it points at the wrong one
of the two ambiguities this file's tests cover. A comment fix, not a behavior fix.

### This ledger's own title was stale by two rounds

**What.** The title said "(convergence round 1)" while the file already held a "Convergence
round 2" section (and, before this fix, would have held a "Convergence round 3" section
under a "round 1" title). Fixed in this same edit: the title above now reads "round 3".

**Where.** The top of this file.

**Why it is safe to defer (moot).** Not deferred — corrected in this round's own edit to
this file, alongside the entries above.

### Cross-package note for W2.3: `CppHoister.copied`'s `const const` spelling must not be fixed before the buffer/lifetime issue above

**What.** `CppHoister.copied` (line 266 on the verifier's trial) writes `const ` before any
spelled type, so a hoisted `CStr` or `Unsafe<T>` temporary is spelled `const const char*
t0_`, which g++, clang and MSVC `/WX` all reject (MSVC: C4114). Fixing that spelling alone,
with nothing else changed, turns `useC(strip(a + b), next())` into a heap-use-after-free
(measured with MSVC ASan on the verifier's trial) — the same mechanism the fix above closes,
but from the hoister's side, which this package does not own.

**Where.** W2.3's `CppHoister.kt` (not this package's file; recorded here because it
currently masks this round's significant issue and interacts with the fix above).

**Why it is safe to defer.** It is W2.3's own bug to fix on its own branch, not something
this package's `OWNS`/`TOUCHES` list reaches. It is flagged here, rather than left silent,
because once W2.3 fixes the spelling, `strip(a + b)`-shaped calls will compile where they
did not before — and this round's fix (refusing a computed `Str` into a pointer-carrying
extern result at the Kira-compiler level, independent of whatever C++ the hoister emits)
already covers that case regardless of which order the two branches merge in, so no
re-check is needed at `cpp-backend`'s merge step beyond confirming this package's own tests
(they do: `strip(nameOf(1))` is refused today, ahead of the hoister fix landing anywhere).

### The W2.3 merge-conflict trial is current against `7c0fb42`, not `41575c6`

**What.** The previous round's note below this one named W2.3's head as `41575c6` and its
own `resolve.py` as the working script. `cpp/w2-3-emit-exprs` has since moved to `7c0fb42`.
The verifier's trial this round (`526467b` + `7c0fb42` + `vc1/resolve.py` + the four
delegation hooks applied by hand from `vc2ffi/hooks.py`, since `v26r5-fix/deleg.patch` no
longer applies — `CppExprEmitter.kt` line 479 now reads `functionValue(sym, at)` where the
patch expects `ctx.qualified(sym)`) still gives a clean result: `cpp.*` 537/0/0, forward
`emit: required` byte for byte, `ExternDelegationTest` 2/0 unskipped. `CppDeclEmitter.kt`'s
conflict still resolves with `vc1/resolve.py` unchanged.

**Where.** `scratchpad/vc2ffi/hooks.py` (this session's copy of the four hand-applied
hooks, replacing the stale `v26r5-fix/deleg.patch`); `scratchpad/vc1/resolve.py` (unchanged,
matches any `>>>>>>> origin/<branch>` marker rather than a literal branch name, so it keeps
working as W2.3's head moves).

**Why it is safe to defer.** Same reasoning as every round's version of this note: a
merge-order fact between two sibling branches, not a defect in this package standalone;
this package's own `cpp-backend` ancestor check passes today with no merge needed
(`cpp-backend` `09ad744` is already an ancestor of this branch's head). Recorded so the next
round's trial merge does not have to re-discover which head and which patch/hooks still
work.

## Convergence round 2

### Three earlier commits on this branch are unsigned

**What.** `c387f55` (FFI fix round 5), `b9961a1` (FFI fix round 4) and `43eb030` are `%G? N`
(no GPG signature), against the user's rule that every commit is GPG-signed. The last
verdict noted this but the ledger had no entry for it.

**Where.** `git log --format='%h %G? %an <%ae> %s' cpp-backend..HEAD` on `cpp/w2-6-ffi`.

**Why it is safe to defer.** The branch is not pushed and `cpp-backend` has not merged it
yet, so the three commits can still be re-signed in place before that merge, for example
with `git rebase cpp-backend --exec 'git commit --amend --no-edit -S'` run on this branch
(every later commit on the branch, this round's included, is already signed, so the rebase
touches only those three). Doing it mid-round would rewrite commits an earlier verdict
already reviewed; it belongs right before the `cpp-backend` merge, once no more rounds are
expected to add commits underneath it.

### The forward golden, ExternDelegationTest's body check, and `13-ffi-cpp` were `pending` on W2.3

**Resolved in round 4.** W2.3 is merged into this branch: forward is `emit: required` and emits
byte for byte (CppGoldenEmitTest; goldens.sh forward 6/0 on gcc, clang and msvc),
`ExternDelegationTest` runs 2/0 with the body check, and `13-ffi-cpp: ok`.

### The W2.3 merge-conflict entry named a moved head; the resolution still applies

**What.** The existing note below this one, "The w2-3-emit-exprs merge conflict predates
this round," names W2.3's head as `42f1b7f`. `cpp/w2-3-emit-exprs` has since moved to
`41575c6`. The conflict shape is unchanged: `git merge-tree` against the new head still
conflicts only in `CppDeclEmitter.kt`, `w2-4-emit-oop` (`45675ea`), `w2-5-rules`
(`cd1b4a1`) and `w2-7-sys` (`4aafc5a`) still merge clean, and the same resolution (keep this
branch's `extern "C"` include block, spell each include with W2.3's `includeLine(it)`) plus
this package's `v26r5-fix/deleg.patch` still applies with no conflict, measured with a real
trial merge this round (`./gradlew installDist`; `cpp.*` 33 XML files, 532 tests, 0 skipped,
0 failures; `13-ffi-cpp/run.sh` ok).

**Where.** `scratchpad/vc1/resolve.py` (this session's copy) matches any
`>>>>>>> origin/<branch>` marker, not a literal `w2-3-emit-exprs` string, so it keeps
working as W2.3's head moves; `v26r5-fix/fix_trial.py`, the older script the previous round
left, is pinned to the literal marker text and no longer matches after the head moved.

**Why it is safe to defer.** Same reasoning as the entry below: a merge-order fact between
two sibling branches, not a defect in this package standalone. Recorded here so the next
round's trial merge does not have to re-discover which head and which script still work.

### The issue-3 fix (a scalar-by-value `declared<T>`) had no regression test until now

**What.** Round 5's fix — `declared<T>` takes a scalar `V` by value so the
lvalue-to-rvalue conversion happens at the call, letting an in-class
`static const int N = 4;` with no out-of-class definition link at `-O0` — had no test
pinning it; `ffi_test.cxx` only read `constexpr` constants, which link at every
optimization level regardless.

**Where.** `kira/cpp/tests/ffi_test.cxx`.

**Status: fixed this round, not deferred.** `fake::Holder::N` (an in-class
`static const int N = 4;`, no out-of-class definition) is now read through
`kira::ffi::declared<std::int32_t>(fake::Holder::N)`, both as a compile-time
`static_assert` and as a `check()` in `main()`, which `kira/cpp/tests/run.sh` and
`msvc.bat` build and run at `-O0`. Left here as a record of what closed the gap, since the
same commit also fixes the round's one significant issue (`declared<T>`'s overload
ambiguity) and both are covered by the same test additions.

### This branch still edits `CallResolver.kt`, outside its OWNS/TOUCHES list

**What.** The branch's diff against `cpp-backend` still includes
`src/main/kotlin/net/exoad/kira/compiler/analysis/types/CallResolver.kt` (25 lines), a file
this package's brief does not list under OWNS or TOUCHES (it is W2.1's file). The last
verdict disclosed this; the ledger had no entry for it.

**Where.** `git diff cpp-backend...HEAD --stat -- '*CallResolver*'`.

**Why it is safe to defer.** `CallResolver.kt`'s change is needed for this package's own
extern-constant and extern-field call resolution to typecheck at all (matching a call whose
argument or result the FFI checks convert); every acceptance command in this package's
brief, and the full `./gradlew test`, pass with it in place. It is flagged here rather than
reverted because splitting it out risks breaking W2.1's own branch state if that package
has moved the same code independently; `cpp-backend`'s merge step is the place to reconcile
the two, not a mid-round revert in this package.

## A same-size twin field write is a C++ error, not Kira's message

**What.** Writing to an extern struct field whose C++ type is a same-size twin of the
declared one (a C `int` field on arm-none-eabi, an unscoped enum field), or passing such a
field as a `mut` argument, fails with a plain C++ "lvalue required as left operand of
assignment" (or MSVC's equivalent), not `"Kira's X.field no longer matches its C++
header"`. A field whose C++ type is the declared one writes as it always has (the member
itself, an lvalue).

**Where.** `kira/cpp/kira/ffi.hxx`'s `field<T, M>`: it returns the member itself only when
the C++ type equals `T`; when it is a twin, `field<T>(...)` is `static_cast<T>(...)`, a
prvalue, and prvalues do not bind to `T&` or pass through `kira::ffi::out(...)`.

**Reproduction.** A C `enum Mode { OFF, ON }; struct Cfg { Mode mode; };` declared in Kira
as `mode: Int32` (an extern enum is not a Kira declaration, so `Int32` is the only spelling);
`cfg.mode = 1` or `setMode(mut m: Int32)` called as `setMode(cfg.mode)` fails to compile,
since `kira::ffi::field<std::int32_t>(cfg.mode)` is a prvalue there.

**Why it is safe to defer.** This is consistent with design 7.2's rule that Kira never
takes `int&` to such a field (an enum could never take the integer back, so the write must
go through an extern function that assigns the enum from the integer, or Kira must read the
field, compute, and pass the result as a by-value argument instead of a `mut` one). The
failure is a compiler error at the point of use, not a silent miscompile: it names the
member and the `assignment`/`out<T>` proxy in the error, so a program that tries this does
not build, and building is required before it can run. No test in the suite writes such a
field. Fix, if wanted: a two-argument `field<T>` write path through an extern setter,
generated from the same struct declaration, is future work; nothing in the FFI rule
requires it now, since no in-tree program does this.

## gcc 11.4, the design's floor, is unmeasured this round

**What.** The design's minimum compiler (design 7, floor gcc 11.4) has not compiled this
round's `kira/ffi.hxx` changes (the round-5 `declared<T>`/`field<T>` split by
`std::is_scalar_v`, and this round's rvalue-aware `field<T, M&&>`).

**Where.** `kira/ffi.hxx` targets C++17/20 features already in use elsewhere in the file
(`if constexpr`, `decltype(auto)`, `std::optional`, a `__cpp_char8_t` guard); nothing added
this round is later than that, but it is asserted, not measured, on gcc 11.

**Reproduction.** N/A — there is no gcc 11 toolchain on this machine, and
`docker-desktop` (the usual way to reach one) was stopped when this was checked.

**Why it is safe to defer.** Every change in `field<T, M>` and `declared<T, V>` is ordinary
SFINAE and reference-collapsing surface, no different in kind from what gcc 11.4 already
builds elsewhere in this file (the `Matches`/`SigMatches` machinery, `if constexpr` chains).
gcc 13.2, zig clang 20 (≈ clang 20) and MSVC all build and run it (`kira/cpp/tests/run.sh`,
`msvc.bat`). Measuring gcc 11.4 specifically is worth doing before this ships to the Pi's
toolchain, not before this package converges.

## declared<T> costs one move for a class-typed result already of the declared type

**What.** `kira::ffi::declared<T>(call())` is `Declared<T>::from(static_cast<V&&>(v))`,
which for a class-typed `T` that already has the C++ type `T` (the check let it through
unconverted) is a move-construction the round-4 lowering did not pay (a prvalue of type `T`
was returned as-is, eliding the conversion).

**Where.** `kira/ffi.hxx`'s `declared<T, V>` (both overloads).

**Why it is safe to defer.** One move of a small handle type (an `Rc`, a struct with a
couple of scalar members) is not a correctness issue, and the alternative (special-casing
`T == V` to skip the wrapper) reintroduces the `is_convertible`-shaped hole the whole
`declared<T>` design closes for every other `T`. Scalars and pointers, the common case,
cost nothing (the cast is a no-op or a register move either way).

## The w2-3-emit-exprs merge conflict predates this round

**What.** `git merge-tree --write-tree --name-only cpp/w2-6-ffi cpp/w2-3-emit-exprs` (head
42f1b7f) conflicts in `CppDeclEmitter.kt`: this branch's `extern "C"` include block (rounds
1-4) against W2.3's `includeLine`. `cpp/w2-4-emit-oop`, `cpp/w2-5-rules` and `cpp/w2-7-sys`
merge cleanly.

**Where.** `src/main/kotlin/net/exoad/kira/compiler/backend/codegen/cpp/CppDeclEmitter.kt`
(W2.2's file; this branch's earlier rounds and W2.3 both touch the header's include list).

**Why it is safe to defer.** It is a merge-order conflict between two sibling packages'
branches, not a defect in either one's code on its own branch: this package's own
`./gradlew test` and every acceptance command pass standalone. Resolution (keep this
branch's `extern "C"` block, write each include with W2.3's `includeLine(it)`) is for
`cpp-backend`'s merge step, and was already trialled clean against W2.3 42f1b7f plus that
one resolution.
