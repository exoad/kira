package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class MutabilityPassTest {
    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun mutPlacesMayBeWritten() {
        expectClean(
            snippet(
                """
                pub struct P {
                    pub x: Int32 = 0
                    pub mut fx bump: () Void {
                        x += 1
                    }
                }
                pub mut COUNT: Int32 = 0
                pub fx f: (mut out: Int32, v: MutView<UInt8>) Void {
                    mut a: Int32 = 1
                    a = 2
                    out = a
                    v[0] = 1
                    mut p: P = P {}
                    p.x = 3
                    p.bump()
                    mut xs: Arr<Int32> = [1, 2]
                    xs[0] = 5
                    COUNT = 1
                }
                """,
            ),
        )
    }

    @Test
    fun aClassMutMethodIsCallableThroughAnyReference() {
        // D29: a class is a reference; so is a stdlib handle (a Suite), so neither needs a mut binding.
        expectClean(
            snippet(
                """
                use "kira:test"
                pub class C {
                    pub mut n: Int32 = 0
                    pub mut fx bump: () Void {
                        n += 1
                    }
                }
                pub fx f: (c: C) Void {
                    c.bump()
                    c.n = 4
                    d: C = C {}
                    d.bump()
                    t: Suite = Suite { title = "x" }
                    t.check(true, "y")
                }
                """,
            ),
        )
    }

    @Test
    fun containerMutatorsOnMutBindings() {
        expectClean(
            snippet(
                """
                pub fx f: () Void {
                    mut xs: List<Int32> = List<Int32> {}
                    xs.add(1)
                    mut m: Map<Str, Int32> = Map<Str, Int32> {}
                    m.put("a", 1)
                    mut buf: StrBuf<16> = StrBuf<16> {}
                    buf.add("hi")
                }
                pub fx g: (mut xs: List<Int32>) Void {
                    xs.add(2)
                }
                """,
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aNonMutLocalParamOrConstantCannotBeAssigned() {
        val p = snippet(
            """
            pub LIMIT: Int32 = 3
            pub fx f: (a: Int32) Void {
                b: Int32 = 1
                b = 2
                a = 3
                LIMIT = 4
                b += 1
            }
            """,
        )
        expectExactly(p, "rules.mutability.local", "rules.mutability.param", "rules.mutability.global", "rules.mutability.local")
        assertTrue(message(p, "rules.mutability.local").contains("declare it `mut b: Int32`"))
    }

    @Test
    fun aStructPlainMethodCannotWriteItsReceiver() {
        val p = snippet(
            """
            pub struct P {
                pub x: Int32 = 0
                pub fx set: () Void {
                    x = 1
                }
                pub mut fx bump: () Void {
                    x += 1
                }
                pub fx twice: () Void {
                    bump()
                }
            }
            """,
        )
        expectExactly(p, "rules.mutability.this", "rules.mutability.method")
        assertTrue(message(p, "rules.mutability.this").contains("declare it `mut fx`"))
    }

    @Test
    fun aValueReceiverNeedsAMutablePlace() {
        val p = snippet(
            """
            pub struct P {
                pub x: Int32 = 0
                pub mut fx bump: () Void {
                    x += 1
                }
            }
            pub class C {
                pub n: Int32 = 0
            }
            pub fx f: (xs: List<Int32>, c: C) Void {
                ys: List<Int32> = List<Int32> {}
                ys.add(1)
                xs.add(2)
                q: P = P {}
                q.bump()
                q.x = 2
                c.n = 3
                zs: Arr<Int32> = [1]
                zs[0] = 2
            }
            """,
        )
        expectExactly(
            p,
            "rules.mutability.method", "rules.mutability.method", "rules.mutability.method",
            "rules.mutability.field", "rules.mutability.field", "rules.mutability.element",
        )
        assertTrue(message(p, "rules.mutability.method").contains("List.add".substringAfter('.')))
    }

    @Test
    fun aWriteToACaptureIsPhaseCsAlone() {
        // Phase C reports the capture; the pass does not add a second diagnostic for the same write.
        val p = snippet(
            """
            pub fx f: () Void {
                n: Int32 = 0
                g: Fx<Tuple0, Void> = fx() Void {
                    n = 1
                }
            }
            """,
        )
        expectExactly(p, "types.lambda.assign-capture")
    }
}
