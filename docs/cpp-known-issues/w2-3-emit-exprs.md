# Known issues: w2-3-emit-exprs

The C++ backend's expression, statement, closure and binding lowering: `CppExprEmitter.kt`,
`CppStmtEmitter.kt`, `CppClosureEmitter.kt`, `CppHoister.kt` (with `CppEscapes`, now only the
`Fx` escape fallback), `CppCopyPolicy.kt` (round 4) and `CppBindingTable.kt`, the tests under
`src/test/kotlin/net/exoad/kira/cpp/exprs/` and the `evalorder` golden. Each entry says what the
issue is, where it lives, how to reproduce it, and why it is safe to leave for now. "Measured"
means built with the goldens' warning flags and `-Werror` on g++ 13.2, zig c++ (clang) and MSVC
`/W4 /WX`, and run.

## Copy by default, round 5: an assignment's value is a use

This round merges `cpp/w2-5-rules` at 18d3f42 (round 5's CONFINED; merge 6ec4252, signed, no
conflict), then fixes round 4's two significant w2-3 findings (sc-round4.json) at one root.

- **The finding.** `CppExprEmitter.assignment` built its value with `operandOf` and no `Use`, so
  `CppCopyPolicy.pass` never saw it. C++'s `operator=(const T&)` binds a reference to the value
  and writes the target while it reads it, so a value inside the target is freed or overwritten
  as it is read. 50-round4 section 1 has no row for this lending point (1.1-1.8), and 7.2's
  generator had no such use. #0 is `t = t.kids[0]` (a3b: gcc exited 3, MSVC ASan reported a
  container-overflow). #1 is `a[i] = v` with `v` under the receiver's root (c2 `t.kids[0] = t`:
  all three compilers printed `1 one 1 1 2` for Kira's `1 one 1 2 2`). Both are this path, since
  `a[i] = v` is an assignment to the place `kira::at(a, i)`, the same text as `List.set`'s binding.
- **The fix.** The value is a `Use`. The assignment is its consumer: never CONFINED, with its
  target as its own `mut` operand, so the target's root is NAMED. The value is therefore lent only
  as a prvalue (W1) or as a PRIVATE place under another root (W2). Anything else is `T(e)`, copied
  before the target is written: `t = Tree(kira::at(t.kids, 0));` and `kira::at(t.kids, 0) = Tree(t);`.
  `t = u` with `u` a local or a by-value parameter stays `t = u;`.
- **One more thing the fix needed: `WrapSome` is no temporary for an assignment**
  (`CppHoister.Use.direct`). `kira::Maybe` is `std::optional`, and its `operator=(U&&)` assigns
  the payload from the unconverted reference. So c20's `m = m.unwrap().kids[0]`, which counted as
  W1 through `CppCopyPolicy.converts`, still gave a container-overflow. It is now
  `m = Tree(kira::at(kira::unwrap(m).kids, 0));`. `Upcast` stays W1, because `std::shared_ptr`'s
  converting assignment is specified as `shared_ptr(r).swap(*this)`: it makes its own copy first.
- **The binding form is covered already.** `t.kids.set(0, t)`, `ts.set(0, ts[0].kids[0])`,
  `arr.set(...)`, `mv.set(0, mv[0].kids[0])` and `gl.set(0, gl[0].kids[0])` are refused by D37's
  `rules.exclusivity.receiver` (probe `w23r5/p/b1`: 5 refusals). A binding writer whose value
  shares no root with its receiver is decided by W3's `mayHold` test against the `mut fx`
  receiver. `r1.value.kids.set(0, r2.value)` (two names of one `Ref`) emits `Tree(r2->value)` and
  prints Kira's `1 one 1 2 2` (probe `b2`).
- **The golden this changes.** proto gains three wrappers: `out.topic =
  kira::Str(kira::str::substring(...))` and `out = kira::Str(kira::str::substring(...))` (binding
  prvalues, free: KI-19), and one real copy, `out.rest = kira::Str(out.line)`. That is a copy
  because `out.line`'s root is the target's root, and the rule is decided by root. It runs once
  per parse, outside any loop. Every other `expected/` tree that emits on this branch is byte
  identical (decls, evalorder, hall, macros, numerics, strings, sysdecls, text, unilidar,
  modules, sys; evalorder's `expected.txt` too). The class goldens do not emit here (KI-18). Their
  `expected/` assignments are all prvalues or scalars (`why = kira::cat(...)`,
  `c->value = c->value + 1`, `lastMs = now`), so the trial should keep them byte-identical.
- **Round 5's rule M in this package's test.** `CppCopyPolicyTest`'s `arm` added hooks to the
  globals `gfs` and `hooks` directly, and 2.3's row 1 now refuses that (W2.5 round 5, 4 test
  cases). It now builds each list in a local and stores it back (`gfs = fs`, `hooks = hs`), as
  the refusal message says to. No row's text or value changes.
- **Measured** (scratchpad `w23r5`, this branch's CLI, jar md5 c357f0d5; g++ 13.2 | zig clang
  20 | MSVC 14.44 | MSVC `/fsanitize=address`). Each probe below prints Kira's value on all
  four builds, with 0 ASan reports: a3b `1 2 two-long 2 7 8 19`; c2 `1 one 1 1 1`, `1 one 1 2 2`,
  `2 two-long 2 7 0 8 0`; c5, c6, c14, c19 and a3c1-3 `2 two-long 2 ...`; c8 `1 one 2 7 0 8 0`;
  c12; c15 `88 b bb`; c20 (List and Maybe); a3c6; atk a3; c18; d3. a3c4 (a class field) and
  c16, c17, c21, cl1, d1, d2, d4, d5 declare a class (KI-18). c3, c4 and c13 stay refused by
  D37's receiver rule, c7 by `types.index.map-read`, and a3c5 by the typer, as on the trial.
- **Tests.** `CppCopyPolicyTest.anAssignmentsValueIsCopiedUnlessItIsATemporaryOrAPrivatePlaceUnderAnotherRoot`
  has 13 copy texts from the verifier's probes, 2 W1 texts, and 2 W2 lends with no copy.
  `anAssignmentsValuePrintsKirasValueOnEveryCompiler` has 15 run rows (a3b, c2, c5, c6, c8, c12,
  c14, c15, c19, c20 twice, a3c3, a3c6, and the two lends), run on gcc, clang and msvc.
- **Acceptance.** `./gradlew test` ran 1207 tests: 0 failures, 0 errors, 0 skipped.
  `examples/regenerate.sh --check`: all snapshots current. goldens.sh: 17 cases, 67 passed, 0
  failed. run.sh: 57 passed. sys.sh: 18 passed. msvc.bat: all passed.

### Round 4's minors this package owns, ledgered

- **The design and the generator.** 50-round4's lending points (1.1-1.8) and 7.2's generator
  table have no assignment-value use. The next generator should add that dimension: target =
  own element, element = owner, field = own element's field; through a local, a `mut`
  parameter, a global, a List element, a value `this`, a class field, a `Ref` and a `Maybe`.
  `CppCopyPolicyTest`'s assignment module covers each of these but the class field, which is
  W2.4's to run (KI-18).
- **Not written yet: 7.3's PRVALUE node-class walk test and the CONFINED table test.** Neither
  is a one-line fix. The round-4 verifier read `isPrvalue` and found no hole: it is a positive
  list, and every `TypeCastExpr` kind lowers to a numeric, char, enum or TO_STR conversion.
- **A compound assignment's value is not a use.** `s += e` on a `Str` is `std::string`'s
  append, which the standard defines as appending a copy of the range, so it is safe even when
  `e` aliases `s` (c15's `s += s` gives 88 on every build). A user type's `op=` lowers to
  `a = a.op(b)`, a call, and a numeric operand is taken by value. Neither finding named this
  path, so it is unchanged.

## Copy by default, round 4

This round merges `cpp/w2-5-rules` (b5910b6, merge 1d605d8, no conflict), so the policy reads
round 4's rules (`CallReach.confined`, `TypedModel.fnConfined`, rule M, O1/O2), and then does
50-round4's W2.3 plan (6.4). On the merge alone `./gradlew test` ran 1190 tests, 0 failures.

- **The policy, one function (`CppCopyPolicy.pass`, 50-round4 2.0).** Wherever C++ binds a
  reference to a first-class operand (a `CppHoister.Use`), the operand is a temporary or it is
  copied, `T(e)`, unless W1-W3 (W6 for a range) keeps it still. The uses:
  - a given argument, where the root declaration's unsubstituted parameter is spelled by
    reference (`CppCopyPolicy.argByRef`: `!byValue`, so a generic `T` at `Int32` is a use; a
    binding's, `trace`'s and an `Fx` value's by the Kira type; an extern's also a class handle;
    a `Str` given to a second-class parameter);
  - a receiver: a value receiver read as `const S&` (a struct's `this`, an implicit `this`, a
    binding's `{self}`), and a class or trait handle of a method or extern call (`h->m(...)`,
    copied as `kira::Rc<C>(h)->m(...)`);
  - an `Fx` value's callee (`kira::Fn<...>(f)(...)`), never CONFINED, so lent only when PRIVATE;
  - the operands of `Str` `+` and `==`, a container's `==`, and `kira::cat`'s holes, whose
    consumer is CONFINED when no operand type holds a user class, trait or `T`
    (`CppCopyPolicy.runtimeConsumer`);
  - an extern's argument: the policy decides and `externCallText(rc, receiver, args, copied)`
    spells it (`kira::ffi::in(kira::Str(e))`, `T(e)`); `copied` is the set W2.6 receives;
  - a `for` range: `rangeLends` (W6), else `for(const T& x : T(r))`.
  A use D33 spilled is its typed temporary, already the copy (W1): no second copy.
- **PRVALUE, PRIVATE, NAMED, W3.** `isPrvalue` is a positive list (a literal, an interpolation,
  a construction, a lambda, an operator, a cast, an IIFE-lowered if-expression, a ternary of
  prvalues, a call of a Kira function, a converting coercion); `isPrivate` a local, a by-value
  parameter, a value `this` in a plain `fx` (or a lambda's `[*this]`), or a value temporary,
  through value steps (`Rules.isReferenceStep`); NAMED is `writtenBy` plus the consumer's own
  `mut` operands, and `writtenBy` no longer names the receiver of a class `mut fx`
  (`Rules.writesObjectOnly`); W3 is `CallReach.confined`, no IMPURE operand, and `Rules.mayHold`
  both ways against every own `mut` operand not PRIVATE with another root.
- **A binding's result is no prvalue (2.0, literally).** A binding the `LEND` pin misses is
  copied, never lent. The cost is text only (C++17 elides `T(e)` of a prvalue): numerics and
  proto gain `kira::Str(kira::str::padStart(...))` and `kira::Str(kira::str::substring(...))`,
  three lines. 50-round4 4.1's count classed a non-accessor binding's result as W1, so these
  three lines are beyond its prediction; every other `expected/` tree on this branch is
  byte-identical, evalorder's included.
- **R-PURE (2.8).** `CppHoister.rank` is IMPURE when EffectsPass or the scan says so, PURE only
  on the positive list (a literal, a constant, a PRIVATE non-view place no sibling writes, a
  lambda whose captures are such reads, a node EffectsPass calls PURE on its own with PURE
  children), else READS. Deleted: `sharedRead`, `readsThrough`, `readsAny`, the `ThisExpr`
  READS case and the READS half of `modelRank`. A by-value parameter and a value `this` are
  PURE now (invariant I), so `b.pair(next(), next())` with `b: Box` no longer copies `b`
  (`CppHoisterTest.anImpureReceiverIsCopiedBeforeTheArgumentsAndAStructParameterIsNot`).
- **P1, P2, O3.** An inner element step through a reallocating container is a leaf in every
  mode, and a lent accessor's receiver is bound (its own element step counts), so both
  spellings of `gll[0][growGll()]` locate `gll[0]` after the index. A `Str` receiver of `[]` is
  a by-value operand. A view owner that is no place and no prvalue is copied
  (`total((if c { la } else { lb }).view())` is `kira::view(kira::List<std::int32_t>(c ? la : lb))`).
- **The binding-drop fix (w2-3 #3).** An operand a binding never names is evaluated anyway,
  unless it is PURE: `(static_cast<void>(mkT()), static_cast<std::int32_t>(2))`.
  `CppBindingTableTest.everyBindingThatLeavesOutAnOperandIsListed` pins the list, measured as
  the ten `TupleN.size` receivers.
- **Deleted**: `rangeMayDangle`, `isTemporary`, `isCopiedPlace` and the syntax READS clauses
  above. KI-10's old range rule is W6 now.
- **Measured** (scratchpad `w23r4`, this branch's CLI, g++ 13.2 | zig clang 20 | MSVC 14.44,
  plus MSVC `/fsanitize=address`):

  | Probe | Kira | Round 3 | Now, all three | ASan |
  |---|---|---|---|---|
  | n1 (w2-3 #0) | 501, 501, old:2, 503 | 1501, 501, 1501 and the like | 501, 501, old:2, 503 | 0 |
  | n7, n8 (w2-3 #1) | 88; 77 5 99, 77 89 99 | clang -2, clang exit 127 | 88; 77 5 99, 77 89 99 | 0 |
  | n5, n7 (w2-3 #2) | b, b | Y on all three, Y b Y | b, b | 0 |
  | n11 (w2-3 #3) | 2 1, 4 3, 4 4 | 2 0, 4 0 | 2 1, 4 3, 4 4 | 0 |
  | r4d/p3 (a), (b) | 47, 6 | 3 on all three; 183960230, 6, 6 | 47, 6, the C++ of p3fix | 0 |
  | r4d/p5 | old-name...:1 | new:2 | old-name...:1, byte for byte p5fix | 0 |

  W2.5's round-4 sweep names 16 probes its deleted refusals now accept. n2, rp1 and rp1f2 run
  here and print Kira's value on the three compilers with 0 ASan reports; f1r and q5 stay
  refused by rules that remain (`rules.exclusivity.order`, `rules.view.write`); the other 11
  (al, b1ki, r2v6 a1, b1, b1c, b1k, b1u, b9b, b9c, tw1, q7) and r4d/p1 declare a class or a
  trait, which this branch does not lower (KI-18).
- **Tests.** `CppCopyPolicyTest` (new): 20 text rows (each whitelist entry lends with no copy
  in the body, each rule-6 use and O3 is `T(...)`), 16 run rows and round 3's 12 run rows, each
  on gcc, clang and msvc. `CppBindingTableTest` gains the omitted-operand pin; `CppHoisterTest`'s
  `localReceiver` and `lentIndexArg` assertions follow R-PURE and P1.

### KI-18. The class half of the policy is emitted but not run on this branch

- **What.** A class handle receiver's copy (`kira::Rc<C>(h)->m(...)`, W5), E-DROP
  (`CppCopyPolicy.dropsOnCopy`: a copied handle whose drop may run an IMPURE `finally` spills
  its call alone) and every probe with a class or a trait (r4d/p3 (c), the 11 above) need
  W2.4's class lowering, which this branch lacks (`cpp.unsupported: the class ... is not
  lowered yet`).
- **Why it can wait.** The integration trial has classes; W2.4's run tests and the verifiers'
  replay (50-round4 7.1) run them. The text is the same `T(e)` as every other copy.

### KI-19. A binding's result is copied where it is used by reference

- **What.** `T(binding(...))` wherever a first-class binding result binds a `const&` (above).
- **Why it can wait.** It is the safe reading of 2.0 (a missed accessor is a copy) and costs
  nothing at run time; a binding flag saying "returns by value" would let the text go.

### Round 3's minors, ledgered

- #0 A `return`, `break` or `continue` in a branch of an if-expression that is no ternary is
  `cpp.unsupported` (`CppStmtEmitter.ifExprLambda`), though the rules accept it (r2v23/i1).
  Loud, not a wrong value.
- #1 `gv.view().get(0)` is `kira::mutView(gv)[0]` and `gv.view()[0]` is
  `kira::at(kira::mutView(gv), 0)`: both checked, one value, two spellings.
- #3 A `Map` `[]` read is `types.index.map-read`, so R-A's Map row cannot be written.
- #4 (the parser's) `xs.get(0) = 1` panics in `KiraParser.parsePrimaryExpr`; refused either way.
- #5 MSVC's ASan cannot see a left-to-right hazard: every run row here runs on clang too.

## Views are second-class, round 3: one place however it is spelled

This round merges `cpp/w2-5-rules` (f86261f, merge 8bac10a) first, so every test here runs
against the rules, and then does 40-round3's W2.3 plan (section 5.4). The merge's 4 conflicts
were resolved as the trial's db5eaac did (this branch's text golden; both `views.kira` lines),
and it takes the seam commit's 11-line CppHoister hunk (a READS entry is a call with no
effect). On the merge alone, `./gradlew test` ran 1168 tests with 37 failures, all this
package's (CppHoisterTest 33, the evalorder golden 4). CppExprRowsTest's 37 already passed:
F1 was their only cause.

- **Round 2's w2-3 #0, fixed (R-E): a module-qualified global was read out of order.**
  `CppHoister.scan` raised READS only for a `MemberRef.Field`, and `rank` took only IMPURE
  from the model, so `ctr.G` (a `MemberRef.ModuleMember`) ranked PURE. Now:
  - `rank` is at least EffectsPass's rank over the operand's nodes (`modelRank`: the root's
    entry, which is the join of its nodes', READS as well as IMPURE), raised by the scan;
  - the scan asks the model's place for every read (`TypedModel.readPlace`) and the rules'
    `Rules.isSharedPlace`, not a `MemberRef` kind;
  - `writtenBy`, `readsAny` and `viewsForeignStorage` compare `Place.root()` values
    (`placeRoot`), and `freshRoot` became `freshlyOwned`, a receiver-less `Place.Field` root;
  - no `MemberRef` is left in `CppHoister.kt`. `isPureCall` also reads R-G's
    `FnSymbol.suppliedByCpp`.
  Measured before (the merge's CLI) and after, on the round-2 verifier's probes (scratchpad
  `w23r3/m1`..`m7`), with Kira's values from the unqualified controls:

  | Probe | Before (the merge's CLI): gcc / clang / msvc | After, all three | MSVC ASan before / after |
  |---|---|---|---|
  | m1 `sub(ctr.G, bumpG())` and three twins | 15 / 5 / 15, and so on | 5, 15, 25, 35/0, 45, 55 | - / 0 |
  | m2 `show(ctr.GS, changeGS())`, `count(ctr.GL, growGL())` | the new string and 67, on all three | old0, 3 | - / 0 |
  | m4 `show(growGSL(), ctr.GSL[0])` and its mirror | bad_alloc / garbage / "0:" | 0:first..., first...:0, 129 | heap-use-after-free / 0 |
  | m6 `ctr.GLL[0].add(growGLL())`, `store(mut ctr.GLS[0], ...)` | no output / no output / 1 | 2, 7 | heap-use-after-free / 0 |

  m4 and m5, and m6 and m7, now emit the same C++ byte for byte. Pinned by
  `CppHoisterTest.aModuleQualifiedGlobalIsEmittedAsItsUnqualifiedName` (nine qualified forms,
  each equal to its control) and `aModuleQualifiedGlobalReadsKirasValueOnEveryCompiler` (9
  checks on gcc, clang and msvc), and by the evalorder golden's new `sub(ctr.T, ctr.bumpT())`.
  No other golden's `expected/` changed: the model's READS adds no spill to any of them.
- **R-A, the emitter's side: a lent result is lowered as the place it is.** `isPlaceExpr`
  reads `TypedModel.readPlace`, so `gl.get(i)`, `m.unwrap()` and `r.unwrapErr()` are places
  the way `gl[i]` and `m.value` are. `placeOperand` makes a lent call a `Operand.Place` whose
  parts are the call's own operands (`CppHoister.lentPlace` captures them as a chain's are),
  and the accessor's receiver is a step of the path (`pathOperand`), never a snapshot.
  Measured: `minus(ll.get(0), setAt(mut ll[0][0]))` (a view of a lent element, `ll` a local
  List of Lists) printed 6 on gcc, clang and msvc on the merge, where Kira and the index
  spelling give 55: the element was copied first and the view formed of the copy. It prints 55
  now (`w23r3/ra` against `w23r3/ra-merge`). Six spelling pairs (a scalar, a `Str` element, a
  `Maybe` payload, a view of an element, a nested element with an impure index, a lent
  receiver) emit identical C++ and run right on the three compilers with 0 ASan reports.
  Pinned by `aLentResultIsLoweredAsThePlaceItIs` and `aLentResultReadsKirasValueOnEveryCompiler`.
- **The `LEND` pin, this side.** `CppBindingTableTest.theBindingsThatReturnAReferenceIntoTheirReceiverAreExactlyRulesAccessors`
  reads the manifests through `CppBindingTable.parse`: the bindings whose whole expansion is
  `kira::at({self}, ...)`, `kira::unwrap({self})`, `{self}[...]`, `{self}.unwrap()` or
  `{self}.unwrapErr()` are exactly `Rules.ACCESSORS`, and `Stack.peek`, `Queue.peek`,
  `Map.get`, `Maybe.unwrapOr`, `List.set` and `Arr.set` are not. The pin bites: a scratch copy
  with a fake `List.first: kira::at({self}, 0)` fails it (`theAccessorPinFailsOnAnAccessorTheRulesDoNotKnow`).
  `kira/cpp/tests/rt_test.cxx` gains the C++ half: static_asserts that the seven lenders
  return lvalue references and that `Stack.peek`, `Queue.peek` and `Map.get` do not.
- **Round 2's w2-3 #1, fixed: the evalorder golden and the order rule agreed.** W2.5's F1
  accepts lines 143, 231, 233, 236 and 259 (29, 4, 2, 6, 0, unchanged). Lines 192, 195, 222,
  389 and 393 left the golden with their helpers (`gm`, `putKey`, `Acc`, `ga`, `bumpA`): they
  are W2.9.8's (KI-17). Three lines are new: `trace(gl[setFirst()])` (50, Q4 for the index
  spelling), `trace(sub(ctr.T, ctr.bumpT()))` from a second module, `lib:ctr` (-1, R-E), and
  `trace(sub(gl.get(0), resetL()))` where `gl[0]` is 1 (1, R-A). `case.yaml` says
  `pins: spills`, which W2.5's no-spill corpus check skips. expected/ and expected.txt were
  regenerated; the emitted diff is exactly those lines.
- **Round 1's minor #1 (KI-13), closed by F3.** ViewPass refuses a view of a temporary in an
  if-expression's branch as `rules.view.position`, in both the ordered and the one-statement
  form. The emitter's two `cpp.unsupported` reports for it are `cpp.internal` now, and
  `unsupportedView` is gone: no view-shaped `cpp.unsupported` is left in the emitter.
- **w2-6's round-2 #2 and #3 (owner W2.3), refused.** A view of an accessor's result beside a
  write of its owner (`sumAndClear(gl2.get(0).view())`, `sumAndClearM(gm.unwrap().view())`,
  the extern `sumVF(gl2.get(0).view(), fx ...)`) is `rules.view.write`, and a `for` over one
  whose body replaces the owner (`gl2.get(0)`, `gm.unwrap()`) is `rules.exclusivity.loop`,
  because the result is the place `gl2[0]` or `gm.value` (R-A). Pinned by
  `aViewOrALoopOverALentResultBesideAWriteOfItsOwnerIsRefused` (5 refusals, nothing emitted).
- **The tests 40-round3 4.1 counts** (78 on the trial):
  - CppExprRowsTest (37), the views module (4) and the optimistic module (1): F1, no change.
  - CppHoisterTest's shared module (29): `mapReadBeside`, `listReadBeside`, `viaExplicit`,
    `viaImplicit` and `namedBeside` moved to `hoist:receivers`, which the rules refuse whole:
    five `rules.exclusivity.order`, asserted by
    `thisAsAReceiverReadsLikeANamedStructReceiverAndBothAreRefusedUntilQ4IsLowered` and
    `aMemberStyleAndAFreeFunctionBindingReadTheirReceiverTheSameWay`. The shared module gains
    `gl[pushed()]`, emitted `kira::at(gl, pushed())`.
  - The refusing test is two: `aViewWhereTheRuleAllowsNoneIsTheCheckersAndPastItAnInternalError`
    asserts the checker's 15 codes (the emitter reports nothing), then, with the rule passes
    off, the emitter's 17 backstops, 16 of them `cpp.internal` and one `cpp.unsupported`
    (the StrBuf hole, an order, no view); `anOrderNoLoweringGivesIsRefusedByTheEmitter` shows
    that hole refused with the rules on.
  - The evalorder golden (3 tests) and RulesCorpusTest's no-spill check: above.
  - Two more, not in 4.1's count: the views module's `makeRef` and `makeMaybe` are PURE under
    EffectsPass, so their owner stays in the full expression that uses the view (safe);
    they now bump `ticks`, so the test still pins the spilled owner. And EscapePass now sees
    that `handOn` only hands its `Fx` on, so both `eachOf` and `handOn` take a template
    parameter; the test checks that, and KI-2's fallback with the rule passes off.
- **Decisions beyond the plan's letter.**
  - The model's READS includes `this` of a class (a handle): EffectsPass calls every
    `Place.This` shared. A class `this` handed as an argument beside an impure sibling is
    therefore copied (a handle copy, correct and cheap). No golden on this branch lowers a
    class; W2.4's `chain` and `classes` may gain such a spill at the integration (KI-4).
  - A lent accessor's receiver is a path step, read where the accessor runs (Q4), not a
    snapshot. Where that differs from a snapshot (a write of the receiver's place inside the
    accessor's own arguments) the order rule's interim clause refuses the call.
- **Measured this round.** The full suite, regenerate.sh, goldens.sh, run.sh and msvc.bat:
  see the acceptance in the round's report. evalorder under MSVC `/fsanitize=address`: 0
  reports, output identical to `expected.txt` (`w23r3/eoasan.sh`).

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

## Decisions the user answered

- **OD-1 (a stdlib receiver beside an effect), answered by Q4.** The user's Q4 reads a place
  receiver of a container or a mutable value when the call runs: `gm.get(putKey())` gives 99
  and `gl.get(setFirst())` 50, not the snapshot's 42 and 1. Until W2.9.8 lowers that for method
  calls, `rules.exclusivity.order` refuses the shape (KI-17), and the evalorder lines that
  pinned the snapshot are gone. OD-1's JS answer is the user's.
- **OD-4 (decision 4b's "impure"), answered: literal.** The user's 4b is read literally:
  `effect(C) == IMPURE`, as everything since round 2 is written against.
- **OQ-1 (an operator on an immutable value), answered: read first.** `ticks += next()` and
  `z += inc(mut z)` keep 29 and 6 in evalorder: an immutable value's operand is read before
  the call, as D33's copy reads it.

OD-2 and OD-3 were closed by decision 4b (no view is kept or held).

## Known issues

### KI-12. Closed: W2.5's ViewPass is merged

The merge (8bac10a) brings the rule. The three probes that compiled on this branch alone
are refused: `sc31/k1` (`viewPlus(gl.view(), growL())`) and `sc31/k3`
(`growThenSum(gl.view())`) at `rules.view.write`, `sc31/k2` (`return xs.from(at)`) at
`rules.view.return` (the round-3 CLI, scratchpad `w23r3/k1`..`k3`).

### KI-13. Closed: a view of a temporary in an if-expression's branch is the rule's (F3)

ViewPass refuses it as `rules.view.position` (40-round3 F3), also in the one-statement form
`total(if c { makeList().view() } else { gl.view() })` that this emitter lowered safely. The
emitter's two reports for it are `cpp.internal` backstops now. Pinned by
`CppHoisterTest.aViewWhereTheRuleAllowsNoneIsTheCheckersAndPastItAnInternalError`.

### KI-14. The emitter reads the typer's facts, not `TypedModel.viewOrigins`

- **What.** Design 30 2.3 has W2.5 record each view's origins in `TypedModel.viewOrigins` for
  the emitter. The table exists since the merge; the emitter still does not read it. E1 decides LENT from the typer's
  `Coercion.ToView` and the call's second-class result type, which are the PLACE-origin facts,
  and `CppHoister.viewsTemporary` walks a view's receiver and view arguments for a TEMP origin
  (the E3 and E4 checks and the backstops). The walk trusts the return rule: a callee's view
  points only into its receiver and view arguments.
- **Why it can wait.** Both are the same facts `viewOrigins` holds, and the places the walk
  reaches are read through `TypedModel.readPlace` (R-E), so a lent view (`gll.get(0).view()`)
  is of a place there too. Switching to `viewOrigins` changes no emitted text on any golden or
  test here; it is a later cleanup.

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

- **Measured by round 2's verifier (b2).** `trace(sub(Holder { v = 50 }.get(), next()))` is
  spilled as `const std::int32_t t0_ = std::make_shared<Holder>(50)->get()`, so Holder's
  `finally` prints "bye 50" before "next 1"; the unspilled `trace(sub(makeH(70).get(), 1))`
  prints "bye 70" after the result. The same on gcc, clang and msvc, 0 ASan reports. The spec
  runs a `finally` when the count reaches zero, so both orders are defensible; whether an
  unrelated impure sibling moves it is the lowering's, and it is W2.4's call.

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

### KI-2. Where EscapePass wrote no entry, `fxEscapes` is approximated rather than taken as escaping

- **What.** The model contract (design 3.1) says an absent `TypedModel.fxEscapes` entry means
  escaping. With W2.5 merged, EscapePass writes the entries and they win: `handOn`, which only
  hands its `Fx` to `eachOf`'s non-escaping parameter, now takes a template parameter too.
  Where no entry exists (the rule passes off), `CppEscapes.fxEscapes` still makes a parameter
  the body only calls a template parameter.
- **Where.** `CppEscapes.fxEscapes` / `scanFx`, read by `CppPlacement.isNonEscapingFx`
  (W2.2's file) and `CppClosureEmitter.prepare`.
- **Reproduce.** `CppHoisterTest.anFxParameterOnlyCalledIsATemplateParameterAndOnePassedOnIsNot`
  checks both: EscapePass's answer with the rules, the fallback's without them.
- **Why it is safe.** A template parameter and a `kira::Fn` make the same call, and a function
  used as a value, or a parameter with a default, is always a `kira::Fn`. Design 30 7.2 says
  the fallback goes; that is a cleanup once every path runs the rule passes.

### KI-4. W2.4's `chain` and `classes` goldens differ from this branch by one D33 spill each

- **What.** With this branch merged, `chain` and `classes` differ from their `expected/` by one
  D33 spill each: a class field read beside a virtual call, `kira::cat(name, " says ",
  sound())`, is copied first because `sound()` may change `name`.
- **Why it can wait.** The spilled code is correct and compiles on every toolchain. The
  integrator regenerates those two `expected/` files at the merge.
- **Round 3.** The rank is now at least EffectsPass's, which calls a class's `this` (a handle)
  READS, so a class `this` handed as an argument beside an impure sibling is copied too. Not
  measurable here (no class lowers on this branch); the integrator's regeneration covers it.

### KI-5. gcc 11.4, the design's floor, is not measured on this branch

- **What.** The branch is measured on g++ 13.2, zig c++ (clang) and MSVC, and compiled for
  aarch64. gcc 11.4 (the board, `jack@bibobox`) is not.
- **Why it can wait.** This round adds no C++ language feature: typed temporaries and
  `kira::View` temporaries in IIFEs, all C++11. Someone with the board runs
  `kira/cpp/tests/goldens.sh` and `run.sh` there before the merge.

### KI-6. Closed: every commit is signed

`git log --format=%G? cpp-backend..HEAD` shows `G` for every commit (the four this entry named,
41575c6, 71b8c4b, 42f1b7f and fbf0f61, are no longer on the branch: its history was re-signed).

### KI-7. Files outside this package's OWNS and TOUCHES

- **What.** Earlier rounds edited `kira/cpp/kira/rt.hxx` and `kira/cpp/tests/rt_test.cxx` (two
  `kira::list` overloads), `CppPlacement.kt`, `CppDeclEmitter.kt` and `CppEmitContext.kt`
  (W2.2's), `examples/regenerate.sh`, `examples/cpp-legs.txt` and three plumbing tests. This
  round edits `src/test/resources/typer/body/positive/views.kira` (W2.1's fixture): its
  `v: View<Char> = out.view()` becomes `m: Size = out.view().size()` with the same `@type`
  check on `out.view()`, since `CppTyperFixturesTest` emits it and a view local is now
  `cpp.internal`. It also rewrites the `text` golden's source, driver and `expected/`, which
  design 30 section 8 gives this package. Round 3 adds the `LEND` static_asserts to
  `kira/cpp/tests/rt_test.cxx` (40-round3 5.4 gives them to this package) and a second module,
  `lib:ctr`, to the evalorder golden.
- **Why it can wait.** Each change is small and covered by tests. The merger routes them.

### KI-8. Closed: EffectsPass is merged

EffectsPass's PURE entries remove the copies a container read got beside any user call. What
the hoister still ranks IMPURE whatever the table says is unchanged: a virtual, trait, `Fx`,
extern or bodiless call (R-G's `suppliedByCpp` included), a call handed a `MutView`, and a
construction with an `initially`, a `finally` or an impure default. A pure owner of a view now
stays in the full expression that uses the view, which keeps it alive (`plusSize(t0_,
kira::mutView(makeRef()->value).from(1))`); an impure one is spilled as before.

A cost left (round 2's note): a user function returning a view of its view parameter
(`tail(v, at)`) is READS (EffectsPass's read of a view), never PURE, so such a chain beside an
effect is copied into a `const kira::View` temporary. Correct (round 2's b1), not the cheapest.

### KI-10. A `for` range is iterated as a copy unless W6 keeps it still

- **What.** `CppCopyPolicy.rangeLends` (round 4, W6) lends a prvalue range, or a place no named
  write of the loop overlaps whose root is no temporary and which is PRIVATE or whose body
  EffectsPass ranks at most READS; anything else is `for(const T& x : T(r))`, the copy the
  loop's reference keeps alive (`kira::at(makeLists(), 0)`, `makeRef()->value`, a global the
  body may replace). A view range is never copied (design 30 E5).
- **Why it can wait.** A copy of a prvalue is elided; a real copy costs one container per loop.

### KI-11. The CLI cannot reach R6's skipped middle default, so only the rows test covers it

- **What.** R6 fills a skipped middle default in at the call (`three(1, c = 9)` becomes
  `three(1, 2, 9)`). From source, the C/JS semantic analyzer's named-argument check refuses
  `f(1, c = 5)` before the typer binds it.
- **Where.** `KiraSemanticAnalyzer.checkNamedArguments` and `core/NamedArguments.bind`
  (frontend code). The emitter's side is `Slot.Filled`.
- **Why it can wait.** Nothing is emitted wrongly. `CppExprRowsTest`'s `r6` row checks
  `r6::skipMiddle() == 129` on gcc, clang, msvc and zig-aarch64.

### KI-17. A method call's receiver beside a write of its place waits for W2.9.8 (Q4)

- **What.** The user's Q4 reads a container's or a mutable value's receiver when the call runs.
  No lowering of that exists for a method call before W2.9.8, and a snapshot would give the
  answer Q4 rejected, so `rules.exclusivity.order` refuses the shape (40-round3 3.2, clause 3).
  These left the evalorder golden with their helpers, with Q4's values for W2.9.8:

  | Line (old golden) | Kira | Q4's value |
  |---|---|---|
  | 192 | `trace(gm.get(putKey()).unwrapOr(-1))` | 99 |
  | 195 | `trace(gl.get(setFirst()))` | 50 |
  | 222 | `trace(ga.plus(bumpA()))` | 210 |
  | 389 | `this.plus(bump())` in `Acc.viaThis` | 210 |
  | 393 | `plus(bump())` in `Acc.viaImplicit` | 210 |

- **Where.** `hoist:receivers` in `CppHoisterTest` holds the same five shapes, refused.
- **Why it can wait.** It is a refusal, never a wrong value. The index spelling
  `gl[setFirst()]` already reads the List when `kira::at` runs (50, in the golden).
