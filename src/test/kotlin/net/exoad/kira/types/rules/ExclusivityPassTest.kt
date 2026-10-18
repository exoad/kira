package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExclusivityPassTest {
    private val bag = """
        pub Bag: Struct {
            pub a: Int32 = 0
            pub b: Int32 = 0
            pub items: Arr<Int32> = []
            pub mut absorb: (other: Bag) Void {
                a += other.a
            }
            pub take: (mut other: Bag) Void {
                other.a = a
            }
        }
        pub swap: (mut x: Int32, mut y: Int32) Void {
            t: Int32 = x
            x = y
            y = t
        }
        pub put: (mut x: Int32, v: Int32) Void {
            x = v
        }
        pub grow: (mut xs: Arr<Int32>) Void {
            xs.set(0, 1)
        }
        pub sum: (v: View<Int32>) Int32 {
            return 0
        }
        pub fill: (mut xs: Arr<Int32>, v: View<Int32>) Void {
            xs.set(0, v[0])
        }
        pub sz: (n: Size, v: Int32) Int32 {
            return v
        }
        pub pair: (a: Int32, b: Int32) Int32 {
            return a + b
        }
        pub bump: (mut x: Int32) Int32 {
            x += 1
            return x
        }
        pub Counter: Struct {
            pub a: Int32 = 0
            pub items: Arr<Int32> = []
            pub mut grow: () Int32 {
                a += 1
                return a
            }
            pub peek: () Int32 {
                return a
            }
            pub mut reset: () Void {
                a = 0
            }
        }
        pub Cache: Any {
            pub mut items: List<Int32> = List<Int32> {}
            pub mut push: (v: Int32) Void {
                items.add(v)
            }
            pub mut grown: () Int32 {
                items.add(1)
                return 1
            }
        }
    """

    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun distinctPlacesMayBeMixed() {
        expectClean(
            snippet(
                bag + """
                pub f: () Void {
                    mut a: Int32 = 1
                    mut b: Int32 = 2
                    swap(mut a, mut b)
                    put(mut a, b)
                    mut s: Bag = Bag {}
                    put(mut s.a, s.b)
                    mut t: Bag = Bag {}
                    s.absorb(t)
                    s.take(mut t)
                }
                """,
            ),
        )
    }

    @Test
    fun aValueComputedBeforeTheCallIsNoOverlap() {
        expectClean(
            snippet(
                bag + """
                pub f: () Void {
                    mut xs: Arr<Int32> = [1, 2]
                    n: Size = xs.size()
                    put(mut xs[0], n as Int32)
                    grow(mut xs)
                }
                """,
            ),
        )
    }

    @Test
    fun aLoopMayWriteWhatItDoesNotIterate() {
        expectClean(
            snippet(
                bag + """
                pub f: (xs: Arr<Int32>) Int32 {
                    mut total: Int32 = 0
                    mut out: List<Int32> = List<Int32> {}
                    mut s: Bag = Bag {}
                    for x: Int32 in xs {
                        total += x
                        out.add(x)
                    }
                    for y: Int32 in s.items {
                        s.a += y
                    }
                    return total
                }
                """,
            ),
        )
    }

    @Test
    fun siblingsThatWriteNothingAnotherReadsAreInOrder() {
        // A call's receiver is sequenced before its arguments in C++, so a mut receiver beside an argument
        // that reads it is the receiver rule's business, not the order rule's; a nested write beside a
        // sibling that reads something else is fine, and so are two reads.
        expectClean(
            snippet(
                bag + """
                pub f: () Int32 {
                    mut b: Counter = Counter {}
                    mut n: Int32 = 1
                    t: Int32 = sz(b.items.size(), b.peek())
                    u: Int32 = pair(b.peek(), bump(mut n))
                    v: Int32 = pair(bump(mut n), 1) + b.grow()
                    w: Int32 = b.grow() + 1
                    put(mut b.a, b.peek())
                    mut c: Cache = Cache {}
                    c.items.add(c.grown())
                    c.push(bump(mut n))
                    return t + u + v + w
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aByValueReadBesideANamedWriteIsReadFirstAndTwoWritesOfOnePlaceAreRefused() {
        // F1 (40-round3 3.2): a by-value argument or an operator operand that reads the written place is copied
        // first by D33 (the emitter raises a local a sibling writes to READS), so t, u and v are in order; two
        // operands that both write b are a place written twice by one call, which stays refused.
        val p = snippet(
            bag + """
            pub f: () Int32 {
                mut b: Counter = Counter {}
                mut n: Int32 = 1
                t: Int32 = sz(b.items.size(), b.grow())
                u: Int32 = pair(n, bump(mut n))
                v: Int32 = b.a + b.grow()
                w: Int32 = pair(b.grow(), b.grow())
                mut c: Cache = Cache {}
                x: Int32 = sz(c.items.size(), bump(mut n))
                return t + u + v + w + x
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").startsWith("'b.grow()' writes 'b' while one operand of 'pair' is evaluated, and 'b.grow()' writes it again"))
    }

    @Test
    fun aMutArgumentMustNotOverlapAnotherArgument() {
        val p = snippet(
            bag + """
            pub f: () Void {
                mut a: Int32 = 1
                swap(mut a, mut a)
                put(mut a, a)
                mut s: Bag = Bag {}
                put(mut s.a, s.a)
                grow(mut s.items)
                mut xs: Arr<Int32> = [1, 2]
                put(mut xs[0], sum(xs.view()))
                fill(mut xs, xs.view())
            }
            """,
        )
        // `sum(xs.view())` is a value, not a place: no overlap. `xs.view()` lends xs itself: overlap.
        // fill(mut xs, xs.view()) is ViewPass's as well: a named write of the place a view in use points into (3.3).
        expectExactly(p, "rules.view.write", "rules.exclusivity.argument", "rules.exclusivity.argument", "rules.exclusivity.argument", "rules.exclusivity.argument")
        assertTrue(message(p, "rules.exclusivity.argument").contains("D37"))
    }

    @Test
    fun aMutArgumentMustNotOverlapTheReceiver() {
        val p = snippet(
            bag + """
            pub f: () Void {
                mut s: Bag = Bag {}
                s.take(mut s)
                s.absorb(s)
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.receiver", "rules.exclusivity.receiver")
    }

    @Test
    fun aMutViewLendsAWriteOfWhatItWasLentFrom() {
        // fill's MutView parameter writes arr, and a sibling operand reads an element of arr by value. F1: the
        // element is a value D33 copies first (the emitter raises a read of the MutView's source to READS, its
        // `writtenBy`), whether spelled arr[0] or arr.get(0) (R-A: one place), or read through a MutView parameter;
        // the order rule refuses none. A MutView of arr beside arr itself in one call is D37's argument rule.
        val p = snippet(
            """
            pub fill: (v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return 1
            }
            pub pair8: (a: UInt8, b: UInt8) UInt8 {
                return a
            }
            pub both: (xs: Arr<UInt8, 4>, v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return xs[0]
            }
            pub viaParam: (mv: MutView<UInt8>) UInt8 {
                return pair8(mv[0], fill(mv))
            }
            pub f: () UInt8 {
                mut arr: Arr<UInt8, 4> = [1, 2, 3, 4]
                a: UInt8 = pair8(arr[0], fill(arr.view()))
                c: UInt8 = arr[0] + fill(arr.view())
                d: UInt8 = pair8(arr.get(0), fill(arr.from(0)))
                g: UInt8 = both(arr, arr.view())
                return a + c + d + g
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.argument")
        assertTrue(message(p, "rules.exclusivity.argument").startsWith("the MutView 'arr.view()' overlaps the argument 'arr' of 'both'"))
    }

    @Test
    fun anElementReadThroughAViewBesideAWriteOfItsStorageIsAValueReadFirst() {
        // The other way round: the sibling writes the storage a view's element is read from (F1: an Int32 value,
        // copied first; R-A: xs.view()[0] is the place xs[0]). A mut argument the sibling writes is refused.
        val p = snippet(
            bag + """
            pub f: () Int32 {
                mut xs: Arr<Int32> = [1, 2]
                a: Int32 = pair(xs.view()[0], bump(mut xs[1]))
                put(mut xs[0], bump(mut xs[1]))
                return a
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").contains("and 'xs[0]' is passed to it as mut"), message(p, "rules.exclusivity.order"))
    }

    @Test
    fun cppSequencesTheseTheOtherWayRound() {
        // Round 2, issue 3: a compound assignment evaluates its right side first in C++17 (`n += bump(mut n)`
        // gives 4 there and 3 in Kira), a class construction is a make_shared call, a call's receiver is an
        // operand (Str methods bind to free functions; a place receiver is a reference), an assignment's index
        // is evaluated after its value, and an interpolation is a kira::cat call.
        val p = snippet(
            bag + """
            pub Box: Any {
                pub a: Int32 = 0
                pub b: Int32 = 0
            }
            pub Pt: Struct {
                pub a: Int32 = 0
                pub b: Int32 = 0
            }
            pub app: (mut s: Str) Size {
                s = s + "x"
                return 1 as Size
            }
            pub bumpS: (mut x: Size) Size {
                x += 1 as Size
                return x
            }
            pub Ctr: Struct {
                pub mut a: Int32 = 0
                pub mut grow: () Int32 {
                    a += 1
                    return a
                }
                pub plus: (v: Int32) Int32 {
                    return a + v
                }
            }
            pub f: () Int32 {
                mut n: Int32 = 1
                n += bump(mut n)
                bx: Box = Box { n, bump(mut n) }
                pt: Pt = Pt { n, bump(mut n) }
                arr: Arr<Int32, 2> = [n, bump(mut n)]
                mut s: Str = "abc"
                t: Str = s.substring(0 as Size, app(mut s))
                mut xs: Arr<Int32, 2> = [1, 2]
                mut i: Size = 0
                xs[i] = bump(mut n) + (bumpS(mut i) as Int32)
                n = bump(mut n)
                xs[0] = bump(mut n)
                u: Str = "${'$'}{n} and ${'$'}{bump(mut n)}"
                mut c: Ctr = Ctr {}
                v: Int32 = c.plus(c.grow())
                mut k: CCtr = CCtr {}
                w: Int32 = k.plus(k.grow())
                return n + bx.a + pt.a + arr[0] + xs[0] + w
            }
            pub CCtr: Any {
                pub mut a: Int32 = 0
                pub mut grow: () Int32 {
                    a += 1
                    return a
                }
                pub plus: (v: Int32) Int32 {
                    return a + v
                }
            }
            """,
        )
        // F1 and OQ-1 (READ FIRST): the compound target n and the Str receiver s are immutable values, read first,
        // and every other operand here is a by-value read D33 copies first; a plain assignment's target (and its
        // index) is located after its value, as C++17 does. Only the mutable struct receiver c of `c.plus(...)`
        // stays refused: Q4 reads it when plus runs, which W2.9.8 lowers. On a class, `k->plus(k->grow())` reads
        // the reference before the arguments and the fields after them, as Kira does.
        expectExactly(p, "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").startsWith("'c.grow()' writes 'c' while one operand of 'plus' is evaluated, and 'c' is the receiver, a container or a mutable value"))
    }

    @Test
    fun aLoopBodyMayNotChangeTheCollectionThroughACalleeOrAMutView() {
        // Round 2, issue 5: note() adds to the global LOG being iterated; push adds to this.items being
        // iterated; fill writes xs through a MutView lent from it. In C++ each is push_back under a range-for.
        val p = snippet(
            """
            pub mut LOG: List<Int32> = List<Int32> {}
            pub note: () Void {
                LOG.add(1)
            }
            pub quiet: () Int32 {
                return LOG.size() as Int32
            }
            pub fill: (v: MutView<Int32>) Int32 {
                v.set(0, 9)
                return 1
            }
            pub Box: Any {
                pub mut items: List<Int32> = List<Int32> {}
                pub mut other: List<Int32> = List<Int32> {}
                pub mut push: (v: Int32) Void {
                    items.add(v)
                }
                pub mut pushOther: (v: Int32) Void {
                    other.add(v)
                }
                pub mut walk: () Int32 {
                    mut n: Int32 = 0
                    for z: Int32 in items {
                        pushOther(z)
                        n += z
                    }
                    for z: Int32 in other {
                        push(z)
                    }
                    for z: Int32 in items {
                        push(z)
                    }
                    return n
                }
            }
            pub f: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    n += quiet()
                    note()
                }
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                for x: Int32 in xs {
                    n += fill(xs.view())
                }
                mut b: Box = Box {}
                for x: Int32 in b.items {
                    b.pushOther(x)
                }
                for x: Int32 in b.other {
                    b.pushOther(x)
                }
                return n
            }
            """,
        )
        // A `mut fx` with a body writes what its body writes (round 3, issue 7: pushOther on b while b.items is
        // iterated writes only b.other, so it is no invalidation); the hidden-write rule sees a callee the call
        // site does not show writing (note on LOG, push on items).
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("iterates 'LOG', and its body calls 'note', which writes 'LOG'") }, messages.joinToString("\n"))
        assertTrue(messages.none { it.contains("quiet") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'xs', and its body passes a MutView of 'xs.view()'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'items', and its body calls 'push', which writes 'this.items'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'b.other', and its body calls 'pushOther', which writes 'b.other'") }, messages.joinToString("\n"))
        assertTrue(messages.none { it.contains("iterates 'b.items'") }, messages.joinToString("\n"))
    }

    @Test
    fun aLoopBodyHiddenWriteThroughAnOverrideATraitOrAnFxValueIsSeen() {
        // Round 3, issue 5: a virtual call runs whichever override the object has, a trait call whichever
        // implementor, and a callee handed a lambda may call it; each is a push_back under the range-for.
        val p = snippet(
            """
            pub mut LOG: List<Int32> = List<Int32> {}
            pub A: Any {
                pub act: () Void { }
            }
            pub B: A {
                override pub act: () Void {
                    LOG.add(1)
                }
            }
            pub T: Any {
                pub act: () Void;
            }
            pub SI: Struct, T {
                override pub act: () Void {
                    LOG.add(1)
                }
            }
            pub run: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            pub l1: (a: A) Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    a.act()
                    n += x
                }
                return n
            }
            pub l2: (t: T) Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    t.act()
                    n += x
                }
                return n
            }
            pub l3: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    n += run(() Int32 {
                        LOG.add(1)
                        return 1
                    })
                }
                return n
            }
            pub l4: () Int32 {
                mut n: Int32 = 0
                g: Fx<Tuple0, Int32> = () Int32 {
                    LOG.add(1)
                    return 1
                }
                for x: Int32 in LOG {
                    n += g()
                }
                return n
            }
            pub ok: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    n += run(() Int32 {
                        return 1
                    })
                }
                return n
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("iterates 'LOG', and its body calls 'act', which writes 'LOG'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'LOG', and its body calls 'run', which writes 'LOG'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'LOG', and its body calls 'g', which writes 'LOG'") }, messages.joinToString("\n"))
    }

    @Test
    fun aLoopOverAViewACalleeLentAndAWriteThroughAViewAliasInACalleeAreSeen() {
        // Round 3, issue 6: `half(xs)` returns a view of xs, so the loop iterates xs (ViewPass's origin of the range,
        // which it refuses too: a for over a local's view is rules.view.position); and poke writes GL through a
        // MutView it lends, which its caller cannot see at the call site.
        val p = snippet(
            """
            pub mut GL: List<Int32> = List<Int32> {}
            pub half: (a: View<Int32>) View<Int32> {
                return a
            }
            pub set5: (mv: MutView<Int32>) Void {
                mv[0 as Size] = 5
            }
            pub poke: () Void {
                set5(GL.view())
            }
            pub l1: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                for x: Int32 in half(xs) {
                    xs.add(x)
                }
                return 0
            }
            pub l2: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in GL {
                    poke()
                    n += x
                }
                return n
            }
            """,
        )
        // poke's set5(GL.view()) is ViewPass's too: set5 writes through the MutView it is lent, so it is impure, and GL
        // is a mut global (decision 4b read literally, round 2).
        expectExactly(p, "rules.view.write", "rules.view.position", "rules.exclusivity.loop", "rules.exclusivity.loop")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("iterates 'half(xs)', and its body calls the `mut fx` 'add' on it") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'GL', and its body calls 'poke', which writes 'GL'") }, messages.joinToString("\n"))
    }

    @Test
    fun aByValueArgumentTheCalleeWritesIsAcceptedAndItsCalleeIsNotConfined() {
        // Round 4 (50-round4 1.3) deletes rules.exclusivity.alias: f(GS) hands f a copy in Kira, and the emitter now
        // copies GS at the call (T(e), W2.3's policy) because f is not CONFINED: it writes a global, so its caller may
        // not lend it the global. g and h write a global too. What the policy reads is CallReach.confined.
        val p = snippet(
            """
            pub S: Struct {
                pub a: Int32 = 0
            }
            pub mut GS: S = S {}
            pub mut GN: Int32 = 0
            pub mut GT: S = S {}
            pub bumpGS: () Int32 {
                GS.a += 1
                return GS.a
            }
            pub f: (p: S) Int32 {
                n: Int32 = bumpGS()
                return p.a + n
            }
            pub g: (p: Int32) Int32 {
                GN += 1
                return p
            }
            pub h: (p: S) Int32 {
                GN += 1
                return p.a
            }
            pub main: () Int32 {
                return f(GS) + g(GN) + h(GS) + f(GT)
            }
            """,
        )
        expectClean(p)
        assertEquals(mapOf("f" to listOf(false, false), "g" to listOf(false), "h" to listOf(false)), listOf("f", "g", "h").associateWith { confinedCalls(p, it) })
    }

    @Test
    fun aLoopMustNotChangeWhatItIterates() {
        val p = snippet(
            bag + """
            pub f: () Void {
                mut xs: List<Int32> = List<Int32> {}
                for x: Int32 in xs {
                    xs.add(x)
                }
                mut ys: Arr<Int32> = [1]
                for y: Int32 in ys {
                    grow(mut ys)
                }
                for z: Int32 in ys {
                    ys[0] = z
                }
                mut s: Bag = Bag {}
                for w: Int32 in s.items {
                    s = Bag {}
                }
                mut k: Counter = Counter {}
                for v: Int32 in k.items {
                    k.reset()
                }
                mut c: Cache = Cache {}
                for z: Int32 in c.items {
                    c.push(z)
                }
            }
            """,
        )
        // Round 3, issue 7: reset writes k.a, not k.items, so it is no invalidation; push writes c.items.
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("iterates 'xs', and its body calls the `mut fx` 'add' on it") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("calls 'push', which writes 'c.items'") }, messages.joinToString("\n"))
        assertTrue(messages.none { it.contains("reset") }, messages.joinToString("\n"))
    }

    @Test
    fun aClassReceiverASiblingMayRebindIsAcceptedItsHandleIsCopiedFirst() {
        // Round 4 (50-round4 1.6) deletes order clause 1: `k.plus(rebind(mut k))` evaluated `k.operator->()` to a raw K*
        // before the argument (round-4-of-convergence issue 1, MSVC ASan heap-use-after-free); the emitter now copies
        // the handle in Kira's order, kira::Rc<K>(k)->plus(...), which holds the object for the call, so every row
        // below is accepted and runs on the object Kira read (W2.3's policy tests run them).
        val p = snippet(
            """
            pub K: Any {
                pub mut n: Int32 = 0
                pub mut grow: () Int32 {
                    n += 1
                    return n
                }
                pub plus: (v: Int32) Int32 {
                    return n + v
                }
                pub mut add: (v: Int32) Int32 {
                    n += v
                    return n
                }
            }
            pub Holder: Struct {
                pub k: K
                pub mut swap: () Int32 {
                    k = K { n = 9 }
                    return 1
                }
            }
            pub rebind: (mut k: K) Int32 {
                k = K { n = 5 }
                return 1
            }
            pub reset: (mut h: Holder) Int32 {
                h = Holder { k = K {} }
                return 1
            }
            pub bumpI: (mut i: Size) Int32 {
                i += 1 as Size
                return 1
            }
            pub mk: () K {
                return K {}
            }
            pub c1: () Int32 {
                mut k: K = K {}
                return k.plus(rebind(mut k))
            }
            pub c3: (c: Bool) Int32 {
                mut k: K = K {}
                return k.plus(if c {
                    k = K { n = 5 }
                    1
                } else {
                    2
                })
            }
            pub c4: (c: Bool) Int32 {
                mut k: K = K {}
                return k.add(if c {
                    k = K { n = 5 }
                    1
                } else {
                    2
                })
            }
            pub c5: () Int32 {
                mut st: Holder = Holder { k = K {} }
                a: Int32 = st.k.plus(reset(mut st))
                b: Int32 = st.k.plus(st.swap())
                return a + b
            }
            pub ok: () Int32 {
                mut k: K = K {}
                mut st: Holder = Holder { k = K {} }
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                mut i: Size = 0
                mut k2: K = K {}
                a: Int32 = k.plus(k.grow())
                b: Int32 = st.k.plus(st.k.grow())
                c: Int32 = ks[i].plus(bumpI(mut i))
                d: Int32 = mk().plus(rebind(mut k2))
                return a + b + c + d
            }
            """,
        )
        expectClean(p)
    }

    @Test
    fun aClassArgumentTheCalleeRebindsIsAcceptedAndItsCalleeIsNotConfined() {
        // Round 4 deletes rules.exclusivity.alias: readSwapped(child), whose body rebinds this.child, is handed a copy
        // of the handle at the call (kira::Rc<Node>(child)), because readSwapped is not CONFINED (it writes a field of
        // a class object). The emitter decides that from CallReach.confined, asserted here.
        val p = snippet(
            """
            pub Node: Any {
                pub mut n: Int32 = 0
                pub plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub Scene: Any {
                pub mut child: Node = Node {}
                pub bump: () Void {
                    child.n += 1
                }
                pub mut swap: () Void {
                    child = Node { n = 5 }
                }
                pub read: (k: Node) Int32 {
                    bump()
                    return k.n
                }
                pub mut readSwapped: (k: Node) Int32 {
                    swap()
                    return k.n
                }
                pub mut go: () Int32 {
                    a: Int32 = read(child)
                    b: Int32 = readSwapped(child)
                    c: Int32 = child.plus(1)
                    return a + b + c
                }
            }
            pub outside: (sc: Scene) Int32 {
                a: Int32 = sc.read(sc.child)
                b: Int32 = sc.readSwapped(sc.child)
                c: Int32 = sc.child.plus(1)
                return a + b + c
            }
            """,
        )
        expectClean(p)
        assertEquals(listOf(false, false), confinedCalls(p, "readSwapped"))
        assertEquals(listOf(false, false), confinedCalls(p, "read"))
    }

    @Test
    fun aSiblingThatRebindsTheClassReceiversPlaceOutOfSightIsAccepted() {
        // Round 4 deletes order clause 1 and rules.exclusivity.alias: each receiver handle (sc.k, k) is copied first in
        // Kira's order and holds its K for the call (g++ ran plus on the destroyed K when it was not: -776 for Kira's
        // 1), and readAfter(sc.k, sc) is handed a copy of sc.k because readAfter is not CONFINED.
        val p = snippet(
            """
            pub K: Any {
                pub mut n: Int32 = 0
                pub plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub Sc: Any {
                pub mut k: K = K {}
                pub mut c: Int32 = 0
                pub mut resetK: () Void {
                    k = K { n = 5 }
                }
                pub mut bumpC: () Int32 {
                    c += 1
                    return 1
                }
                pub mut swapOut: () Int32 {
                    k = K { n = 5 }
                    return 1
                }
                pub mut go: () Int32 {
                    return k.plus(swapOut())
                }
            }
            pub resetVia: (sc: Sc) Int32 {
                sc.resetK()
                return 1
            }
            pub resetDirect: (sc: Sc) Int32 {
                sc.k = K { n = 5 }
                return 1
            }
            pub readAfter: (k: K, sc: Sc) Int32 {
                sc.resetK()
                return k.n
            }
            pub r1: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(resetVia(sc))
            }
            pub r2: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(resetDirect(sc))
            }
            pub r3: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(sc.swapOut())
            }
            pub r4: () Int32 {
                sc: Sc = Sc {}
                g: Fx<Tuple0, Int32> = () Int32 {
                    sc.k = K { n = 5 }
                    return 1
                }
                return sc.k.plus(g())
            }
            pub r5: () Int32 {
                sc: Sc = Sc {}
                return readAfter(sc.k, sc)
            }
            pub ok: () Int32 {
                sc: Sc = Sc {}
                a: Int32 = sc.k.plus(sc.bumpC())
                b: Int32 = sc.k.plus(sc.k.n)
                c: Int32 = readAfter(sc.k, Sc {})
                return a + b + c
            }
            """,
        )
        expectClean(p)
        assertEquals(listOf(false, false), confinedCalls(p, "readAfter"))
    }

    @Test
    fun aReferenceReceiverReturnedByAMagicCallIsAcceptedItsHandleIsCopiedFirst() {
        // Round 4: m.unwrap() and ks.get(0) are lent results, places (R-A), of class type; the emitter copies the
        // handle they name before the sibling runs (kira::Rc<K>(kira::unwrap(m))), so the rebind no longer frees
        // the object plus runs on (unwrap_rebind.cxx: plus ran on K(-777) before clause 1 existed).
        val p = snippet(
            """
            pub K: Any {
                pub mut n: Int32 = 0
                pub plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub rebindM: (mut m: Maybe<K>) Int32 {
                m = K { n = 5 }
                return 1
            }
            pub rebindList: (mut ks: List<K>) Int32 {
                ks = List<K> {}
                return 1
            }
            pub bumpI: (mut i: Size) Int32 {
                i += 1 as Size
                return 1
            }
            pub rebind: (mut k: K) Int32 {
                k = K { n = 5 }
                return 1
            }
            pub mk: () K {
                return K {}
            }
            pub g1: () Int32 {
                mut m: Maybe<K> = K {}
                return m.unwrap().plus(rebindM(mut m))
            }
            pub g2: () Int32 {
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                return ks.get(0 as Size).plus(rebindList(mut ks))
            }
            pub g3: (c: Bool) Int32 {
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                return ks.get(0 as Size).plus(if c {
                    ks.add(K {})
                    1
                } else {
                    2
                })
            }
            pub ok: () Int32 {
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                mut i: Size = 0
                mut k: K = K {}
                a: Int32 = ks.get(i).plus(bumpI(mut i))
                b: Int32 = mk().plus(rebind(mut k))
                return a + b
            }
            """,
        )
        expectClean(p)
    }

    @Test
    fun aFieldReadThroughAMagicCallsReferenceIsAcceptedItsHandleIsCopiedFirst() {
        // Round 4: ks.get(0).child, m.unwrap().child and hs.get(0).k are receivers of class type, their handles copied
        // first in Kira's order (50-round4 1.6), so rebinding the container no longer frees what plus runs on.
        val p = snippet(
            """
            pub K: Any {
                pub mut n: Int32 = 0
                pub plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub Node: Any {
                pub mut child: K = K {}
            }
            pub Holder: Struct {
                pub mut k: K = K {}
            }
            pub rebindList: (mut ks: List<Node>) Int32 {
                ks = List<Node> {}
                return 1
            }
            pub rebindM: (mut m: Maybe<Node>) Int32 {
                m = Node {}
                return 1
            }
            pub rebindHs: (mut hs: List<Holder>) Int32 {
                hs = List<Holder> {}
                return 1
            }
            pub g1: () Int32 {
                mut ks: List<Node> = List<Node> {}
                ks.add(Node {})
                return ks.get(0 as Size).child.plus(rebindList(mut ks))
            }
            pub g2: () Int32 {
                mut m: Maybe<Node> = Node {}
                return m.unwrap().child.plus(rebindM(mut m))
            }
            pub g3: () Int32 {
                mut hs: List<Holder> = List<Holder> {}
                hs.add(Holder {})
                return hs.get(0 as Size).k.plus(rebindHs(mut hs))
            }
            """,
        )
        expectClean(p)
    }

    @Test
    fun aHiddenWriteBesideAnImmutableValueIsReadFirstAndBesideAContainerReceiverIsRefused() {
        // A write hidden in a callee (bumpG writes G) beside an argument or an operator's operand that reads G is
        // READS beside IMPURE, which the emitter copies into temporaries in source order (TypedModel.Effect), and
        // an assignment's target is a place the emitter binds in order. The user's OQ-1 (READ FIRST): an immutable
        // value's operand is read before the call, so the Str receiver of S.startsWith and the Int32 target of
        // G += are copied first too. Q4 reads a container receiver when its call runs, which W2.9.8 lowers: until
        // then GL.get(bumpL()) and GL.size() + ... on a List are refused, the index spelling GL[bumpL()] is not
        // (kira::at reads GL when it runs, Q4's answer already).
        val p = snippet(
            """
            pub mut G: Int32 = 0
            pub mut S: Str = "ab"
            pub mut GL: List<Int32> = List<Int32> { values = [1, 2] }
            pub bumpG: () Int32 {
                G += 1
                return 0
            }
            pub growS: () Str {
                S = S + "c"
                return "a"
            }
            pub bumpL: () Size {
                GL.set(0, 50)
                return 0
            }
            pub add2: (x: Int32, y: Int32) Int32 {
                return x + y
            }
            pub f: () Int32 {
                mut xs: Arr<Int32> = [1, 2, 3]
                a: Int32 = add2(G, bumpG())
                b: Int32 = G + bumpG()
                G += bumpG()
                xs[G as Size] = bumpG()
                c: Bool = S.startsWith(growS())
                d: Int32 = GL[bumpL()]
                e: Int32 = GL.get(bumpL())
                return a + b + d + e
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").startsWith("'bumpL()' writes 'GL' inside its callee while one operand of 'get' is evaluated, and 'GL' is the receiver"), message(p, "rules.exclusivity.order"))
        assertEquals(listOf(Effect.READS, Effect.IMPURE), listOf(BodyTestSupport.all<Expr>(p, "G")[1], BodyTestSupport.all<Expr>(p, "bumpG()")[0]).map { p.model.effects[it] })
    }

    // ---- round 3 (40-round3 3.2, F1; the user's OQ-1 READ FIRST) ----------------------------------

    private val evalorder = """
        pub mut ticks: Int32 = 0
        pub mut gl: List<Int32> = List<Int32> { }
        pub mut gm: Map<Int32, Int32> = Map<Int32, Int32> { }
        pub next: () Int32 {
            ticks += 1
            return ticks
        }
        pub sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }
        pub inc: (mut x: Int32) Int32 {
            x += 1
            return x
        }
        pub lenOf: (xs: List<Int32>, n: Int32) Int32 {
            return xs.size() as Int32 + n
        }
        pub pushTo: (mut xs: List<Int32>) Int32 {
            xs.add(3)
            return 0
        }
        pub poke: (v: MutView<Int32>) Int32 {
            v.set(0, 9)
            return 0
        }
        pub putKey: () Int32 {
            gm.put(1, 99)
            return 1
        }
        pub setFirst: () Size {
            gl.set(0, 50)
            return 0
        }
        pub Acc: Struct {
            pub n: Int32 = 1
            pub mut bump: () Int32 {
                n = 2
                return 10
            }
            pub plus: (k: Int32) Int32 {
                return n * 100 + k
            }
        }
        pub mut ga: Acc = Acc { }
        pub bumpA: () Int32 {
            ga.bump()
            return 10
        }
    """

    @Test
    fun theFiveEvalorderLinesTheOrderRuleRefusedWronglyAreAccepted() {
        // 143, 231, 233, 236 and 259 of W2.3's evalorder golden: a compound target of an immutable value (READ
        // FIRST), by-value arguments beside a named write (D33 copies them), an Int32 element beside a MutView of
        // its List. Also the index spelling gl[setFirst()] (Q4 already) and the spelled-out twins of 143.
        val p = snippet(
            evalorder + """
            pub f: () Int32 {
                ticks += next()
                mut x: Int32 = 5
                a: Int32 = sub(x, inc(mut x))
                mut xs: List<Int32> = List<Int32> { values = [1, 2] }
                b: Int32 = lenOf(xs, pushTo(mut xs))
                mut z: Int32 = 5
                z += inc(mut z)
                mut ws: List<Int32> = List<Int32> { values = [1, 2, 3] }
                c: Int32 = sub(ws[0], poke(ws.from(0)))
                d: Int32 = gl[setFirst()]
                ticks = ticks + next()
                e: Int32 = sub(ticks, next())
                return a + b + c + d + e + z
            }
            """,
        )
        expectClean(p)
    }

    @Test
    fun theFiveReceiverLinesStayRefusedUntilQ4IsLoweredImplicitThisIncluded() {
        // 192, 195, 222, 389 and 393 (the implicit this the round-2 rule missed): a Map, a List, a mutable struct
        // global, an explicit and an implicit this of a struct whose mut fx writes it. Q4 reads each receiver when
        // the call runs; W2.9.8 lowers that (99, 50, 210, 210, 210).
        val p = snippet(
            evalorder + """
            pub Acc2: Struct {
                pub n: Int32 = 1
                pub mut bump: () Int32 {
                    n = 2
                    return 10
                }
                pub plus: (k: Int32) Int32 {
                    return n * 100 + k
                }
                pub mut viaThis: () Int32 {
                    return this.plus(bump())
                }
                pub mut viaImplicit: () Int32 {
                    return plus(bump())
                }
            }
            pub f: () Int32 {
                a: Int32 = gm.get(putKey()).unwrapOr(-1)
                b: Int32 = gl.get(setFirst())
                c: Int32 = ga.plus(bumpA())
                return a + b + c
            }
            """,
        )
        expectExactly(p, *Array(5) { "rules.exclusivity.order" })
        val messages = p.diagnostics.map { it.message }
        for (text in listOf("and 'gm' is the receiver", "and 'gl' is the receiver", "and 'ga' is the receiver")) {
            assertTrue(messages.any { it.contains(text) }, "$text:\n" + messages.joinToString("\n"))
        }
        assertEquals(2, messages.count { it.startsWith("'bump()' writes 'this' while one operand of 'plus' is evaluated, and 'this' is the receiver") }, messages.joinToString("\n"))
    }

    @Test
    fun aMutPlaceASiblingWritesIsRefusedAndTwoCallsWritingOnePlaceBesideAValueAreNot() {
        // Clause 2: a mut place is bound by reference and never copied, so a sibling's named write of it has no order
        // both languages keep. Its hidden twin (G bound mut beside bumpG()) and a class mut fx are no refusal: the
        // reference names the place either way.
        val p = snippet(
            evalorder + """
            pub mut G: Int32 = 0
            pub bumpG: () Int32 {
                G += 1
                return 0
            }
            pub two: (mut x: Int32, y: Int32) Int32 {
                x += y
                return x
            }
            pub f: () Int32 {
                mut x: Int32 = 1
                a: Int32 = two(mut x, inc(mut x))
                b: Int32 = two(mut G, bumpG())
                return a + b
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").startsWith("'mut x' writes 'x' while one operand of 'two' is evaluated, and 'x' is passed to it as mut"))
    }

    @Test
    fun aContainerOrMutableValueAsAnOperatorsReceiverIsHeldToo() {
        // `a == b` is `a.@_op_eq_(b)` and `a += b` is `a = a.@_op_add_(b)` (DECISIONS 2): the left operand is the
        // receiver, so a container there is Q4's (read when the operator runs) like a method call's receiver; an
        // immutable value (an Int32, a Str, a Bool) is read first (OQ-1).
        val p = snippet(
            evalorder + """
            pub mut S: Str = "a"
            pub mut GA: Arr<Int32, 2> = [1, 2]
            pub resetL: () List<Int32> {
                gl = List<Int32> { }
                return List<Int32> { values = [3] }
            }
            pub resetA: () Arr<Int32, 2> {
                GA = [5, 6]
                return [1, 2]
            }
            pub growS: () Str {
                S = S + "b"
                return "a"
            }
            pub f: () Bool {
                a: Bool = gl == resetL()
                b: Bool = GA == resetA()
                c: Bool = S == growS()
                d: Bool = gl.isEmpty() == resetL().isEmpty()
                e: Int32 = ticks + next()
                return a && b && c && d && e > 0
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order")
        assertTrue(p.diagnostics.all { it.message.contains("is the receiver of the operator, a container or a mutable value") }, TyperTestSupport.render(p))
    }

    // ---- round 2's loop minors (w2-5 minor #2): a finally a drop runs, and two handles of one object ------

    @Test
    fun aLoopBodyWritingTheIteratedFieldThroughAnotherHandleOfTheClassIsRefused() {
        // q6: g and h may name one object, so g.reset() replacing g.items may invalidate a loop over h.items; a write
        // of another field through g (g.bump()) is none.
        val p = snippet(
            """
            pub Holder: Any {
                pub mut items: List<Int32> = List<Int32> { values = [1, 2] }
                pub mut n: Int32 = 0
                pub mut reset: () Void {
                    items = List<Int32> { }
                }
                pub mut bump: () Void {
                    n += 1
                }
            }
            pub mk: () Holder {
                return Holder { }
            }
            pub f: (h: Holder, g: Holder) Int32 {
                mut s: Int32 = 0
                for x: Int32 in h.items {
                    g.reset()
                    s += x
                }
                for y: Int32 in h.items {
                    g.bump()
                    s += y
                }
                for z: Int32 in mk().items {
                    g.reset()
                    s += z
                }
                return s
            }
            """,
        )
        // The third loop iterates a temporary's field, through the copy W2.3's statement part makes: not refused.
        expectExactly(p, "rules.exclusivity.loop")
        assertTrue(message(p, "rules.exclusivity.loop").contains("calls 'reset', which writes 'g.items'"), message(p, "rules.exclusivity.loop"))
    }

    // ---- round 4 (50-round4 2.7): rule M, a mut place is bound only where nothing can move or free it ----

    /** CONFINED (CallReach.confined, what W2.3's policy and rule M read) of every call of [name] in [p], in source order. */
    private fun confinedCalls(p: TypedProgram, name: String): List<Boolean> =
        BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { call ->
            val rc = p.model.calls[call]?.takeIf { it.fn?.name == name } ?: return@mapNotNull null
            CallReach.confined(rc, p.model)
        }

    private val hooks = """
        pub mut HOOKS: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> {}
        pub runHooks: () Void {
            for h: Fx<Tuple0, Void> in HOOKS {
                h()
            }
        }
        pub bumpAfter: (mut x: Int32) Void {
            runHooks()
            x += 1
        }
        pub inc: (mut x: Int32) Void {
            x += 1
        }
    """

    @Test
    fun ruleMAcceptsAPrivateOrStableMutPlaceWhateverTheCalleeRuns() {
        // bumpAfter runs the Fx of a global List, which may do anything, so it is not CONFINED; each place here is
        // PRIVATE (a local, an element of one) or STABLE (a value-class field at a fixed offset in an object held for
        // the call: this of a class, the object behind a local or by-value handle; or the caller's own mut place,
        // which rule M kept still at the caller; or a trait's this, a held object or a value kept still). A hook can
        // overwrite such storage in place, which is what mut means (R19), but cannot move or free it.
        val p = snippet(
            hooks + """
            pub Pose: Struct {
                pub mut x: Int32 = 0
            }
            pub H: Any {
                pub mut pose: Pose = Pose {}
                pub mut n: Int32 = 0
                pub mut step: () Void {
                    bumpAfter(mut this.n)
                    bumpAfter(mut pose.x)
                }
            }
            pub S: Struct {
                pub mut n: Int32 = 0
                pub mut own: () Void {
                    bumpAfter(mut n)
                }
            }
            pub fwd: (mut x: Int32, mut q: Pose) Void {
                bumpAfter(mut x)
                bumpAfter(mut q.x)
            }
            pub Poker: Any {
                pub mut poke: () Void;
                pub nudge: () Void {
                    runHooks()
                    poke()
                }
            }
            pub f: (p: H) Void {
                mut a: Int32 = 0
                bumpAfter(mut a)
                mut xs: List<Int32> = List<Int32> { values = [1] }
                bumpAfter(mut xs[0])
                h: H = H {}
                bumpAfter(mut h.n)
                bumpAfter(mut h.pose.x)
                bumpAfter(mut p.pose.x)
            }
            """,
        )
        expectClean(p)
        assertTrue(confinedCalls(p, "bumpAfter").none { it }, "bumpAfter runs the hooks: not CONFINED")
    }

    @Test
    fun ruleMAcceptsAGlobalPassedToAConfinedCallAndRefusesItBesideCodeItCannotSee() {
        // A global and an element of a global List are neither PRIVATE nor STABLE: a hook run from a global List may
        // replace or clear them while the callee writes through the T&. inc and List.add run no such code.
        val p = snippet(
            hooks + """
            pub mut G: Int32 = 0
            pub mut GL: List<Int32> = List<Int32> { values = [1] }
            pub f: () Void {
                inc(mut G)
                GL.add(1)
                inc(mut GL[0])
                bumpAfter(mut G)
                bumpAfter(mut GL[0])
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.mut", "rules.exclusivity.mut")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("the mut argument 'G', a global, is passed to 'bumpAfter', which may run code that moves or frees it") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("the mut argument 'GL[0]', an element of a list, is passed to 'bumpAfter'") }, messages.joinToString("\n"))
        assertEquals(listOf(true, true), confinedCalls(p, "inc"))
        assertEquals(listOf(true), confinedCalls(p, "add"))
    }

    private val externItem = """
        pub Item: Any {
            pub label: Str
            pub mut count: Int32 = 0
            pub mut xs: List<Int32> = List<Int32> {}
        }
        pub Box: Any {
            pub mut item: Item = Item { "short" }
            pub mut reset: () Void {
                item = Item { "other" }
            }
        }
        pub Wrap: Any {
            pub f: Fx<Tuple0, Void>
        }
        pub bump: (mut n: Int32, f: Fx<Tuple0, Void>) Void;
        pub bumpList: (mut n: Int32, fs: List<Fx<Tuple0, Void>>) Void;
        pub bumpWrap: (mut n: Int32, w: Wrap) Void;
    """

    @Test
    fun ruleMRefusesW24sExternShapesAndAnExternHandedAnyLambda() {
        // externmut and externarr (round 2's W2.4 verifier, MSVC ASan heap-use-after-free): the callback frees the Item
        // that h.item.count lies in while the prototype writes through the int&. h.item is a handle step, so the place
        // is not STABLE, and a prototype handed anything that may hold an Fx is not CONFINED (2.3's extern row,
        // !mayRunAnything): a lambda literal too, even one that writes nothing, since C++ may call it with what it
        // chooses (round 4's e1: a lambda that runs its own Fx parameter, handed a hook C++ kept).
        val p = snippet(
            externItem + """
            pub run: (f: Fx<Tuple0, Void>) Int32 {
                h: Box = Box {}
                c: Box = h
                bump(mut h.item.count, () Void {
                    c.reset()
                })
                bumpList(mut h.item.count, List<Fx<Tuple0, Void>> { values = [() Void { c.reset() }] })
                bumpWrap(mut h.item.count, Wrap { f = () Void { c.reset() } })
                bump(mut h.item.count, f)
                bump(mut h.item.count, () Void { })
                return h.item.count
            }
            """,
        )
        expectExactly(p, *Array(5) { "rules.exclusivity.mut" })
        assertTrue(p.diagnostics.all { it.message.startsWith("the mut argument 'h.item.count', storage reached through a handle, is passed to 'bump") }, TyperTestSupport.render(p))
        assertEquals(listOf(false, false, false), confinedCalls(p, "bump"))
    }

    @Test
    fun ruleMRefusesTwoMutOperandsOfAConfinedCallWhenOneMayHoldTheOther() {
        // g and h may name one Box: swapItem replaces the Item through `it` while C++ still holds a List& into the old
        // Item's xs. swapItem is CONFINED (it writes only its own mut parameters), so the other operand decides: an Item
        // may hold a List. Two locals of their own are PRIVATE with different roots and pass.
        val p = snippet(
            externItem + """
            pub swapItem: (mut xs: List<Int32>, mut it: Item) Void {
                it = Item { "new" }
                xs.add(1)
            }
            pub f: () Void {
                g: Box = Box {}
                h: Box = g
                swapItem(mut g.item.xs, mut h.item)
                mut ys: List<Int32> = List<Int32> {}
                mut other: Item = Item { "o" }
                swapItem(mut ys, mut other)
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.mut")
        assertTrue(message(p, "rules.exclusivity.mut").startsWith("the mut argument 'g.item.xs', storage reached through a handle, is passed to 'swapItem' beside 'h.item'"), message(p, "rules.exclusivity.mut"))
        assertEquals(listOf(true, true), confinedCalls(p, "swapItem"))
    }

    @Test
    fun theAliasRowsOfW24sVerifierAreAcceptedAndTheirCalleesAreNotConfined() {
        // w2-4 #0 [owner w2-5] (rp1e 'new', rp1b and rp1g heap-use-after-free): the alias rule missed a function named
        // as a value and an Fx inside a List. Round 4 deletes the rule; the emitter copies gs and gl[0] at the call
        // because neither viaFx nor viaList is CONFINED, whatever spelling hands the hook over.
        val p = snippet(
            """
            pub mut gs: Str = "old"
            pub mut gl: List<Str> = List<Str> { values = ["a long list element, past the small buffer"] }
            pub rewrite: () Void {
                gs = "new"
                gl = List<Str> {}
            }
            pub viaFx: (s: Str, f: Fx<Tuple0, Void>) Str {
                f()
                return s
            }
            pub viaList: (s: Str, fs: List<Fx<Tuple0, Void>>) Str {
                for f: Fx<Tuple0, Void> in fs {
                    f()
                }
                return s
            }
            pub both: () Str {
                a: Str = viaFx(gs, rewrite)
                b: Str = viaList(gl[0], List<Fx<Tuple0, Void>> { values = [rewrite] })
                c: Str = viaFx(gs, () Void { })
                return a + b + c
            }
            """,
        )
        expectClean(p)
        assertEquals(listOf(false, true), confinedCalls(p, "viaFx"))
        assertEquals(listOf(false), confinedCalls(p, "viaList"))
        // w2-5 minor #0 (probe alb): readAfterH frees h.items before it reads its const& parameter, safe in round 3
        // only by W2.4's entry snapshot, which round 4 deletes. Both spellings are accepted, and readAfterH is not
        // CONFINED (it writes through a handle), so the element is copied at the call.
        val alb = snippet(
            """
            pub H: Any {
                pub mut items: List<Str> = List<Str> { values = ["a long list element, past the small buffer"] }
            }
            pub pick: (h: H) H {
                return h
            }
            pub readAfterH: (s: Str, h: H) Int32 {
                h.items = List<Str> {}
                return s.length() as Int32
            }
            pub both: (h: H) Int32 {
                return readAfterH(pick(h).items.get(0), h) + readAfterH(pick(h).items[0], h)
            }
            """,
        )
        expectClean(alb)
        assertEquals(listOf(false, false), confinedCalls(alb, "readAfterH"))
    }

    // ---- round 5 (w2-5 round-4 #0-#3): the routes round 4's lending-shape generator found (scratchpad w25r4v3/gen) ----

    /** gen.py's prelude: the storages, a writer that replaces every one of them, and the helpers the routes use. */
    private val genPrelude = """
        pub mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
        pub mut gl: List<Int32> = [1, 2, 3]
        pub mut gls: List<Str> = ["old-text-long-enough-to-live-on-the-heap-000000"]
        pub mut hooks: List<Fx<Tuple0, Void>> = []
        pub Node: Any {
            pub mut name: Str = "old-text-long-enough-to-live-on-the-heap-000000"
        }
        pub mut gh: Maybe<Node> = null
        pub clobber: () Void {
            gs = "new"
            gl = [100]
            gls = ["x", "y", "z", "w", "v", "u", "t", "s", "r", "q", "p", "o", "n", "m", "l", "k", "j"]
            gh = Node { name = "new" }
        }
        pub callIt: (g: Fx<Tuple0, Void>) Void {
            g()
        }
        pub pass<T>: (f: Fx<Tuple1<T>, Void>, x: T) Void {
            f(x)
        }
        pub Box<T>: Any {
            pub v: T
            pub run: (f: Fx<Tuple1<T>, Void>) Void {
                f(this.v)
            }
        }
        pub @_extern(cpp = "e::callWith", header = "e.hxx") callWith: (f: Fx<Tuple1<Fx<Tuple0, Void>>, Void>) Void;
    """

    /** The five routes round 4 lent through (each printed wrong values, with an MSVC ASan heap-use-after-free). */
    private val genRoutes = mapOf(
        // An Fx given for pass's T, run by pass through its own Fx parameter.
        "generic" to "h: Fx<Tuple0, Void> = hooks[0]\n    pass<Fx<Tuple0, Void>>(callIt, h)",
        "genlambda" to "pass<Fx<Tuple0, Void>>(callIt, () Void { clobber() })",
        // A lambda literal that calls its own Fx parameter, handed pass's T.
        "genparam" to "h: Fx<Tuple0, Void> = hooks[0]\n    pass<Fx<Tuple0, Void>>((g: Fx<Tuple0, Void>) Void { g() }, h)",
        // A generic class method handing its T to its Fx parameter: no Fx is given for a T at this call at all.
        "box" to "b: Box<Fx<Tuple0, Void>> = Box<Fx<Tuple0, Void>> { v = hooks[0] }\n    b.run(callIt)",
        // An extern handed a CONFINED lambda, which C++ calls with the hook it kept.
        "extern" to "callWith((g: Fx<Tuple0, Void>) Void { g() })",
    )

    private fun genRoute(body: String): String = "pub route: () Void {\n    $body\n}\n"

    @Test
    fun aGenericHandingItsTToItsFxAndAnExternHandedALambdaAreNotConfinedSoEveryUseIsCopied() {
        // gen/<route>_val: the four by-value uses (a global Str, a global List iterated, a field through a global
        // handle, an element of a global List<Str>) read after the route ran. Kira prints 47/6/47/47; round 4 lent all
        // four through W3 because route was CONFINED. Row 1 now charges every Fx argument, one given for a T included,
        // and a call of an Fx value charges its T argument (mayHoldFx); the extern row is !mayRunAnything.
        for ((name, body) in genRoutes) {
            val p = snippet(
                genPrelude + genRoute(body) + """
                pub lenAfter: (s: Str) Int32 {
                    route()
                    return s.length() as Int32
                }
                pub sumAfter: (xs: List<Int32>) Int32 {
                    mut t: Int32 = 0
                    for x: Int32 in xs {
                        route()
                        t += x
                    }
                    return t
                }
                pub drive: () Void {
                    trace(lenAfter(gs))
                    trace(sumAfter(gl))
                    trace(lenAfter(gh.value.name))
                    trace(lenAfter(gls[0]))
                }
                """,
            )
            assertTrue(RulesTestSupport.rules(p).isEmpty(), "$name: " + TyperTestSupport.render(p))
            assertEquals(false, p.model.fnConfined[RulesTestSupport.fn(p, "route")], name)
            assertEquals(listOf(false, false, false), confinedCalls(p, "lenAfter"), name)
            assertEquals(listOf(false), confinedCalls(p, "sumAfter"), name)
        }
        // The helpers each route goes through: pass and Box.run hand their T to their own Fx, callIt runs only its own.
        val p = snippet(genPrelude)
        assertEquals(false, p.model.fnConfined[RulesTestSupport.fn(p, "pass")])
        assertEquals(false, p.model.fnConfined[RulesTestSupport.method(p, "Box", "run")])
        assertEquals(true, p.model.fnConfined[RulesTestSupport.fn(p, "callIt")])
    }

    @Test
    fun ruleMRefusesAGlobalElementAndAGlobalHandlesFieldBesideEachRoute() {
        // gen/<route>_m1 and _m2: `setAfter(mut gls[0])` and `setAfter(mut gh.value.name)`, accepted by round 4 (gcc
        // exit 139/127, an MSVC ASan heap-use-after-free inside setAfter's assign). Neither place is PRIVATE or STABLE,
        // and setAfter is not CONFINED, so rule M refuses both, through every route.
        for ((name, body) in genRoutes) {
            for (place in listOf("gls[0]", "gh.value.name")) {
                val p = snippet(
                    genPrelude + genRoute(body) + """
                    pub setAfter: (mut s: Str) Void {
                        route()
                        s = "written-through-the-reference-after-the-route-000"
                    }
                    pub drive: () Void {
                        setAfter(mut $place)
                    }
                    """,
                )
                assertEquals(listOf("rules.exclusivity.mut"), RulesTestSupport.rules(p), "$name $place: " + TyperTestSupport.render(p))
                assertTrue(message(p, "rules.exclusivity.mut").startsWith("the mut argument '$place', "), message(p, "rules.exclusivity.mut"))
                assertEquals(listOf(false), confinedCalls(p, "setAfter"), "$name $place")
            }
        }
    }

    // ---- round 5b: 2.3 row 1 charges an Fx argument only where the callee can run it (design 7.1's r4d/p3 and p5) ----

    @Test
    fun r4dP3AndP5AreAcceptedAHookStoredInAMutGlobalIsNeverRunByTheAdd() {
        // r4d/p3 and p5 (scratchpad r4d): main adds to the mut global hooks a lambda that replaces other globals, and
        // later calls run it through runHooks. 7.1 says both must run and print Kira's value (47/6/5, and
        // old-name...:1). The add stores the lambda and never runs it, so it is CONFINED and rule M accepts the mut
        // global receiver; round 5's literal row 1 refused both at hooks.add. Every call that reaches runHooks is not
        // CONFINED, so what it reads is copied.
        val p3 = snippet(
            """
            pub mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            pub mut gl: List<Int32> = [1, 2, 3]
            pub mut hooks: List<Fx<Tuple0, Void>> = []
            pub runHooks: () Void {
                items: List<Fx<Tuple0, Void>> = hooks
                for h: Fx<Tuple0, Void> in items {
                    h()
                }
            }
            pub lenAfter: (s: Str) Int32 {
                runHooks()
                return s.length() as Int32
            }
            pub sumAfter: (xs: List<Int32>) Int32 {
                mut t: Int32 = 0
                for x: Int32 in xs {
                    runHooks()
                    t += x
                }
                return t
            }
            pub Node: Any {
                pub mut n: Int32 = 5
                pub nAfter: () Int32 {
                    runHooks()
                    return n
                }
            }
            pub mut gn: Maybe<Node> = null
            pub drive: () Void {
                hooks.add(() Void {
                    gs = "new"
                    gl = [100]
                    gn = Node { n = 9 }
                })
                trace(lenAfter(gs))
                gs = "old-text-long-enough-to-live-on-the-heap-000000"
                gl = [1, 2, 3]
                trace(sumAfter(gl))
                gn = Node {}
                trace(gn.value.nAfter())
            }
            """,
        )
        expectClean(p3)
        assertEquals(listOf(true), confinedCalls(p3, "add"))
        assertEquals(listOf(false), confinedCalls(p3, "lenAfter"))
        assertEquals(listOf(false), confinedCalls(p3, "sumAfter"))
        assertEquals(listOf(false), confinedCalls(p3, "nAfter"))
        assertEquals(false, p3.model.fnConfined[RulesTestSupport.fn(p3, "runHooks")])
        // p5 as written (a struct, which W2.9 turns into an immutable class; a class construction cannot start a mut
        // global, D49): the value receiver gt of describe, which runs the hook.
        val p5 = snippet(
            """
            pub Tag: Struct {
                pub name: Str = ""
                pub n: Int32 = 0
                pub describe: () Str {
                    runHooks()
                    return "${'$'}{name}:${'$'}{n}"
                }
            }
            pub mut gt: Tag = Tag { name = "old-name-long-enough-for-the-heap-00000000", n = 1 }
            pub mut hooks: List<Fx<Tuple0, Void>> = []
            pub runHooks: () Void {
                items: List<Fx<Tuple0, Void>> = hooks
                for h: Fx<Tuple0, Void> in items {
                    h()
                }
            }
            pub drive: () Void {
                hooks.add(() Void {
                    gt = Tag { name = "new", n = 2 }
                })
                trace(gt.describe())
            }
            """,
        )
        expectClean(p5)
        assertEquals(listOf(true), confinedCalls(p5, "add"))
        assertEquals(listOf(false), confinedCalls(p5, "describe"))
        // The control: an add whose declared parameter IS an Fx (a Kira function that runs it) is still charged.
        val ctl = snippet(
            """
            pub mut hooks: List<Fx<Tuple0, Void>> = []
            pub mut gs: Str = "old"
            pub addRun: (mut hs: List<Fx<Tuple0, Void>>, f: Fx<Tuple0, Void>) Void {
                f()
                hs.add(f)
            }
            pub drive: () Void {
                addRun(mut hooks, () Void {
                    hooks = []
                })
            }
            """,
        )
        expectExactly(ctl, "rules.exclusivity.mut")
        assertEquals(listOf(false), confinedCalls(ctl, "addRun"))
    }

    // ---- round 6 (w2-5 round-5b #1): a construction that runs an Fx it is given (scratchpad w25r5c x/t1, x/t2, gen2) ----

    @Test
    fun aThreadConstructionRunsItsBodySoItsCallerIsNotConfinedAndRuleMRefuses() {
        // x/t1 as the verifier wrote it: startAndJoin constructs a Thread, which starts its body there, and drops it,
        // which joins it. Round 5b charged nothing (the construction row read only initially and the defaults), so
        // lenAfter and sumAfter were CONFINED and W3 lent gs and gl: 3 for Kira's 47, 100 for 6.
        val t1 = snippet(
            """
            kira.sync as sync

            mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            mut gl: List<Int32> = [1, 2, 3]

            startAndJoin: () Void {
                t: Thread = Thread { name = "w", body = () Void {
                    gs = "new"
                    gl = [100]
                } }
            }

            pub lenAfter: (s: Str) Int32 {
                startAndJoin()
                return s.length() as Int32
            }

            pub sumAfter: (xs: List<Int32>) Int32 {
                startAndJoin()
                mut t: Int32 = 0
                for x: Int32 in xs {
                    t += x
                }
                return t
            }

            main: () Void {
                trace(lenAfter(gs))
                gs = "old-text-long-enough-to-live-on-the-heap-000000"
                gl = [1, 2, 3]
                trace(sumAfter(gl))
            }
            """,
        )
        assertTrue(RulesTestSupport.rules(t1).isEmpty(), TyperTestSupport.render(t1))
        assertEquals(false, t1.model.fnConfined[RulesTestSupport.fn(t1, "startAndJoin")])
        assertEquals(listOf(false), confinedCalls(t1, "lenAfter"))
        assertEquals(listOf(false), confinedCalls(t1, "sumAfter"))
        // x/t2: the body is a hook from a global List, an Fx value row 1 never passes. Round 5b accepted
        // setAfter(mut gls[0]) (gcc and clang exit 127, an MSVC ASan heap-use-after-free in lenAfter).
        val t2 = snippet(
            """
            kira.sync as sync

            mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            mut gls: List<Str> = ["old-text-long-enough-to-live-on-the-heap-000000"]
            mut hooks: List<Fx<Tuple0, Void>> = []

            startAndJoin: () Void {
                t: Thread = Thread { name = "w", body = hooks[0] }
            }

            pub lenAfter: (s: Str) Int32 {
                startAndJoin()
                return s.length() as Int32
            }

            setAfter: (mut s: Str) Void {
                startAndJoin()
                s = "written-through-the-reference-after-the-route-000"
            }

            main: () Void {
                mut hs: List<Fx<Tuple0, Void>> = []
                hs.add(() Void {
                    gs = "new"
                    gls = ["x", "y", "z", "w", "v", "u", "t", "s", "r", "q", "p", "o", "n", "m", "l", "k", "j"]
                })
                hooks = hs
                trace(lenAfter(gs))
                gs = "old-text-long-enough-to-live-on-the-heap-000000"
                trace(lenAfter(gls[0]))
                gls = ["old-text-long-enough-to-live-on-the-heap-000000"]
                setAfter(mut gls[0])
                trace(gls[0])
            }
            """,
        )
        assertEquals(listOf("rules.exclusivity.mut"), RulesTestSupport.rules(t2), TyperTestSupport.render(t2))
        assertTrue(message(t2, "rules.exclusivity.mut").startsWith("the mut argument 'gls[0]', "), message(t2, "rules.exclusivity.mut"))
        assertEquals(false, t2.model.fnConfined[RulesTestSupport.fn(t2, "startAndJoin")])
        assertEquals(listOf(false, false), confinedCalls(t2, "lenAfter"))
        assertEquals(listOf(false), confinedCalls(t2, "setAfter"))
    }

    /** gen2.py's two routes: a Thread constructed with a lambda body, and with a hook from a global List. */
    private val threadRoutes = mapOf(
        "thread" to "t: Thread = Thread { name = \"w\", body = () Void { clobber() } }",
        "threadhook" to "t: Thread = Thread { name = \"w\", body = hooks[0] }",
    )

    @Test
    fun theThreadRoutesOfTheGeneratorCopyEveryUseAndRuleMRefusesBothMutPlaces() {
        // gen/thread_* and gen/threadhook_*: 6 of 6 wrong on round 5b (both _val lent all four uses; all four _m1/_m2
        // were accepted, gcc 139/127 and an ASan use-after-free each).
        for ((name, body) in threadRoutes) {
            val p = snippet(
                "kira.sync as sync\n" + genPrelude + genRoute(body) + """
                pub lenAfter: (s: Str) Int32 {
                    route()
                    return s.length() as Int32
                }
                pub sumAfter: (xs: List<Int32>) Int32 {
                    mut t: Int32 = 0
                    for x: Int32 in xs {
                        route()
                        t += x
                    }
                    return t
                }
                pub drive: () Void {
                    trace(lenAfter(gs))
                    trace(sumAfter(gl))
                    trace(lenAfter(gh.value.name))
                    trace(lenAfter(gls[0]))
                }
                """,
            )
            assertTrue(RulesTestSupport.rules(p).isEmpty(), "$name: " + TyperTestSupport.render(p))
            assertEquals(false, p.model.fnConfined[RulesTestSupport.fn(p, "route")], name)
            assertEquals(listOf(false, false, false), confinedCalls(p, "lenAfter"), name)
            assertEquals(listOf(false), confinedCalls(p, "sumAfter"), name)
            for (place in listOf("gls[0]", "gh.value.name")) {
                val m = snippet(
                    "kira.sync as sync\n" + genPrelude + genRoute(body) + """
                    pub setAfter: (mut s: Str) Void {
                        route()
                        s = "written-through-the-reference-after-the-route-000"
                    }
                    pub drive: () Void {
                        setAfter(mut $place)
                    }
                    """,
                )
                assertEquals(listOf("rules.exclusivity.mut"), RulesTestSupport.rules(m), "$name $place: " + TyperTestSupport.render(m))
                assertTrue(message(m, "rules.exclusivity.mut").startsWith("the mut argument '$place', "), message(m, "rules.exclusivity.mut"))
                assertEquals(listOf(false), confinedCalls(m, "setAfter"), "$name $place")
            }
        }
    }

    @Test
    fun aThreadBodyRowOnePassesIsConfinedAndAnFxParameterIsChargedWhereItIsHandedIn() {
        // The controls: the charge is row 1's, not "a Thread is never CONFINED". A body that writes only its own local is
        // CONFINED, so the caller's use is lent as before; a body that is the function's own Fx parameter is charged at
        // the call that hands it in, as spawn(name, body) is.
        val p = snippet(
            """
            kira.sync as sync
            pub mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            pub quiet: () Void {
                t: Thread = Thread { name = "w", body = () Void {
                    mut k: Int32 = 0
                    k += 1
                } }
            }
            pub startWith: (f: Fx<Tuple0, Void>) Void {
                t: Thread = Thread { name = "w", body = f }
            }
            pub lenAfter: (s: Str) Int32 {
                quiet()
                return s.length() as Int32
            }
            pub drive: () Void {
                trace(lenAfter(gs))
                startWith(() Void { })
                startWith(() Void {
                    gs = "new"
                })
            }
            """,
        )
        expectClean(p)
        assertEquals(true, p.model.fnConfined[RulesTestSupport.fn(p, "quiet")])
        assertEquals(true, p.model.fnConfined[RulesTestSupport.fn(p, "startWith")])
        assertEquals(listOf(true), confinedCalls(p, "lenAfter"))
        assertEquals(listOf(true, false), confinedCalls(p, "startWith"))
    }

    // ---- round 7 (w2-5 round-6 #1, W2.3's KI-20): a STABLE receiver of a binding that drops what it held mid-operation ----

    /** The mut-operand refusals of [p], as (the receiver or argument text, the callee's name). */
    private fun dropRefusals(p: TypedProgram): List<Pair<String, String>> =
        p.diagnostics.filter { it.code == "rules.exclusivity.mut" }.map { d ->
            assertTrue(d.message.contains("which drops what it held while it still works on the container"), d.message)
            Regex("^the (?:receiver|mut argument) '([^']+)'.*? is passed to '([^']+)'").find(d.message)!!.let { it.groupValues[1] to it.groupValues[2] }
        }

    /** `CallReach.dropsMidCall` at every call of [name] in [p], in source order. */
    private fun dropsMid(p: TypedProgram, name: String): List<Boolean> =
        BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { call ->
            val rc = p.model.calls[call]?.takeIf { it.fn?.name == name } ?: return@mapNotNull null
            CallReach.dropsMidCall(rc, p.model, rc.receiver?.let { p.model.types[it] })
        }

}
