package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.fn
import net.exoad.kira.types.rules.RulesTestSupport.method
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EffectsPassTest {
    // ---- positive: pure ------------------------------------------------------------------------

    @Test
    fun arithmeticLocalsAndPureCalleesArePure() {
        val p = snippet(
            """
            pub LIMIT: Int32 = 3
            pub struct P {
                pub x: Int32 = 0
                pub fx doubled: () Int32 {
                    return x * 2
                }
            }
            pub fx twice: (v: Int32) Int32 {
                return v * 2
            }
            pub fx quad: (v: Int32) Int32 {
                mut acc: Int32 = twice(v)
                acc += twice(v)
                mut p: P = P {}
                p.x = acc
                mut xs: Arr<Int32, 2> = [1, 2]
                xs[0] = p.x
                return xs[0] + LIMIT + p.doubled()
            }
            pub fx len: (s: Str, xs: List<Int32>) Size {
                mut ys: List<Int32> = List<Int32> {}
                return s.length() + xs.size() + ys.size()
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.PURE, p.model.effect(fn(p, "twice")))
        assertEquals(Effect.PURE, p.model.effect(fn(p, "quad")), "reads of a constant, writes to locals and a struct method on a local are pure")
        assertEquals(Effect.PURE, p.model.effect(fn(p, "len")), "Str.length and List.size are marked pure, and their receivers are the caller's own values")
        assertEquals(Effect.PURE, p.model.effect(method(p, "P", "doubled")), "a struct reads its own fields by value")
    }

    @Test
    fun mutualRecursionStaysPure() {
        val p = snippet(
            """
            pub fx even: (n: Int32) Bool {
                if n == 0 {
                    return true
                }
                return odd(n - 1)
            }
            pub fx odd: (n: Int32) Bool {
                if n == 0 {
                    return false
                }
                return even(n - 1)
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.PURE, p.model.effect(fn(p, "even")))
        assertEquals(Effect.PURE, p.model.effect(fn(p, "odd")))
    }

    @Test
    fun expressionsCarryTheirOwnEffect() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub fx bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub fx twice: (v: Int32) Int32 {
                return v * 2
            }
            pub fx f: (a: Int32) Int32 {
                g: Fx<Tuple0, Int32> = fx() Int32 {
                    return bump()
                }
                return twice(a) + bump() + a * 2
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "twice(a)")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "bump()")))
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "a * 2")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "twice(a) + bump() + a * 2")))
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.every<LambdaExpr>(p).single()), "creating a closure is pure")
    }

    // ---- negative: impure ----------------------------------------------------------------------

    @Test
    fun writesOutsideTheLocalsAreImpure() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub class C {
                pub mut n: Int32 = 0
                pub mut fx bump: () Void {
                    n += 1
                }
                pub fx peek: () Int32 {
                    return n
                }
            }
            pub fx global: () Void {
                STATE = 1
            }
            pub fx param: (mut out: Int32) Void {
                out = 1
            }
            pub fx through: (c: C) Void {
                c.n = 2
            }
            pub fx viewed: (v: MutView<UInt8>) Void {
                v[0] = 1
            }
            pub fx ref: () Int32 {
                r: Ref<Int32> = Ref<Int32> { value = 0 }
                r.value = 1
                return r.value
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.IMPURE, model.effect(fn(p, "global")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "param")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "through")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "viewed")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "ref")))
        assertEquals(Effect.IMPURE, model.effect(method(p, "C", "bump")), "a mut fx writing its receiver")
        assertEquals(Effect.IMPURE, model.effect(method(p, "C", "peek")), "a class reads its field through a reference a sibling could write")
    }

    @Test
    fun readsOfSharedStateAndThrowsAreImpure() {
        // D33: an operand that observes what a sibling could change, or that a try could catch, needs its order kept.
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub class C {
                pub mut n: Int32 = 0
            }
            pub fx readG: () Int32 {
                return STATE
            }
            pub fx byRef: (mut x: Int32) Int32 {
                return x
            }
            pub fx element: (v: View<UInt8>) Int32 {
                return v[0] as Int32
            }
            pub fx through: (v: View<UInt8>) Size {
                return v.size()
            }
            pub fx field: (c: C) Int32 {
                return c.n
            }
            pub fx parse: (s: Str) Int32 {
                if s.isEmpty() {
                    throw "empty"
                }
                return 1
            }
            """,
        )
        expectClean(p)
        for (name in listOf("readG", "byRef", "element", "through", "field", "parse")) {
            assertEquals(Effect.IMPURE, p.model.effect(fn(p, name)), name)
        }
    }

    @Test
    fun impurityFlowsUpTheCallGraph() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub fx bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub fx uses: (v: Int32) Int32 {
                return v + bump()
            }
            pub fx usesUses: (v: Int32) Int32 {
                return uses(v)
            }
            pub fx builds: () Size {
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                return xs.size()
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "bump")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "uses")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "usesUses")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "builds")), "List.add is a mutator without a pure flag")
    }

    @Test
    fun printsExternsVirtualsAndFxValuesAreImpure() {
        val p = snippet(
            """
            @_extern(cpp = "ext::read", header = "ext.hxx")
            pub fx read: () Int32;
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub fx prints: (v: Int32) Void {
                trace(v)
            }
            pub fx externs: () Int32 {
                return read()
            }
            pub fx virtuals: (s: Shape) Int32 {
                return s.area()
            }
            pub fx values: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            """,
        )
        expectClean(p)
        for (name in listOf("prints", "externs", "virtuals", "values")) {
            assertEquals(Effect.IMPURE, p.model.effect(fn(p, name)), name)
        }
    }
}
