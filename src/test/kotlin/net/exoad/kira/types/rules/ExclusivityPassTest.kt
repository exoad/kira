package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
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
        expectExactly(p, "rules.exclusivity.argument", "rules.exclusivity.argument", "rules.exclusivity.argument", "rules.exclusivity.argument")
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
        // is PURE (a local), so R19 never spills it, and only this rule keeps the order. The view may be lent at
        // the call (arr.view(), arr.from(0)), held in a MutView local (mv), or held in a struct local (w).
        val p = snippet(
            """
            pub fx fill: (v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return 1
            }
            pub fx pair8: (a: UInt8, b: UInt8) UInt8 {
                return a
            }
            pub struct W {
                pub mv: MutView<UInt8>
            }
            pub fx fillW: (w: W) UInt8 {
                w.mv.set(0, 9)
                return 1
            }
            pub fx both: (xs: Arr<UInt8, 4>, v: MutView<UInt8>) UInt8 {
                v.set(0, 9)
                return xs[0]
            }
            pub fx f: () UInt8 {
                mut arr: Arr<UInt8, 4> = [1, 2, 3, 4]
                a: UInt8 = pair8(arr[0], fill(arr.view()))
                mv: MutView<UInt8> = arr.view()
                b: UInt8 = pair8(arr[0], fill(mv))
                c: UInt8 = arr[0] + fill(arr.view())
                d: UInt8 = pair8(arr.get(0), fill(arr.from(0)))
                w: W = W { mv = arr.view() }
                e: UInt8 = pair8(arr[0], fillW(w))
                g: UInt8 = both(arr, arr.view())
                h: UInt8 = pair8(mv[0], fill(mv))
                return a + b + c + d + e + g + h
            }
            """,
        )
        expectExactly(
            p,
            "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order", "rules.exclusivity.order",
            "rules.exclusivity.order", "rules.exclusivity.argument", "rules.exclusivity.order",
        )
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("'the MutView 'arr.view()'' writes 'arr' while one operand of 'pair8' is evaluated, and 'arr[0]' is read") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'the MutView 'mv'' writes 'arr' while one operand of 'pair8'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'the MutView 'w'' writes 'arr' while one operand of 'pair8'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("the MutView 'arr.view()' overlaps the argument 'arr' of 'both'") }, messages.joinToString("\n"))
    }

    @Test
    fun aMutViewReadOnlyBesideAWriteOfItsStorageIsCaughtToo() {
        // The other way round: the sibling writes the storage a view local reads.
        val p = snippet(
            bag + """
            pub fx f: () Int32 {
                mut xs: Arr<Int32> = [1, 2]
                v: View<Int32> = xs.view()
                return pair(v[0], bump(mut xs[1]))
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
                return n + bx.a + pt.a + arr[0] + xs[0]
            }
            """,
        )
        // A struct construction and an array literal are sequenced; `n = bump(mut n)` writes n after its value.
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
        // A `mut fx` on the holder of the iterated collection is reported as such, whichever field it writes
        // (the receiver rule, conservative); the hidden-write rule is the fallback for a callee the call site
        // does not show writing (note on LOG).
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("iterates 'LOG', and its body calls 'note', which writes 'LOG'") }, messages.joinToString("\n"))
        assertTrue(messages.none { it.contains("quiet") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'xs', and its body passes a MutView of 'xs.view()'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("iterates 'b.other', and its body calls the `mut fx` 'pushOther' on 'b', which holds it") }, messages.joinToString("\n"))
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
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
        assertTrue(p.diagnostics.any { it.message.contains("calls the `mut fx` 'reset' on 'k', which holds it") }, p.diagnostics.joinToString("\n") { it.message })
    }
}
