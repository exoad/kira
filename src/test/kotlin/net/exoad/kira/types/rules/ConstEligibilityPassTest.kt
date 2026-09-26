package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.errors
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstEligibilityPassTest {
    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun aStructMachineWithLoopsIsConstant() {
        // hall's shape: a struct's @_const mut fx writing its fields, a global Arr<T, N>, loops.
        expectClean(
            snippet(
                """
                pub INDEX_OF: Arr<Int8, 4> = [-1, 0, 2, 1]
                pub struct Decoder {
                    pub ticks: Int32 = 0
                    pub state: UInt8 = 0
                    pub @_const fx errors: () Int32 {
                        return ticks
                    }
                    pub @_const mut fx feed: (next: UInt8) Void {
                        idx: Int8 = INDEX_OF[(next & 3) as Size]
                        if idx < 0 {
                            state = next
                            return
                        }
                        ticks += idx as Int32
                    }
                }
                pub @_const fx settle: (steps: Arr<UInt8, 4>) Decoder {
                    mut d: Decoder = Decoder {}
                    for s: UInt8 in steps {
                        d.feed(s)
                    }
                    return d
                }
                """,
            ),
        )
    }

    @Test
    fun viewsMaybeAndConstexprBindings() {
        // unilidar's shape: View/MutView parameters, constexpr magic bindings, a Maybe result, an Fx parameter called.
        expectClean(
            snippet(
                """
                pub MAGIC: Arr<UInt8, 4> = [0x55, 0xAA, 0x05, 0x0A]
                pub @_const fx readU32: (p: View<UInt8>) UInt32 {
                    return (p[0] as UInt32) | ((p[1] as UInt32) << 8)
                }
                pub @_const fx writeU32: (p: MutView<UInt8>, v: UInt32) Void {
                    p[0] = (v & 0xFF) as UInt8
                }
                pub @_const fx frame: (kind: UInt32) Maybe<Arr<UInt8, 8>> {
                    mut p: Arr<UInt8, 8> = [0, 0, 0, 0, 0, 0, 0, 0]
                    for i: Size in 0..MAGIC.size() {
                        p[i] = MAGIC[i]
                    }
                    writeU32(p.from(4), kind)
                    if readU32(p.slice(4, 4)) != kind {
                        return null
                    }
                    return p
                }
                pub @_const fx each: (buf: View<UInt8>, f: Fx<Tuple1<UInt8>, Void>) Size {
                    for b: UInt8 in buf {
                        f(b)
                    }
                    return buf.size()
                }
                pub @_const fx biggest: (a: Int32, b: Int32) Int32 {
                    return a.abs()
                }
                """,
            ),
        )
    }

    @Test
    fun genericsAndTuples() {
        expectClean(
            snippet(
                """
                pub @_const fx id<T>: (v: T) T {
                    return v
                }
                pub @_const fx pair: (a: Int32) Tuple2<Int32, Bool> {
                    return Tuple2<Int32, Bool> { a, a > 0 }
                }
                pub @_const fx pick: (b: Bool) Int32 {
                    return if b { 1 } else { 2 }
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun nonLiteralTypesAreNamed() {
        val p = snippet(
            """
            pub class Box {
                pub n: Int32 = 0
            }
            pub @_const fx a: (s: Str) Int32 {
                return 1
            }
            pub @_const fx b: () Int32 {
                xs: List<Int32> = List<Int32> {}
                return 1
            }
            pub @_const fx c: () Box {
                return Box {}
            }
            pub @_const fx d: (v: Int32) Str {
                return "n=${'$'}{v}"
            }
            """,
        )
        val codes = errors(p)
        assertTrue(codes.all { it == "rules.const.type" }, codes.toString())
        assertTrue(codes.size >= 5, codes.toString())
        assertTrue(message(p, "rules.const.type").contains("parameter 's' is Str"))
        assertTrue(p.diagnostics.any { it.message.contains("local 'xs' is List<Int32>") && it.message.contains("GCC 12") })
    }

    @Test
    fun callsOutsideTheConstantWorld() {
        val p = snippet(
            """
            use "kira:math"
            @_extern(cpp = "ext::read", header = "ext.hxx")
            pub fx read: () Int32;
            pub fx slow: (v: Int32) Int32 {
                return v
            }
            pub @_const fx a: (v: Int32) Int32 {
                return slow(v)
            }
            pub @_const fx b: (v: Float64) Float64 {
                return sqrt(v)
            }
            pub @_const fx c: () Int32 {
                return read()
            }
            pub @_const fx d: (v: Int32) Int32 {
                trace(v)
                return v
            }
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub @_const fx e<T: Shape>: (s: T) Int32 {
                return s.area()
            }
            """,
        )
        expectExactly(p, "rules.const.call", "rules.const.call", "rules.const.extern", "rules.const.trace", "rules.const.call")
        assertTrue(message(p, "rules.const.call").contains("'slow', which is not @_const"))
    }

    @Test
    fun throwsGlobalsAndLambdas() {
        val p = snippet(
            """
            pub mut SEEN: Int32 = 0
            pub @_const fx a: (v: Int32) Int32 {
                if v < 0 {
                    throw "negative"
                }
                return v
            }
            pub @_const fx b: (v: Int32) Int32 {
                SEEN = v
                return SEEN
            }
            pub @_const fx c: (v: Int32) Int32 {
                try {
                    return v
                } on e: Str {
                    return 0
                }
            }
            pub @_const fx d: (v: Int32) Int32 {
                f: Fx<Tuple1<Int32>, Int32> = fx(x: Int32) Int32 {
                    return x
                }
                return f(v)
            }
            """,
        )
        assertEquals(
            listOf("rules.const.throw", "rules.const.global", "rules.const.global", "rules.const.try", "rules.const.type", "rules.const.lambda", "rules.const.call"),
            errors(p),
            p.diagnostics.joinToString("\n") { it.render() },
        )
    }
}
