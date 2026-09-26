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

    // ---- negative ----------------------------------------------------------------------------

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
            }
            """,
        )
        expectExactly(p, "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop", "rules.exclusivity.loop")
    }
}
