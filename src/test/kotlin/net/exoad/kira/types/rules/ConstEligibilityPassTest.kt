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
    fun viewsMaybeAndConstexprBindings() {
        // unilidar's shape: View/MutView parameters, constexpr magic bindings, a Maybe result, an Fx parameter called.
        expectClean(
            snippet(
                """
                pub MAGIC: Arr<UInt8, 4> = @arrOf(0x55, 0xAA, 0x05, 0x0A)
                pub @_const readU32: (p: View<UInt8>) UInt32 {
                    (p[0] as UInt32) | ((p[1] as UInt32) << 8)
                }
                pub @_const writeU32: (p: MutView<UInt8>, v: UInt32) Void {
                    p[0] = (v & 0xFF) as UInt8
                }
                pub @_const frame: (kind: UInt32) Maybe<Arr<UInt8, 8>> {
                    p: Mutable<Arr<UInt8, 8>> = @arrOf(0, 0, 0, 0, 0, 0, 0, 0)
                    for i: Size in 0..MAGIC.size() {
                        p[i] = MAGIC[i]
                    }
                    writeU32(p.from(4), kind)
                    readU32(p.slice(4, 4)) != kind ? null : p
                }
                pub @_const each: (buf: View<UInt8>, f: Fx<Tuple1<UInt8>, Void>) Size {
                    for b: UInt8 in buf {
                        f(b)
                    }
                    buf.size()
                }
                pub @_const biggest: (a: Int32, b: Int32) Int32 {
                    a.abs()
                }
                """,
            ),
        )
    }

    @Test
    fun constOperatorsLiteralGlobalsAndConstDefaults() {
        // The three hidden calls, each constant: a @_const @op_add, a global of a literal type, a field default that is @_const.
        expectClean(
            snippet(
                """
                pub V2: Struct {
                    pub x: Int32 = 0
                }
                pub @_const @op_add: (a: V2, b: V2) V2 {
                    V2 { a.x + b.x }
                }
                pub @_const sum: (a: V2, b: V2) V2 {
                    a + b
                }
                pub LIMITS: Arr<Int32, 2> = @arrOf(1, 2)
                pub @_const lim: (i: Size) Int32 {
                    LIMITS[i]
                }
                pub @_const seed: () Int32 {
                    4
                }
                pub W: Struct {
                    pub n: Int32 = seed()
                }
                pub @_const mk: () Int32 {
                    w: W = W { }
                    w.n
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
                pub @_const id<T>: (v: T) T {
                    v
                }
                pub @_const pair: (a: Int32) Tuple2<Int32, Bool> {
                    Tuple2<Int32, Bool> { a, a > 0 }
                }
                pub @_const pick: (b: Bool) Int32 {
                    b ? 1 : 2
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
            pub Box: Any {
                pub n: Int32 = 0
            }
            pub @_const a: (s: Str) Int32 {
                1
            }
            pub @_const b: () Int32 {
                xs: List<Int32> = List<Int32> {}
                1
            }
            pub @_const c: () Box {
                Box {}
            }
            pub @_const d: (v: Int32) Str {
                "n=${'$'}{v}"
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
            kira.math as math
            pub @_extern(cpp = "ext::read", header = "ext.hxx") read: () Int32;
            pub slow: (v: Int32) Int32 {
                v
            }
            pub @_const a: (v: Int32) Int32 {
                slow(v)
            }
            pub @_const b: (v: Float64) Float64 {
                sqrt(v)
            }
            pub @_const c: () Int32 {
                read()
            }
            pub @_const d: (v: Int32) Int32 {
                trace(v)
                v
            }
            pub Shape: Any {
                pub area: () Int32;
            }
            pub @_const e<T: Shape>: (s: T) Int32 {
                s.area()
            }
            """,
        )
        expectExactly(p, "rules.const.call", "rules.const.call", "rules.const.extern", "rules.const.trace", "rules.const.call")
        assertTrue(message(p, "rules.const.call").contains("'slow', which is not @_const"))
    }

    @Test
    fun operatorOverloadsRunTimeGlobalsAndDefaultsAreCalls() {
        // p02, p03 and p09 of the fix round: each of these typed clean in Kira and failed in C++.
        val p = snippet(
            """
            pub V2: Struct {
                pub x: Int32 = 0
            }
            pub @op_add: (a: V2, b: V2) V2 {
                V2 { a.x + b.x }
            }
            pub @op_eq: (a: V2, b: V2) Bool {
                a.x == b.x
            }
            pub @_const sum: (a: V2, b: V2) V2 {
                a + b
            }
            pub @_const same: (a: V2, b: V2) Bool {
                a == b
            }
            pub DYN: Arr<Int32> = @arrOf(1, 2, 3)
            pub @_const firstOf: () Int32 {
                DYN.get(0)
            }
            pub seed: () Int32 {
                4
            }
            pub W: Struct {
                pub n: Int32 = seed()
            }
            pub @_const mk: () Int32 {
                w: W = W { }
                w.n
            }
            """,
        )
        // A parameter default is a literal or a constant (D48, phase C), so a call there never reaches this pass.
        expectExactly(p, "rules.const.call", "rules.const.call", "rules.const.global", "rules.const.call")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("a + b calls '@op_add', which is not @_const") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("reads the global 'DYN', which is Arr<Int32>") && it.contains("inline const") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("constructs W leaving field 'n' to its default seed(), which calls 'seed', which is not @_const") }, messages.joinToString("\n"))
    }

    @Test
    fun throwsGlobalsAndLambdas() {
        val p = snippet(
            """
            pub SEEN: Mutable<Int32> = 0
            pub @_const a: (v: Int32) Int32 {
                if v < 0 {
                    throw "negative"
                }
                v
            }
            pub @_const b: (v: Int32) Int32 {
                SEEN = v
                SEEN
            }
            pub @_const c: (v: Int32) Int32 {
                try {
                    v
                } on e: Str {
                    0
                }
            }
            pub @_const d: (v: Int32) Int32 {
                f: Fx<Tuple1<Int32>, Int32> = (x: Int32) Int32 {
                    x
                }
                f(v)
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
