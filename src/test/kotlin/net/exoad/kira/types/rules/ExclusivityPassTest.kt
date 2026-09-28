package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
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
        pub struct Bag {
            pub a: Int32 = 0
            pub b: Int32 = 0
            pub items: Arr<Int32> = []
            pub mut fx absorb: (other: Bag) Void {
                a += other.a
            }
            pub fx take: (mut other: Bag) Void {
                other.a = a
            }
        }
        pub fx swap: (mut x: Int32, mut y: Int32) Void {
            t: Int32 = x
            x = y
            y = t
        }
        pub fx put: (mut x: Int32, v: Int32) Void {
            x = v
        }
        pub fx grow: (mut xs: Arr<Int32>) Void {
            xs.set(0, 1)
        }
        pub fx sum: (v: View<Int32>) Int32 {
            return 0
        }
        pub fx fill: (mut xs: Arr<Int32>, v: View<Int32>) Void {
            xs.set(0, v[0])
        }
        pub fx sz: (n: Size, v: Int32) Int32 {
            return v
        }
        pub fx pair: (a: Int32, b: Int32) Int32 {
            return a + b
        }
        pub fx bump: (mut x: Int32) Int32 {
            x += 1
            return x
        }
        pub struct Counter {
            pub a: Int32 = 0
            pub items: Arr<Int32> = []
            pub mut fx grow: () Int32 {
                a += 1
                return a
            }
            pub fx peek: () Int32 {
                return a
            }
            pub mut fx reset: () Void {
                a = 0
            }
        }
        pub class Cache {
            pub mut items: List<Int32> = List<Int32> {}
            pub mut fx push: (v: Int32) Void {
                items.add(v)
            }
            pub mut fx grown: () Int32 {
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
                pub fx f: () Void {
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
                pub fx f: () Void {
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
                pub fx f: (xs: Arr<Int32>) Int32 {
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
                pub fx f: () Int32 {
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
    fun aPlaceWrittenByOneArgumentAndReadByItsSiblingHasNoOrderInCpp() {
        val p = snippet(
            bag + """
            pub fx f: () Int32 {
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
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order")
        assertTrue(message(p, "rules.exclusivity.order").contains("'b.grow()' writes 'b' while one operand of 'sz' is evaluated, and 'b.items' is read by another"))
    }

    @Test
    fun aMutArgumentMustNotOverlapAnotherArgument() {
        val p = snippet(
            bag + """
            pub fx f: () Void {
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
            pub fx f: () Void {
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
        // D33 (round 2, issue 1): fill's MutView parameter writes arr, and a sibling operand reads arr; the read
        // is PURE (a local), so R19 never spills it, and only this rule keeps the order. The view is lent at the
        // call (arr.view(), arr.from(0)), or is a MutView parameter (mv): no view is held in a local or a field
        // (decision 4b, ViewPass).
        val p = snippet(
            """
            pub fx fill: (v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return 1
            }
            pub fx pair8: (a: UInt8, b: UInt8) UInt8 {
                return a
            }
            pub fx both: (xs: Arr<UInt8, 4>, v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return xs[0]
            }
            pub fx viaParam: (mv: MutView<UInt8>) UInt8 {
                return pair8(mv[0], fill(mv))
            }
            pub fx f: () UInt8 {
                mut arr: Arr<UInt8, 4> = [1, 2, 3, 4]
                a: UInt8 = pair8(arr[0], fill(arr.view()))
                c: UInt8 = arr[0] + fill(arr.view())
                d: UInt8 = pair8(arr.get(0), fill(arr.from(0)))
                g: UInt8 = both(arr, arr.view())
                return a + c + d + g
            }
            """,
        )
        expectExactly(
            p,
            "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order",
            "rules.exclusivity.argument",
        )
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'the MutView 'arr.view()'' writes 'arr' while one operand of 'pair8' is evaluated, and 'arr[0]' is read") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'the MutView 'mv'' writes 'mv' while one operand of 'pair8'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("the MutView 'arr.view()' overlaps the argument 'arr' of 'both'") }, messages.joinToString("\n"))
    }

    @Test
    fun aMutViewReadOnlyBesideAWriteOfItsStorageIsCaughtToo() {
        // The other way round: the sibling writes the storage a view reads (lent where it is read: no view is held
        // in a local, decision 4b).
        val p = snippet(
            bag + """
            pub fx f: () Int32 {
                mut xs: Arr<Int32> = [1, 2]
                return pair(xs.view()[0], bump(mut xs[1]))
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order")
    }

    @Test
    fun cppSequencesTheseTheOtherWayRound() {
        // Round 2, issue 3: a compound assignment evaluates its right side first in C++17 (`n += bump(mut n)`
        // gives 4 there and 3 in Kira), a class construction is a make_shared call, a call's receiver is an
        // operand (Str methods bind to free functions; a place receiver is a reference), an assignment's index
        // is evaluated after its value, and an interpolation is a kira::cat call.
        val p = snippet(
            bag + """
            pub class Box {
                pub a: Int32 = 0
                pub b: Int32 = 0
            }
            pub struct Pt {
                pub a: Int32 = 0
                pub b: Int32 = 0
            }
            pub fx app: (mut s: Str) Size {
                s = s + "x"
                return 1 as Size
            }
            pub fx bumpS: (mut x: Size) Size {
                x += 1 as Size
                return x
            }
            pub struct Ctr {
                pub mut a: Int32 = 0
                pub mut fx grow: () Int32 {
                    a += 1
                    return a
                }
                pub fx plus: (v: Int32) Int32 {
                    return a + v
                }
            }
            pub fx f: () Int32 {
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
            pub class CCtr {
                pub mut a: Int32 = 0
                pub mut fx grow: () Int32 {
                    a += 1
                    return a
                }
                pub fx plus: (v: Int32) Int32 {
                    return a + v
                }
            }
            """,
        )
        // A struct construction and an array literal are sequenced; `n = bump(mut n)` writes n after its value.
        // Round 3, issue 7: on a class, `k->plus(k->grow())` reads the reference before the arguments and the
        // fields after them, in C++17 as in Kira, so the receiver of a reference type is no sibling read.
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("one operand of the assignment is evaluated, and 'n' is read") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("one operand of the construction of Box is evaluated") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("one operand of 'substring' is evaluated, and 's' is read") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("one operand of the assignment is evaluated, and 'i' is read") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("one operand of the string is evaluated") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("'c.grow()' writes 'c' while one operand of 'plus' is evaluated, and 'c' is read") }, messages.joinToString("\n"))
    }

    @Test
    fun aLoopBodyMayNotChangeTheCollectionThroughACalleeOrAMutView() {
        // Round 2, issue 5: note() adds to the global LOG being iterated; push adds to this.items being
        // iterated; fill writes xs through a MutView lent from it. In C++ each is push_back under a range-for.
        val p = snippet(
            """
            pub mut LOG: List<Int32> = List<Int32> {}
            pub fx note: () Void {
                LOG.add(1)
            }
            pub fx quiet: () Int32 {
                return LOG.size() as Int32
            }
            pub fx fill: (v: MutView<Int32>) Int32 {
                v.set(0, 9)
                return 1
            }
            pub class Box {
                pub mut items: List<Int32> = List<Int32> {}
                pub mut other: List<Int32> = List<Int32> {}
                pub mut fx push: (v: Int32) Void {
                    items.add(v)
                }
                pub mut fx pushOther: (v: Int32) Void {
                    other.add(v)
                }
                pub mut fx walk: () Int32 {
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
            pub fx f: () Int32 {
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
            pub class A {
                pub fx act: () Void { }
            }
            pub class B: A {
                override pub fx act: () Void {
                    LOG.add(1)
                }
            }
            pub trait T {
                pub fx act: () Void;
            }
            pub struct SI: T {
                override pub fx act: () Void {
                    LOG.add(1)
                }
            }
            pub fx run: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            pub fx l1: (a: A) Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    a.act()
                    n += x
                }
                return n
            }
            pub fx l2: (t: T) Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    t.act()
                    n += x
                }
                return n
            }
            pub fx l3: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    n += run(fx() Int32 {
                        LOG.add(1)
                        return 1
                    })
                }
                return n
            }
            pub fx l4: () Int32 {
                mut n: Int32 = 0
                g: Fx<Tuple0, Int32> = fx() Int32 {
                    LOG.add(1)
                    return 1
                }
                for x: Int32 in LOG {
                    n += g()
                }
                return n
            }
            pub fx ok: () Int32 {
                mut n: Int32 = 0
                for x: Int32 in LOG {
                    n += run(fx() Int32 {
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
            pub fx half: (a: View<Int32>) View<Int32> {
                return a
            }
            pub fx set5: (mv: MutView<Int32>) Void {
                mv[0 as Size] = 5
            }
            pub fx poke: () Void {
                set5(GL.view())
            }
            pub fx l1: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                for x: Int32 in half(xs) {
                    xs.add(x)
                }
                return 0
            }
            pub fx l2: () Int32 {
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
    fun aByValueArgumentMustNotAliasWhatTheCalleeWrites() {
        // Round 3, issue 9: design 5.1 passes a struct, a Str or a container by `const&`, so `f(GS)` hands f a
        // reference to the global it writes: Kira's p is a copy that stays 0, C++'s p sees the bump. A scalar
        // is copied in both, and a place the callee never writes is fine.
        val p = snippet(
            """
            pub struct S {
                pub a: Int32 = 0
            }
            pub mut GS: S = S {}
            pub mut GN: Int32 = 0
            pub mut GT: S = S {}
            pub fx bumpGS: () Int32 {
                GS.a += 1
                return GS.a
            }
            pub fx f: (p: S) Int32 {
                n: Int32 = bumpGS()
                return p.a + n
            }
            pub fx g: (p: Int32) Int32 {
                GN += 1
                return p
            }
            pub fx h: (p: S) Int32 {
                GN += 1
                return p.a
            }
            pub fx main: () Int32 {
                return f(GS) + g(GN) + h(GS) + f(GT)
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.alias")
        assertTrue(message(p, "rules.exclusivity.alias").startsWith("'f' writes 'GS.a', and the argument 'GS' is passed to it by reference (design 5.1)"))
    }

    @Test
    fun aLoopMustNotChangeWhatItIterates() {
        val p = snippet(
            bag + """
            pub fx f: () Void {
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
    fun aClassReceiverIsReadAsAReferenceAndRebindingItIsTheOneHazard() {
        // Round 4, issue 1: `k->plus(rebind(k))` evaluates `k.operator->()` to a raw K* before the argument
        // (C++17), and rebind drops the last reference, so plus runs on a freed object (MSVC ASan: heap-use-
        // after-free). Kira keeps the reference alive until the call returns. A write of the variable holding
        // the reference, or of a place around it, is the hazard: a mut argument, an assignment inside an
        // if-expression, a mut fx on a struct holding it. A class mut fx on the same place writes the object,
        // which both languages read after the arguments, and what the receiver expression reads on the way
        // is sequenced before the arguments in both. A call result is kept alive by its own temporary.
        val p = snippet(
            """
            pub class K {
                pub mut n: Int32 = 0
                pub mut fx grow: () Int32 {
                    n += 1
                    return n
                }
                pub fx plus: (v: Int32) Int32 {
                    return n + v
                }
                pub mut fx add: (v: Int32) Int32 {
                    n += v
                    return n
                }
            }
            pub struct Holder {
                pub k: K
                pub mut fx swap: () Int32 {
                    k = K { n = 9 }
                    return 1
                }
            }
            pub fx rebind: (mut k: K) Int32 {
                k = K { n = 5 }
                return 1
            }
            pub fx reset: (mut h: Holder) Int32 {
                h = Holder { k = K {} }
                return 1
            }
            pub fx bumpI: (mut i: Size) Int32 {
                i += 1 as Size
                return 1
            }
            pub fx mk: () K {
                return K {}
            }
            pub fx c1: () Int32 {
                mut k: K = K {}
                return k.plus(rebind(mut k))
            }
            pub fx c3: (c: Bool) Int32 {
                mut k: K = K {}
                return k.plus(if c {
                    k = K { n = 5 }
                    1
                } else {
                    2
                })
            }
            pub fx c4: (c: Bool) Int32 {
                mut k: K = K {}
                return k.add(if c {
                    k = K { n = 5 }
                    1
                } else {
                    2
                })
            }
            pub fx c5: () Int32 {
                mut st: Holder = Holder { k = K {} }
                a: Int32 = st.k.plus(reset(mut st))
                b: Int32 = st.k.plus(st.swap())
                return a + b
            }
            pub fx ok: () Int32 {
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
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'mut k' writes 'k' while one operand of 'plus' is evaluated, and 'k' is the receiver, a reference read before the arguments") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'k = K { n = 5 }' writes 'k' while one operand of 'plus' is evaluated, and 'k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'k = K { n = 5 }' writes 'k' while one operand of 'add' is evaluated, and 'k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'mut st' writes 'st' while one operand of 'plus' is evaluated, and 'st.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'st.swap()' writes 'st' while one operand of 'plus' is evaluated, and 'st.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.all { it.contains("C++ holds only a raw pointer, and rebinding the last reference destroys the object") }, messages.joinToString("\n"))
    }

    @Test
    fun aClassArgumentOrReceiverAliasesOnlyWhenTheCalleeRebindsIt() {
        // Round 4, issue 5: a class is shared by Kira and C++ alike, so a callee writing the fields of the object
        // an argument refers to (Scene.read calling bump, which writes this.child.n) is seen the same way on
        // both sides: no alias. Only rebinding the place diverges: readSwapped(child), where readSwapped rebinds
        // this.child, reads the new object through its `const Rc<Node>&` in C++ and the old one in Kira. The
        // receiver of a class `mut fx` with a body (readSwapped itself, `mut fx` as the spec asks, round 5
        // issue 8) is no object passed by reference twice: its body's writes decide (bump's `child.n` beside
        // the argument `child` is fine).
        val p = snippet(
            """
            pub class Node {
                pub mut n: Int32 = 0
                pub fx plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub class Scene {
                pub mut child: Node = Node {}
                pub fx bump: () Void {
                    child.n += 1
                }
                pub mut fx swap: () Void {
                    child = Node { n = 5 }
                }
                pub fx read: (k: Node) Int32 {
                    bump()
                    return k.n
                }
                pub mut fx readSwapped: (k: Node) Int32 {
                    swap()
                    return k.n
                }
                pub mut fx go: () Int32 {
                    a: Int32 = read(child)
                    b: Int32 = readSwapped(child)
                    c: Int32 = child.plus(1)
                    return a + b + c
                }
            }
            pub fx outside: (sc: Scene) Int32 {
                a: Int32 = sc.read(sc.child)
                b: Int32 = sc.readSwapped(sc.child)
                c: Int32 = sc.child.plus(1)
                return a + b + c
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.alias", "rules.exclusivity.alias")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'readSwapped' writes 'this.child', and the argument 'child' is that reference (design 5.1): Kira hands 'readSwapped' the reference as it was, but C++ passes a `const Rc&` to the variable itself, and after the write 'readSwapped' reads the new object") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'readSwapped' writes 'sc.child', and the argument 'sc.child' is that reference") }, messages.joinToString("\n"))
    }

    @Test
    fun aSiblingThatRebindsTheClassReceiversPlaceOutOfSightIsCaught() {
        // Round 5, issues 3, 9 and 10: the object a class receiver names is destroyed by any rebind of its place
        // during the arguments, not only a visible one. resetVia(sc) rebinds sc.k through sc.resetK() (a class
        // mut fx on a by-value parameter, D29), resetDirect(sc) assigns sc.k straight, sc.swapOut() rebinds
        // this.k, g() is a lambda that captured sc, and inside the class k.plus(swapOut()) does the same. g++
        // runs plus on the destroyed K (hidden_rebind.cxx: -776 where Kira gives 1). A class mut fx with a body
        // writes what its body writes, so sc.bumpC(), which writes only c, is in order (issue 9), and
        // readAfter(sc.k, sc), which rebinds sc.k through its second parameter, aliases the first (issue 10).
        val p = snippet(
            """
            pub class K {
                pub mut n: Int32 = 0
                pub fx plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub class Sc {
                pub mut k: K = K {}
                pub mut c: Int32 = 0
                pub mut fx resetK: () Void {
                    k = K { n = 5 }
                }
                pub mut fx bumpC: () Int32 {
                    c += 1
                    return 1
                }
                pub mut fx swapOut: () Int32 {
                    k = K { n = 5 }
                    return 1
                }
                pub mut fx go: () Int32 {
                    return k.plus(swapOut())
                }
            }
            pub fx resetVia: (sc: Sc) Int32 {
                sc.resetK()
                return 1
            }
            pub fx resetDirect: (sc: Sc) Int32 {
                sc.k = K { n = 5 }
                return 1
            }
            pub fx readAfter: (k: K, sc: Sc) Int32 {
                sc.resetK()
                return k.n
            }
            pub fx r1: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(resetVia(sc))
            }
            pub fx r2: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(resetDirect(sc))
            }
            pub fx r3: () Int32 {
                sc: Sc = Sc {}
                return sc.k.plus(sc.swapOut())
            }
            pub fx r4: () Int32 {
                sc: Sc = Sc {}
                g: Fx<Tuple0, Int32> = fx() Int32 {
                    sc.k = K { n = 5 }
                    return 1
                }
                return sc.k.plus(g())
            }
            pub fx r5: () Int32 {
                sc: Sc = Sc {}
                return readAfter(sc.k, sc)
            }
            pub fx ok: () Int32 {
                sc: Sc = Sc {}
                a: Int32 = sc.k.plus(sc.bumpC())
                b: Int32 = sc.k.plus(sc.k.n)
                c: Int32 = readAfter(sc.k, Sc {})
                return a + b + c
            }
            """,
        )
        expectExactly(
            p,
            "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order",
            "rules.exclusivity.order", "rules.exclusivity.alias",
        )
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'swapOut()' writes 'this.k' inside its callee while one operand of 'plus' is evaluated, and 'k' is the receiver, a reference read before the arguments") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'resetVia(sc)' writes 'sc.k' inside its callee while one operand of 'plus' is evaluated, and 'sc.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'resetDirect(sc)' writes 'sc.k' inside its callee while one operand of 'plus' is evaluated, and 'sc.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'sc.swapOut()' writes 'sc.k' inside its callee while one operand of 'plus' is evaluated, and 'sc.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'g()' writes 'sc.k' inside its callee while one operand of 'plus' is evaluated, and 'sc.k' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'readAfter' writes 'sc.k', and the argument 'sc.k' is that reference") }, messages.joinToString("\n"))
    }

    @Test
    fun aReferenceReceiverReturnedByAMagicCallLivesAsLongAsItsReceiversPlace() {
        // Round 5, issue 1: m.unwrap() is kira::unwrap(const std::shared_ptr<U>&), which returns that const&, and
        // ks.get(0) is kira::at, a reference into the list, so the raw K* C++17 takes before the arguments dies
        // with the rebind (unwrap_rebind.cxx: plus runs on K(-777)). A Kira function returns its Rc by value,
        // so mk().plus(rebind(mut k)) is kept alive by the temporary, and a scalar argument the magic call took
        // by value (i) is no part of that lifetime.
        val p = snippet(
            """
            pub class K {
                pub mut n: Int32 = 0
                pub fx plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub fx rebindM: (mut m: Maybe<K>) Int32 {
                m = K { n = 5 }
                return 1
            }
            pub fx rebindList: (mut ks: List<K>) Int32 {
                ks = List<K> {}
                return 1
            }
            pub fx bumpI: (mut i: Size) Int32 {
                i += 1 as Size
                return 1
            }
            pub fx rebind: (mut k: K) Int32 {
                k = K { n = 5 }
                return 1
            }
            pub fx mk: () K {
                return K {}
            }
            pub fx g1: () Int32 {
                mut m: Maybe<K> = K {}
                return m.unwrap().plus(rebindM(mut m))
            }
            pub fx g2: () Int32 {
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                return ks.get(0 as Size).plus(rebindList(mut ks))
            }
            pub fx g3: (c: Bool) Int32 {
                mut ks: List<K> = List<K> {}
                ks.add(K {})
                return ks.get(0 as Size).plus(if c {
                    ks.add(K {})
                    1
                } else {
                    2
                })
            }
            pub fx ok: () Int32 {
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
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'mut m' writes 'm' while one operand of 'plus' is evaluated, and 'm.unwrap()' is the receiver, a reference read before the arguments") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'mut ks' writes 'ks' while one operand of 'plus' is evaluated, and 'ks.get(0 as Size)' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'ks.add(K { })' writes 'ks' while one operand of 'plus' is evaluated, and 'ks.get(0 as Size)' is the receiver") }, messages.joinToString("\n"))
    }

    @Test
    fun aFieldReadThroughAMagicCallsReferenceLivesAsLongAsThatReferences() {
        // Convergence round 1, issue 1 (still open after round 5): lifetimeOf.placesOf followed a receiver only
        // when the receiver ITSELF was a magic call, so a field one step further out (`ks.get(0).child`, the
        // emitter's `kira::at(ks,0)->child`) got no lifetime touch at all: g++ prints the child's value after
        // rebindList frees it. A field access has no place of its own here, so the walk must keep going into its
        // origin exactly as it does for a magic call's own receiver: the field lives exactly as long as the
        // reference it was read through.
        val p = snippet(
            """
            pub class K {
                pub mut n: Int32 = 0
                pub fx plus: (v: Int32) Int32 {
                    return n + v
                }
            }
            pub class Node {
                pub mut child: K = K {}
            }
            pub struct Holder {
                pub mut k: K = K {}
            }
            pub fx rebindList: (mut ks: List<Node>) Int32 {
                ks = List<Node> {}
                return 1
            }
            pub fx rebindM: (mut m: Maybe<Node>) Int32 {
                m = Node {}
                return 1
            }
            pub fx rebindHs: (mut hs: List<Holder>) Int32 {
                hs = List<Holder> {}
                return 1
            }
            pub fx g1: () Int32 {
                mut ks: List<Node> = List<Node> {}
                ks.add(Node {})
                return ks.get(0 as Size).child.plus(rebindList(mut ks))
            }
            pub fx g2: () Int32 {
                mut m: Maybe<Node> = Node {}
                return m.unwrap().child.plus(rebindM(mut m))
            }
            pub fx g3: () Int32 {
                mut hs: List<Holder> = List<Holder> {}
                hs.add(Holder {})
                return hs.get(0 as Size).k.plus(rebindHs(mut hs))
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'mut ks' writes 'ks' while one operand of 'plus' is evaluated, and 'ks.get(0 as Size).child' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'mut m' writes 'm' while one operand of 'plus' is evaluated, and 'm.unwrap().child' is the receiver") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'mut hs' writes 'hs' while one operand of 'plus' is evaluated, and 'hs.get(0 as Size).k' is the receiver") }, messages.joinToString("\n"))
    }

    @Test
    fun aHiddenWriteIsThisRulesAgainstAnOperandTheEmitterCannotCopyAndTheEmittersAgainstAnArgument() {
        // Round 5, issue 7: a write hidden in a callee (bumpG writes G) beside an argument or an operator's
        // operand that reads G is READS beside IMPURE, which the emitter copies into temporaries in source order
        // (TypedModel.Effect; W2.3's CppHoister at 1ff53c5), and an assignment's target is a place the emitter
        // binds by reference in order (`xs[G] = bumpG()` locates xs[G] first). A receiver a free function takes
        // by const& (S.startsWith) and the target of a compound assignment (whose old value Kira reads first, and
        // C++ evaluates the right side first) can be given no copy, and the order is refused here.
        val p = snippet(
            """
            pub mut G: Int32 = 0
            pub mut S: Str = "ab"
            pub fx bumpG: () Int32 {
                G += 1
                return 0
            }
            pub fx growS: () Str {
                S = S + "c"
                return "a"
            }
            pub fx add2: (x: Int32, y: Int32) Int32 {
                return x + y
            }
            pub fx f: () Int32 {
                mut xs: Arr<Int32> = [1, 2, 3]
                a: Int32 = add2(G, bumpG())
                b: Int32 = G + bumpG()
                G += bumpG()
                xs[G as Size] = bumpG()
                c: Bool = S.startsWith(growS())
                return a + b
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.order", "rules.exclusivity.order")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'bumpG()' writes 'G' inside its callee while one operand of the assignment is evaluated, and 'G' is read by the target, which C++ evaluates last") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'growS()' writes 'S' inside its callee while one operand of 'startsWith' is evaluated, and 'S' is read by the receiver, which C++ passes by reference") }, messages.joinToString("\n"))
        assertEquals(listOf(Effect.READS, Effect.IMPURE), listOf(BodyTestSupport.all<Expr>(p, "G")[1], BodyTestSupport.all<Expr>(p, "bumpG()")[0]).map { p.model.effects[it] })
    }
}
