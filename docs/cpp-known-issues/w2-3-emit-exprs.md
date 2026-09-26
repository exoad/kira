# Known issues: w2-3-emit-exprs

The C++ backend's expression, statement, closure and binding lowering: `CppExprEmitter.kt`,
`CppStmtEmitter.kt`, `CppClosureEmitter.kt`, `CppHoister.kt` (with `CppLending` and
`CppEscapes`) and `CppBindingTable.kt`, the tests under `src/test/kotlin/net/exoad/kira/cpp/exprs/`
and the `evalorder` golden. Each entry says what the issue is, where it lives, how to reproduce
it, and why it is safe to leave for now. "Measured" means built with the goldens' warning flags
and `-Werror` on g++ 13.2, zig c++ (clang) and MSVC `/W4 /WX`, and run.

Fixed in convergence round 1, and so not listed below: a generic callee that stores its `T`
instantiated with a view (`keep<View<Int32>>(mut h, gl, nextSize())` copied `gl`, and `h.v`
dangled: gcc printed garbage, MSVC's ASan a heap-use-after-free); a `View` built from a
`List` in a call's argument list, unsequenced against an impure sibling (`sizeOf(gl, grow())`
printed 4 on gcc and 3 on clang); a function used as a value whose `Fx` parameter had become a
template parameter (`g: Fx<...> = applyTo` did not compile on any compiler); the escape scan's
"a body that makes any view lends every by-reference operand" rule, which read `lenOf(gl,
grow())` after the effect; and a local read beside a sibling that writes it through a `mut`
argument or a `MutView` (`sub(x, inc(mut x))` printed 14 on gcc and 4 on clang).

## Open decisions

These are questions about the language that only the user can settle. The current behaviour
is documented and pinned by the evalorder golden. None of them is a memory-safety question:
each behaviour below is safe.

### OD-1. A stdlib receiver beside an effect is read before it

- **What.** `gl.get(pushed())`, where `pushed()` appends to `gl` and returns the new index,
  reads `gl` before `pushed()` runs (D33, snapshot: the hoister copies the receiver). The
  copy has no element at that index, so the program panics with `kira: index out of range`.
  The JS backend prints 7, because a JS array is a reference and R19 says a place is never
  copied. Kira's left-to-right rule and D44's value semantics say the receiver is evaluated
  first, as a value.
- **Where.** `CppExprEmitter.receiverOperand` / `receiverMode`: a receiver the call cannot
  lend from is a `PlaceMode.SNAPSHOT`.
- **Reproduce.** The verifier's `o-tq7` probe prints -1, then panics (exit 127) on gcc,
  clang and MSVC. `evalorder/expected.txt` lines 22-23 (`gm.get(putKey())` gives 42 and
  `gl.get(setFirst())` gives 1) pin the snapshot rule.
- **The choice.** (a) Keep the snapshot. (b) Read a receiver in place, as R19 says of a place.
  That is the JS answer, and it would flip those two golden lines to 99 and 50.

### OD-2. A view the callee keeps is made after its siblings' effects

- **What.** When a call may keep a view into an operand (the lent case: the callee stores it,
  returns it, or hands it to a callee that is not seen through), the emitter never copies that
  operand, because the view would point into a copy that dies with the IIFE. It reads the
  operand after every sibling has run. Left to right would take the view first. The emitter
  does not do that, because taking the view first is not memory-safe when a sibling moves the
  storage the view points into. For example, in `keep<View<Int32>>(mut h, gl, growL())`,
  `growL()` appends past `gl`'s capacity. A view taken before it would point at freed
  storage. Taken after it, the view sees 67 elements, not 3. The same holds for a lending
  receiver (`gl.from(grow())`), a construction's view field (`Win { v = gl, k = grow() }`) and
  an assignment into a view (`sinks[nextSize()].v = gl`). For the assignment, Kira's own
  order, target first, agrees with reading `gl` after the index.
- **Where.** `CppHoister.PlaceMode.LENT` (a leaf of its own, never copied, its path applied
  after every sibling), chosen by `lentArgument`, `lentReceiver` and `CppLending.lends`.
- **Reproduce.** `evalorder/expected.txt` lines 45-46 (11, then 67). Under MSVC
  `/fsanitize=address` the whole evalorder golden runs with 0 reports.
- **The choice.** (a) Keep this. It is safe and deterministic, and it differs from left to
  right only where a sibling writes the operand. (b) Take the view first and refuse the call
  wherever a sibling may write the viewed storage. That needs EffectsPass's write sets to be
  precise. Until EffectsPass merges, every user call is impure, so (b) would also refuse
  `w.attach(gl, nextSize())`, which is fine today. A lent operand no sibling can reach (a
  local) is unaffected either way.

## Known issues

### KI-1. `closures` stays `emit: pending`: it needs W2.4's class lowering

- **What.** The brief's byte-identity line lists `closures`. The CLI stops at
  `closures.kira:51:11: error: cpp.unsupported: the class 'Counter' is not lowered yet`.
- **Where.** `class Counter` in `src/test/resources/cpp-golden/closures/src/lang/closures.kira`.
  Classes are W2.4's (`CppClassEmitter`).
- **Reproduce.** `kira --target cpp` on a copy of the case, or flip its `case.yaml` to
  `emit: required` and run `CppGoldenEmitTest`.
- **Why it can wait.** It depends on another package (policy item 5). Its `expected/`
  already compiles and runs on gcc, clang and MSVC, and compiles for zig-aarch64
  (`CppGoldenCompileTest`). The verifier checked that everything in `closures.kira` except
  `class Counter` emits byte for byte as `expected/`.

### KI-2. With no EscapePass entry, `fxEscapes` is approximated rather than taken as escaping

- **What.** The model contract (design 3.1) says an absent `TypedModel.fxEscapes` entry means
  escaping. Where W2.5 has written no entry, `CppEscapes.fxEscapes` instead makes an `Fx`
  parameter a template parameter when the body only calls it, outside any lambda, in a
  non-virtual, non-trait method. `unilidar`'s `each` needs this to be `emit: required`
  (design line 802).
- **Where.** `CppEscapes.fxEscapes` / `scanFx`. It is read by `CppPlacement.isNonEscapingFx`
  (W2.2's file) and `CppClosureEmitter.prepare`, so placement and closures agree.
- **Reproduce.** `CppHoisterTest.anFxParameterOnlyCalledIsATemplateParameterAndOnePassedOnIsNot`.
- **Why it is safe.** A template parameter and a `kira::Fn` make the same call. The one way a
  template goes wrong, a function used as a value, is excluded: every `Fx` parameter of a
  function that any `FnRef` names, and every one with a default, is a `kira::Fn` whatever the
  model says. If another fact ever makes such a function a template, `functionValue` refuses
  the reference by name rather than write C++ that does not compile. W2.5's entries win
  wherever present.

### KI-3. The view-escape scan is conservative in known ways

- **What.** `CppEscapes.viewEscapes` answers "can a view into this operand outlive the call".
  It is sound but coarse in these ways:
  - a value of a view-holding type computed from the operand counts as derived from it
    (`Win { v = other, k = xs.size() }`);
  - a view held in a local struct, used as a user method's receiver, escapes;
  - a closure that mentions a view escapes;
  - a stdlib binding with a view-holding parameter lends at the call site;
  - a `T` parameter fed a converted view counts as kept wherever the body stores its `T`;
  - a cycle in the call graph escapes.

  In each case the operand is lent (OD-2): never copied, and read after its siblings. The only
  cost is D33's snapshot where a copy would in fact have been safe.
- **Where.** `CppEscapes.ViewScan` in `CppHoister.kt`.
- **Reproduce.** `fx f: (xs: List<Int32>, k: Size) Win { return Win { v = other, k =
  xs.size() } }` called as `f(gl, grow())` reads `gl` after `grow()`. A snapshot would have
  been safe.
- **Why it can wait.** It never costs memory safety. A precise answer would need per-value
  provenance, which is `EscapePass`'s job. `TypedModel.viewEscapes` entries are only allowed
  to add an escape, never to remove one: EscapePass's view rule (design 3.4) allows a view to
  be returned, so its "does not escape" does not answer the hoister's question.

### KI-4. W2.4's `chain` and `classes` goldens differ from this branch by one D33 spill each

- **What.** W2.4's ledger (its KI-1) reports that, with this branch merged, `chain` and
  `classes` differ from their `expected/` by one D33 spill each: a class field read beside a
  virtual call, `kira::cat(name, " says ", sound())`, is copied first because `sound()` may
  change `name`.
- **Where.** W2.4's `expected/` against this branch's `CppHoister`.
- **Why it can wait.** The spilled code is correct and compiles on every toolchain (W2.4
  measured this). At the merge the integrator regenerates those two `expected/` files or
  narrows the rule. This branch keeps D33's rule.

### KI-5. gcc 11.4, the design's floor, is not measured on this branch

- **What.** The branch is measured on g++ 13.2, zig c++ (clang) and MSVC, and compiled for
  aarch64. gcc 11.4 is not.
- **Where.** The board (`jack@bibobox`) has gcc 11.4.
- **Reproduce.** `ssh -o ConnectTimeout=8 jack@bibobox 'g++ --version'` timed out from this
  session, and the Docker daemon is not running.
- **Why it can wait.** This round adds no C++ language feature. The new output is ordinary
  IIFEs and `const T t0_ = ...;` temporaries, which earlier rounds already emit. Someone with
  the board runs `kira/cpp/tests/goldens.sh` and `run.sh` there before the merge.

### KI-6. Four earlier commits are unsigned

- **What.** 41575c6, 71b8c4b, 42f1b7f and fbf0f61 show `%G? = N`. From those sessions gpg
  failed with "No pinentry".
- **Why it can wait.** Rewriting published branch history is the merger's call. Re-signing
  is `git rebase --exec 'git commit --amend --no-edit -S' 1ff53c5` at a terminal where
  pinentry can prompt.

### KI-7. Files outside this package's OWNS and TOUCHES

- **What.** Earlier rounds edited files that other packages own:
  - `kira/cpp/kira/rt.hxx` and `kira/cpp/tests/rt_test.cxx` (runtime owner, w1-3): two
    `kira::list` overloads on `std::array`;
  - `CppPlacement.kt` (W2.2): `isNonEscapingFx` reads `CppEscapes`;
  - `CppDeclEmitter.kt` (`includeLine`) and `CppEmitContext.kt` (docs), both W2.2's;
  - `examples/regenerate.sh` and `examples/cpp-legs.txt`;
  - the plumbing tests `CppCliTest.kt`, `KiraCppBackendTest.kt` and
    `decls/CppDeclEmitterTest.kt`.

  This round adds none. `CppExprEmitter.functionValue` reads W2.2's existing
  `ctx.placementOf(...).isNonEscapingFx`.
- **Why it can wait.** Each change is small and covered by tests. The merger routes them to
  their owners.

### KI-8. Until EffectsPass lands, a container read beside any user call is copied

- **What.** EffectsPass (W2.5) has not merged yet, so every user call is impure. A `Str`,
  struct or container that is read beside a user call, and that the callee cannot lend from,
  is deep-copied into the IIFE (D33).
- **Why it can wait.** It is a cost, not a correctness issue. When EffectsPass merges, its
  `PURE` entries remove every copy whose sibling cannot write.
