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

Fixed in convergence round 2, and so not listed below (each is in `CppHoisterTest`, and the
ones that compile run in the evalorder golden on gcc, clang and msvc, ASan-clean on MSVC):

- **A view into a temporary is refused wherever it would outlive its full expression**
  (`cpp.view-lifetime`, with what to write instead: "store the result of 'makeList' in a local
  first"). This covers a fresh operand a call lends from inside the lambda that orders the
  call's operands (`trace(total(tail(makeList(), nextSize())))` printed 1651771218 on gcc, and
  MSVC's ASan reported a heap-use-after-free), with the same for `makeList().from(nextSize())`,
  a construction holding a call, `gl.toArr()` and an interpolated `Str`. It covers a value
  holding such a view copied into the lambda (`pick(tail(makeList(), 1), nextSize())`). It
  also covers a local, a `return`, an assignment and a `for` range that keep one
  (`v: View<Int32> = makeList().view()` compiled and read freed memory), a callee that stores
  one (`vs.add(tail(makeList(), 3))`), and an if-expression branch whose value views a local of
  that branch. `"x".view()` is no longer such a view: it is `kira::lit("x")`, which views the
  literal's static storage, as the implicit conversion already did. Before, it was
  `kira::str::view("x")`, which views a temporary `kira::Str`.
- **An explicit view made before a later operand's effect that may move its storage is
  refused.** `total(gl.view(), grow())` copied `kira::mutView(gl)` into the lambda before
  `grow()` reallocated `gl`, which is a heap-use-after-free. A view of an `Arr` or a `StrBuf`,
  whose storage never moves, is still copied first, as D33 says.
- **A bound place is located after a sibling that may move its container** (`PlaceMode.BOUND`:
  a `mut` argument, the receiver a `mut fx` writes). `gll[0].add(growGll())` and
  `store(mut gls[0], growGls())` bound `kira::at(...)` before the growth. All three compilers
  wrote into freed storage, and MSVC's ASan reported it. The element steps through a
  `List`/`Map` are now leaves, so the sibling is spilled first. A `StrBuf` element of such a
  container beside an interpolation hole that may grow it is refused, because each piece
  binds its receiver before its own hole.
- **A pure read through a view or a handle is READS** (`sub(v.get(0), setG())`,
  `readU16Le(pkt, 0)` beside `pump()`: gcc and MSVC printed 8, clang 0). The model's PURE
  now means only "no effect", and the scan still looks for reads under it (EffectsPass calls
  a function that reads a global pure).
- **A local some `MutView` is lent from is shared state.** A closure that captured the view,
  a `List<MutView>` and a struct holding it could all write it from a sibling, so
  `sub(xs[0], pk())` printed 8 on gcc and MSVC and 0 on clang. Taking a view of the local is
  still no read (`writeU32(p.from(20), crc32(...))` in `unilidar` is unchanged).

Fixed in convergence round 3, and so not listed below. Each refusal is pinned in
`CppHoisterTest.aTemporaryTheLoweringMakesOrStorageAPathMovesIsRefusedWithWhatToWriteInstead`,
next to its safe neighbours, which still compile. The spills run in the evalorder golden on
gcc, clang and msvc, ASan-clean on MSVC. The verifier's probes are in the scratchpad's
`vw23cr2`, and this round's own are in `w23r3`.

- **A temporary the lowering makes where the source names none is a temporary.** Two kinds
  were taken for places:
  - A `Str` default (D48: a literal or a `Str` constant) is a fresh `kira::Str` that the
    `const kira::Str&` parameter binds at the call. It is refused where a view into it would
    outlive the full expression: inside a D33 spill (`count(tailS(1), nextSize())` printed
    102 on gcc and 103 on clang), returned out of the spill's lambda
    (`count(tailK(nextSize(), nextSize()))`), in a local (`v: View<Char> = tailS(1)`, the
    verifier's pd1, which was pending on W2.5 and is now refused here too), and where the
    callee keeps a view of it (`keepS()` storing `s.view()` in a global). The message says to
    pass the parameter explicitly, from a local. A default that is a constant place of the
    parameter's own type binds as it is and is no temporary.
  - A place the typer wraps for a `Maybe` parameter (`WrapSome`) is a `kira::Maybe` copy of
    the place, and `m.value.view()` in the callee points into the copy.
    `v: View<Int32> = mview(xs)` printed 331473940 on gcc, and MSVC's ASan reported a
    heap-use-after-free. It is refused in a local, inside a spill (`mfrom(gxs, nextSize())`)
    and where the callee keeps the view. The message says to store the value in a local of
    the `Maybe` type first.
- **A view into storage that a step of its path moves is refused before a later effect.**
  The test used to read only the viewed value's own type. `total2(gla[0].view(), grow())`
  with `gla: List<Arr<Int32, 3>>`, and `total2(gr.value.view(), reset())` with `gr` a `Ref`,
  copied the view before the growth or the reset freed its storage (gcc -886930616, and ASan
  on both). `CppHoister.storageMoves` now also follows the path: an element of a container
  that reallocates, and a field behind a handle or inside a `Maybe`, both move. A local
  `Arr`, and an `Arr` beside a pure sibling, are still copied first.
- **A `MutView` a user callee lends out of a variable makes that variable shared state.**
  `mv: MutView<Int32> = mk(mut xs)`, then a closure writing through `mv`, then
  `sub(xs[0], pk())` printed 8 on gcc and MSVC and 0 on clang. `mutViewRoots` now also
  takes the `mut` argument, or the receiver of a `mut fx`, of any call that
  `CppEscapes.lendsMutView` says may let a `MutView` out: returned (a `List` and an `Arr`
  argument, a struct's `mut fx`, an `Fx` value's `mut` parameter) or stored (in a global).
  A callee that only writes its `mut` argument lends nothing, so `sub(x, bump())` after
  `inc(mut x)` stays unspilled.
- **A callee that is not known statically is scanned through every body it may run.**
  `keep(makeList())`, with `keep` an `Fx` value whose lambda stores `xs.view()` in a global
  (gcc -741963422 where Kira gives 1360) or through a captured `Ref`, is now refused, as the
  same body as a free function already was. `CppEscapes.dispatchStores` scans every lambda
  of the program and every function used as a value that has the `Fx` value's type, or, for
  a virtual or trait call, every method of that name and arity. A lambda of another type
  and a local argument are not refused.
- **More ways a temporary's view could outlive its statement are refused:**
  - a method that keeps a view of a fresh receiver (`makeBag().stash()` storing
    `items.view()` in a global printed 2071994584 on gcc);
  - a `Ref`'s box or a class object built around a view into a temporary
    (`Ref<View<Int32>> { value = makeList().view() }` printed 633348308 on gcc);
  - an operator overload that keeps a view of a temporary operand;
  - a store through a local handle (`keepR(makeList())`, whose body does
    `r.value.add(xs.view())` with `r` a local `Ref`, printed -921081130 on gcc). The escape
    scan used to count that as a store into the local.

  The receiver, the box and the local handle each printed garbage on gcc before this round,
  where clang printed 1360. They are in the scratchpad's `w23r3/rf2`, `rf3` and `rf4`. The
  operator overload applies the same rule to an operator's operands, which the round-2 code
  never checked.

## Open decisions

These are questions about the language that only the user can settle. The current behaviour
is documented, and the evalorder golden pins the behaviour of OD-1 and OD-2. OD-1 and OD-2
are memory-safe. OD-3 is not: it is a memory-safety hole in the language rules, and the
emitter cannot close it alone.

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
- **An explicit view is not deferred.** `total(gl.view(), grow())` is refused instead (round 2,
  above): the view is an expression with operands of its own (`gl.from(gidx)` reads `gidx`),
  so it cannot be moved after its siblings without breaking D33 for those operands. The
  message asks for the later operand in a local first. Under choice (b) the implicit
  conversion (`total(gl, grow())`) would be refused the same way.

### OD-3. A view held across a statement that grows what it views dangles

- **What.** D5 lets a `View` of a `List` or a `Str` live in a local, but no rule stops the
  viewed container from growing, or being reassigned, while the view lives:
  `v: View<Int32> = gl.view()`, then `k: Int32 = growL()`, then `trace(total(v) + k)`. No
  evaluation order is involved: each statement is one full expression. `kira::View` is a
  pointer and a length, so after `growL()` reallocates `gl`, `v` points at freed storage.
  gcc printed -2039998058 where clang printed 13, and MSVC's `/fsanitize=address` reports
  heap-use-after-free. The same happens through a `View` parameter, since the callee may grow
  the caller's `List` through a global. It also happens to a `mut` parameter or a struct's
  `this` bound to a `List` element, when the callee grows that `List` through a global before
  it writes: `storeAfterGrowth(mut gls[0], 7)`, whose body calls `growGls()` and then does
  `into = v`. There gcc segfaulted and MSVC's ASan reported a heap-use-after-free. In C++ each
  of these is a reference, and D37 says nothing about the storage behind it.
- **Where.** It is a language rule, not an emitter one. Design 3.4's `EscapePass` refuses only
  a view returned from a local or stored in a class field. `ExclusivityPass` refuses
  overlapping `mut` arguments and a loop that mutates what it iterates. Neither covers a
  live view or reference across a growth that the callee reaches through a global.
- **Reproduce.** The scratchpad probes `w23r2/e5` (the three lines above, with `growL` pushing
  64 elements onto `gl`) and `w23r2/e8` (`storeAfterGrowth`). Both were measured on gcc and
  zig c++, and under MSVC ASan.
- **The choice.** (a) A borrow rule in ExclusivityPass: while a view of a place, or a `mut`
  parameter or `this` bound to it, is live, no write may move the place's storage. That
  covers a growth, a reassignment, a `mut` argument, and a call that may reach the place
  through a global. It covers every case above, but it is flow-sensitive and needs
  EffectsPass's write sets. (b) Views only of storage that never moves (`Arr`, `StrBuf`, `Str`
  literals), with a `List` or `Str` copied to an `Arr` first. That is simple, but it refuses
  today's views of a `List` or a `Str` (the `text` golden slices a `Str` parameter), and it
  leaves the `mut` parameter case open. (c) A counted view that keeps its storage alive (a
  `std::shared_ptr` to the buffer). That is safe, but it costs a count per view and cannot
  be done freestanding. Until the user decides, this package's emitter refuses every case it
  can see within one statement (round 2, above). Across statements it cannot see the case.

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

  Convergence rounds 1, 2 and 3 add none. `CppExprEmitter.functionValue` reads W2.2's
  existing `ctx.placementOf(...).isNonEscapingFx`.
- **Why it can wait.** Each change is small and covered by tests. The merger routes them to
  their owners.

### KI-8. Until EffectsPass lands, a container read beside any user call is copied

- **What.** EffectsPass (W2.5) has not merged yet, so every user call is impure. A `Str`,
  struct or container that is read beside a user call, and that the callee cannot lend from,
  is deep-copied into the IIFE (D33).
- **Why it can wait.** It is a cost, not a correctness issue. When EffectsPass merges, its
  `PURE` entries remove every copy whose sibling cannot write.

### KI-9. Until EscapePass merges, a view of a local can leave its function (pending on W2.5)

- **What.** This branch accepts two shapes that dangle, and emits them:
  - an escaping closure that captures a view of a local. `v: View<Int32> = xs.view()` then
    `return fx () Int32 { return v.get(0) }` becomes `return [v]() ...` after `xs` has died,
    and gcc printed -511830624;
  - a direct `return xs.view()` of a local.
- **Where.** W2.5's `EscapePass` (design 3.4: "a View must not be returned when derived from a
  local"). It refuses both, and its test for the closure case is
  `aClosureCapturingAViewOfALocalEscapes...`. The C++ emitter adds no second copy of that
  rule, because a view held in a local points wherever it was made, which only the pass's
  flow analysis knows.
- **Reproduce.** The verifier's probes `verify-w23c1/c1` (the closure) and `c2`
  (`fx bad: () View<Int32> { xs: ...; return xs.view() }`). Both emit, build and run, reading
  freed memory.
- **Why it can wait.** It depends on another package (policy item 5). The rule is written
  and tested on W2.5's branch, and the merge brings it in ahead of the emitter. Within one
  statement this emitter already refuses a view into a *temporary* that a `return` keeps
  (`return tail(makeList(), 1)`), since no pass sees that one. That includes the temporaries
  the lowering makes where the source names none (round 3, above): a local that keeps a view
  into a `Str` default (the verifier's pd1, which W2.5 refuses too, as
  `rules.escape.view-store`) or into the `Maybe` copy of a place.

### KI-10. The round-2 view rules are conservative in known ways

- **What.** The rules that close the round-2 memory-safety issues are sound but coarse.
  Each item below costs a refusal the program did not need, or a spill:
  - a call whose result can hold a view is taken to point into every operand that owns or
    reaches storage. `pick(v, makeList())` in a local is refused, although `pick` returns `v`;
  - an if-expression branch whose value is a view *variable* declared in that branch is
    refused, although the variable may view outer storage;
  - a local that a `MutView` is lent from anywhere in the program is shared state in every
    expression that reads it. A read of it beside any user call is spilled, even where no
    sibling can reach the view;
  - a pure call given a view is READS even where it reads only the length (`v.size()`), which
    no sibling can change.

  Round 3 adds these. Each is again sound and coarse:
  - a callee not known statically (an `Fx` value, a virtual or trait method) keeps a view of
    a temporary argument when *any* body it may run does: every lambda and function used as
    a value of the `Fx` value's exact type (of its arity only, when the type is generic), or
    every method of that name and arity, plus every lambda of that arity when one of those
    methods has no body (a slot a construction fills). One keeping lambda anywhere in the
    program refuses every such call handed a temporary;
  - a user callee lends a `MutView` out of a `mut` argument wherever its body lets *any*
    view of the argument be stored, and not only a `MutView`. The variable is then shared
    state (a spill, never a refusal);
  - a view is taken to move before a later effect when any step of its path goes through a
    `Maybe`'s payload, a class's `this` or a handle, though a sibling that frees the object
    would make that `this` dangle anyway (OD-3's class, not an ordering one);
  - a default is a temporary for every parameter type that owns storage, except a constant
    place of the parameter's own type.
- **Where.** `CppHoister.sources`, `refuseBranchLocalView`, `mutViewRoots`, `readsThrough`,
  `keepsParameter`, `storageMoves` and `defaultTemporaries`, and `CppEscapes.dispatchStores`
  and `lendsMutView`.
- **Reproduce.** `CppHoisterTest.aViewTheLoweringWouldLeaveDanglingIsRefusedWithWhatToWriteInstead`
  and `aTemporaryTheLoweringMakesOrStorageAPathMovesIsRefusedWithWhatToWriteInstead` pin each
  refusal and its safe neighbours. The golden cases' `expected/` files are unchanged by these
  rules (none of `proto`, `unilidar`, `hall`, `text`, `strings`, `numerics` moved a byte).
  The virtual and trait arm of `dispatchStores` cannot run end to end on this branch: class
  lowering is W2.4's. Its enumeration is a superset by construction. Every method of the
  name is one of the overrides a dispatch may reach, and a slot adds every lambda.
- **Why it can wait.** None of them costs memory safety or evaluation order. A refused
  program has a one-line rewrite, which the message names: a temporary or an operand stored
  in a local first, or a defaulted parameter passed explicitly. EffectsPass's `PURE`
  entries and a provenance fact from EscapePass would make each item precise.

### KI-11. The CLI cannot reach R6's skipped middle default, so only the rows test covers it

- **What.** R6 fills a skipped middle default in at the call (`three(1, c = 9)` becomes
  `three(1, 2, 9)`). From source, `f(1, c = 5)` against
  `f: (a: Int32, b: Int32 = 2, c: Int32 = 3)` stops in the frontend with "Call to 'f' leaves
  parameter(s) without an argument: b". The C/JS semantic analyzer's named-argument check
  runs before the typer and knows no defaults. The typer's `CallResolver` binds the call,
  with `b` as a middle `ArgBinding.Default`.
- **Where.** `KiraSemanticAnalyzer.checkNamedArguments` and `core/NamedArguments.bind`. They
  are frontend code, not this package's. The emitter's side is `CppLowering.arguments`
  (`Slot.Filled`).
- **Reproduce.** The verifier's probe `vw23cr2/pn1`, through the CLI.
- **Why it can wait.** Nothing is emitted wrongly: the program is refused before the
  backend runs. The fill-in itself is emitted and run by `CppExprRowsTest`'s `r6` row, which
  types the module without the semantic analyzer. It checks `r6::skipMiddle() == 129` under
  the warning contract on gcc, clang, msvc and zig-aarch64. The fix is for the frontend's
  owner: let `NamedArguments.bind` treat a parameter with a default as bound.
