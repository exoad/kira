package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.cls
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.fn
import net.exoad.kira.types.rules.RulesTestSupport.method
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EscapePass after decision 4b: the `Fx` and `this` facts only (`fxEscapes`, `thisEscapes`). Every view
 * rule is ViewPass's (ViewPassTest).
 */
class EscapePassTest {
    // ---- positive: the facts ------------------------------------------------------------------

    @Test
    fun anFxParameterThatIsOnlyCalledDoesNotEscape() {
        val p = snippet(
            """
            pub fx applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return f(x)
            }
            pub fx twice: (g: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return applyTo(g, applyTo(g, x))
            }
            pub fx keep: (h: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                return h
            }
            pub fx store: (k: Fx<Tuple1<Int32>, Int32>) Int32 {
                held: Fx<Tuple1<Int32>, Int32> = k
                return held(1)
            }
            pub fx forward: (m: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                return keep(m)
            }
            pub fx capture: (c: Fx<Tuple1<Int32>, Int32>) Int32 {
                inner: Fx<Tuple0, Int32> = fx() Int32 {
                    return c(1)
                }
                return inner()
            }
            pub fx collect: (q: Fx<Tuple1<Int32>, Int32>) Void {
                mut xs: List<Fx<Tuple1<Int32>, Int32>> = List<Fx<Tuple1<Int32>, Int32>> {}
                xs.add(q)
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertFalse(model.fxEscapes(fn(p, "applyTo").params[0]), "applyTo.f is only called")
        assertFalse(model.fxEscapes(fn(p, "twice").params[0]), "twice.g goes to a non-escaping parameter")
        assertTrue(model.fxEscapes(fn(p, "keep").params[0]), "keep.h is returned")
        assertTrue(model.fxEscapes(fn(p, "store").params[0]), "store.k is stored in a local")
        assertTrue(model.fxEscapes(fn(p, "forward").params[0]), "forward.m goes to an escaping parameter")
        assertTrue(model.fxEscapes(fn(p, "capture").params[0]), "capture.c is captured")
        assertTrue(model.fxEscapes(fn(p, "collect").params[0]), "collect.q is put in a container")
    }

    @Test
    fun thisEscapesOnlyWhenItLeavesTheMethod() {
        val p = snippet(
            """
            pub class Counter {
                pub mut k: Int32 = 5
                pub fx apply: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                    return f(x)
                }
                pub fx offset: (x: Int32) Int32 {
                    return apply(fx(y: Int32) Int32 {
                        return y + k
                    }, x)
                }
            }
            pub class Leaky {
                pub mut k: Int32 = 5
                pub fx multiplier: () Fx<Tuple1<Int32>, Int32> {
                    return fx(x: Int32) Int32 {
                        return x * k
                    }
                }
            }
            pub class Self {
                pub fx me: () Self {
                    return this
                }
            }
            pub trait Cb {
                pub fx run: (f: Fx<Tuple1<Int32>, Int32>) Int32;
            }
            pub class Impl: Cb {
                override pub fx run: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                    return f(1)
                }
            }
            """,
        )
        expectClean(p)
        assertFalse(cls(p, "Counter").thisEscapes, "a lambda passed to a non-escaping parameter keeps this inside")
        assertTrue(cls(p, "Leaky").thisEscapes, "a returned lambda captures this")
        assertTrue(cls(p, "Self").thisEscapes, "this is returned")
        assertTrue(p.model.fxEscapes(method(p, "Impl", "run").params[0]), "a virtual method's Fx parameter always escapes")
    }

    @Test
    fun aFunctionTakenAsAValueHasEscapingFxParameters() {
        val p = snippet(
            """
            pub fx applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return f(x)
            }
            pub fx direct: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return f(x)
            }
            pub fx useIt: () Int32 {
                h: Fx<Tuple2<Fx<Tuple1<Int32>, Int32>, Int32>, Int32> = applyTo
                return h(fx(y: Int32) Int32 {
                    return y
                }, 1) + direct(fx(y: Int32) Int32 {
                    return y
                }, 2)
            }
            """,
        )
        expectClean(p)
        assertTrue(p.model.fxEscapes(fn(p, "applyTo").params[0]), "applyTo is a value, so no template: its Fx is a kira::Fn")
        assertFalse(p.model.fxEscapes(fn(p, "direct").params[0]), "direct is only called")
    }

    @Test
    fun escapingIsAFixpointThroughEveryCallee() {
        val p = snippet(
            """
            mut KEPT: List<Fx<Tuple0, Int32>> = List<Fx<Tuple0, Int32>> {}
            pub fx keep: (k: Fx<Tuple0, Int32>) Void {
                KEPT.add(k)
            }
            pub fx middle: (m: Fx<Tuple0, Int32>) Void {
                keep(m)
            }
            pub fx outer: (o: Fx<Tuple0, Int32>) Void {
                middle(o)
            }
            pub fx call: (c: Fx<Tuple0, Int32>) Int32 {
                return c()
            }
            pub fx relay: (q: Fx<Tuple0, Int32>) Int32 {
                return call(q)
            }
            """,
        )
        // Rule M too (50-round4 2.3 row 1, 2.7): KEPT is a mut global, and add is handed an Fx value, so it is not CONFINED.
        RulesTestSupport.expectExactly(p, "rules.exclusivity.mut")
        assertTrue(p.model.fxEscapes(fn(p, "keep").params[0]), "keep.k goes into a container")
        assertTrue(p.model.fxEscapes(fn(p, "middle").params[0]), "middle.m goes to keep.k")
        assertTrue(p.model.fxEscapes(fn(p, "outer").params[0]), "outer.o goes to middle.m, two calls away")
        assertFalse(p.model.fxEscapes(fn(p, "call").params[0]))
        assertFalse(p.model.fxEscapes(fn(p, "relay").params[0]), "relay.q goes only to a parameter that is only called")
    }

    @Test
    fun anFxHandedToAnExternOrAnFxValueEscapes() {
        val p = snippet(
            """
            @_extern(cpp = "ext::later", header = "ext.hxx")
            pub fx later: (f: Fx<Tuple0, Void>) Void;
            pub fx viaExtern: (e: Fx<Tuple0, Void>) Void {
                later(e)
            }
            pub fx viaValue: (g: Fx<Tuple1<Fx<Tuple0, Void>>, Void>, v: Fx<Tuple0, Void>) Void {
                g(v)
            }
            """,
        )
        expectClean(p)
        assertTrue(p.model.fxEscapes(fn(p, "viaExtern").params[0]), "an extern's Fx parameter escapes (5.4)")
        assertTrue(p.model.fxEscapes(fn(p, "viaValue").params[1]), "an Fx value's parameter has no body to look into")
        assertFalse(p.model.fxEscapes(fn(p, "viaValue").params[0]), "g itself is only called")
    }

    @Test
    fun thisPassedOnEscapesButAStructOrATraitMarksNoClass() {
        val p = snippet(
            """
            pub class Node {
                pub mut n: Int32 = 0
                pub fx register: () Void {
                    remember(this)
                }
                pub fx size: () Int32 {
                    return n
                }
            }
            mut NODES: List<Node> = List<Node> {}
            pub fx remember: (x: Node) Void {
                NODES.add(x)
            }
            pub struct Pt {
                pub x: Int32 = 0
                pub fx same: () Pt {
                    return this
                }
            }
            pub trait Named {
                pub fx name: () Str;
                pub fx me: () Named {
                    return this
                }
            }
            pub class Tag: Named {
                override pub fx name: () Str {
                    return "tag"
                }
            }
            """,
        )
        expectClean(p)
        assertTrue(cls(p, "Node").thisEscapes, "this is passed to a function that keeps it")
        assertFalse(cls(p, "Pt").thisEscapes, "a struct's this is a value")
        assertFalse(cls(p, "Tag").thisEscapes, "a trait's default body marks no implementor: W2.4 lowers the trait, not Tag")
    }

    @Test
    fun anFxHandedOnAsAnOperatorsOperandEscapesWhenTheOverloadKeepsIt() {
        // w2-5 minor #1 (round 3, probe esc2): `Keeper { } + f` is `Keeper { }.@op_add(f)` (DECISIONS 2), and @op_add
        // stores f in a global, so viaOp's f escapes; the lambda grab hands it, which captures a view, is then
        // rules.view.capture. passOn hands its g to an overload that only calls it: no escape.
        val p = snippet(
            """
            mut GF: Maybe<Fx<Tuple0, Int32>> = null
            pub struct Keeper {
                pub k: Int32 = 0
                pub fx @op_add: (f: Fx<Tuple0, Int32>) Int32 {
                    GF = f
                    return 0
                }
            }
            pub struct Caller {
                pub k: Int32 = 0
                pub fx @op_add: (f: Fx<Tuple0, Int32>) Int32 {
                    return f()
                }
            }
            pub fx viaOp: (f: Fx<Tuple0, Int32>) Int32 {
                return Keeper { } + f
            }
            pub fx passOn: (g: Fx<Tuple0, Int32>) Int32 {
                return Caller { } + g
            }
            pub fx grab: (v: View<Int32>) Int32 {
                return viaOp(fx() Int32 { return v[0] })
            }
            """,
        )
        assertTrue(p.model.fxEscapes(fn(p, "viaOp").params[0]), "viaOp.f goes to an overload that stores it")
        assertFalse(p.model.fxEscapes(fn(p, "passOn").params[0]), "passOn.g goes to an overload that only calls it")
        assertTrue(p.diagnostics.map { it.code } == listOf("rules.view.capture"), net.exoad.kira.types.TyperTestSupport.render(p))
    }
}
