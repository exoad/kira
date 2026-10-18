package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class GenericBoundsPassTest {
    private val decls = """
        pub Shape: Any {
            pub area: () Int32;
        }
        pub Sq: Struct, Shape {
            pub s: Int32 = 1
            override pub area: () Int32 {
                return s * s
            }
        }
        pub Pt: Struct {
            pub x: Int32 = 0
        }
        pub Box<T: Shape>: Any {
            pub v: T
        }
        pub bigger<T: Shape>: (a: T, b: T) Int32 {
            return a.area() + b.area()
        }
        pub half<T: FloatNum>: (v: T) T {
            return v
        }
    """

    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun argumentsThatSatisfyTheirBounds() {
        expectClean(
            snippet(
                decls + """
                pub f: () Int32 {
                    b: Box<Sq> = Box<Sq> { v = Sq {} }
                    return bigger<Sq>(b.v, Sq {})
                }
                pub g: (v: Float32) Float32 {
                    return half<Float32>(v) + half<Float32>(v)
                }
                """,
            ),
        )
    }

    @Test
    fun aBoundedParameterPassesThrough() {
        expectClean(
            snippet(
                decls + """
                pub wrap<U: Shape>: (u: U) Box<U> {
                    return Box<U> { v = u }
                }
                pub Pair<A: Shape, B: Shape>: Any {
                    pub a: Box<A>
                    pub b: Box<B>
                }
                """,
            ),
        )
    }

    @Test
    fun unboundedParametersTakeAnything() {
        expectClean(
            snippet(
                """
                pub Any<T>: Any {
                    pub v: T
                }
                pub f: () Int32 {
                    a: Any<Str> = Any<Str> { v = "x" }
                    b: Any<List<Int32>> = Any<List<Int32>> { v = List<Int32> {} }
                    return 1
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aSpelledTypeArgumentOutsideItsBound() {
        val p = snippet(
            decls + """
            pub f: (b: Box<Str>) Box<Pt> {
                return Box<Pt> { v = Pt {} }
            }
            """,
        )
        expectExactly(p, "rules.generics.bound", "rules.generics.bound", "rules.generics.bound")
        assertTrue(p.diagnostics.any { it.message.contains("Str does not satisfy the bound of Box's type parameter T: T must be a Shape") }, p.diagnostics.joinToString("\n") { it.render() })
    }

    @Test
    fun anExplicitCallTypeArgumentOutsideItsBound() {
        val p = snippet(
            decls + """
            pub f: () Int32 {
                return bigger<Pt>(Pt {}, Pt {})
            }
            pub g: (v: Int32) Int32 {
                return half<Int32>(v)
            }
            """,
        )
        expectExactly(p, "rules.generics.bound", "rules.generics.bound")
    }

    @Test
    fun aNestedArgumentIsCheckedWhereItIsSpelled() {
        val p = snippet(
            decls + """
            pub f: (xs: List<Box<Pt>>) Int32 {
                return 1
            }
            """,
        )
        expectExactly(p, "rules.generics.bound")
    }
}
