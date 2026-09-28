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
    fun aClassPlainMethodCannotWriteItsOwnState() {
        // Round 5, issue 8: the spec's "methods that modify instance state must be marked mut" (design 5.5 makes
        // a non-mut class method `const`; W2.4 drops the const where a write got through, so this is the spec's
        // rule, not C++'s). A field of another object reached through a class-typed field (`child.n`,
        // `child.bump()`) is that object's, which any reference may write (D29), `initially` may write
        // everything, and a lambda captures `this` as a reference (self = shared_from_this(), design 5.6), so
        // the method that returns it modifies nothing itself.
        val p = snippet(
            """
            pub class Node {
                pub mut n: Int32 = 0
                pub mut fx bump: () Void {
                    n += 1
                }
            }
            pub struct Pt {
                pub x: Int32 = 0
                pub mut fx move: () Void {
                    x += 1
                }
            }
            pub class Counter {
                pub mut count: Int32 = 0
                pub mut items: List<Int32> = List<Int32> {}
                pub mut child: Node = Node {}
                pub mut at: Pt = Pt {}
                initially {
                    count = 1
                }
                pub fx increment: () Void {
                    count = count + 1
                }
                pub fx push: () Void {
                    items.add(1)
                }
                pub fx movePt: () Void {
                    at.move()
                }
                pub fx setX: () Void {
                    at.x = 2
                }
                pub fx twice: () Void {
                    bumpMut()
                }
                pub mut fx bumpMut: () Void {
                    count += 1
                }
                pub fx ok: (other: Counter) Fx<Tuple0, Void> {
                    child.n = 3
                    child.bump()
                    other.count = 4
                    other.bumpMut()
                    n: Int32 = count + (items.size() as Int32)
                    return fx() Void {
                        count += 1
                        bumpMut()
                    }
                }
            }
            """,
        )
        expectExactly(p, "rules.mutability.this", "rules.mutability.method", "rules.mutability.method", "rules.mutability.this", "rules.mutability.method")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("Counter.increment is a plain `fx` of the class Counter, so its own state is read-only (a `const` method in C++); declare it `mut fx` to write count.") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("'bumpMut' is a `mut fx` and writes its receiver, but Counter.twice is a plain `fx` of the class Counter; declare it `mut fx`.") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("'add' is a `mut fx`") && it.contains("Counter.push is a plain `fx` of the class Counter") }, messages.joinToString("\n"))
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
