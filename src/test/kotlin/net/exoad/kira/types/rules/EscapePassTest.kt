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
            pub applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                f(x)
            }
            pub twice: (g: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                applyTo(g, applyTo(g, x))
            }
            pub keep: (h: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                h
            }
            pub store: (k: Fx<Tuple1<Int32>, Int32>) Int32 {
                held: Fx<Tuple1<Int32>, Int32> = k
                held(1)
            }
            pub forward: (m: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                keep(m)
            }
            pub capture: (c: Fx<Tuple1<Int32>, Int32>) Int32 {
                inner: Fx<Tuple0, Int32> = () Int32 {
                    c(1)
                }
                inner()
            }
            pub collect: (q: Fx<Tuple1<Int32>, Int32>) Void {
                xs: Mutable<List<Fx<Tuple1<Int32>, Int32>>> = List<Fx<Tuple1<Int32>, Int32>> {}
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
            pub Counter: Mutable<Any> {
                pub k: Mutable<Int32> = 5
                pub apply: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                    f(x)
                }
                pub offset: (x: Int32) Int32 {
                    apply((y: Int32) Int32 {
                        y + k
                    }, x)
                }
            }
            pub Leaky: Mutable<Any> {
                pub k: Mutable<Int32> = 5
                pub multiplier: () Fx<Tuple1<Int32>, Int32> {
                    (x: Int32) Int32 {
                        x * k
                    }
                }
            }
            pub Self: Any {
                pub me: () Self {
                    this
                }
            }
            pub Cb: Any {
                pub run: (f: Fx<Tuple1<Int32>, Int32>) Int32;
            }
            pub Impl: Cb {
                override pub run: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                    f(1)
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
            pub applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                f(x)
            }
            pub direct: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                f(x)
            }
            pub useIt: () Int32 {
                h: Fx<Tuple2<Fx<Tuple1<Int32>, Int32>, Int32>, Int32> = applyTo
                h((y: Int32) Int32 {
                    y
                }, 1) + direct((y: Int32) Int32 {
                    y
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
            KEPT: Mutable<List<Fx<Tuple0, Int32>>> = List<Fx<Tuple0, Int32>> {}
            pub keep: (k: Fx<Tuple0, Int32>) Void {
                KEPT.add(k)
            }
            pub middle: (m: Fx<Tuple0, Int32>) Void {
                keep(m)
            }
            pub outer: (o: Fx<Tuple0, Int32>) Void {
                middle(o)
            }
            pub call: (c: Fx<Tuple0, Int32>) Int32 {
                c()
            }
            pub relay: (q: Fx<Tuple0, Int32>) Int32 {
                call(q)
            }
            """,
        )
        // No rule M (50-round4 2.3 row 1, round 5b): KEPT is a mut global, but add takes its Fx as a T and only keeps it,
        // so the Fx is not charged and add is CONFINED.
        expectClean(p)
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
            pub @_extern(cpp = "ext::later", header = "ext.hxx") later: (f: Fx<Tuple0, Void>) Void;
            pub viaExtern: (e: Fx<Tuple0, Void>) Void {
                later(e)
            }
            pub viaValue: (g: Fx<Tuple1<Fx<Tuple0, Void>>, Void>, v: Fx<Tuple0, Void>) Void {
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
            pub Node: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub register: () Void {
                    remember(this)
                }
                pub size: () Int32 {
                    n
                }
            }
            NODES: Mutable<List<Node>> = List<Node> {}
            pub remember: (x: Node) Void {
                NODES.add(x)
            }
            pub Pt: Struct {
                pub x: Int32 = 0
                pub same: () Pt {
                    this
                }
            }
            pub Named: Any {
                pub name: () Str;
                pub me: () Named {
                    this
                }
            }
            pub Tag: Named {
                override pub name: () Str {
                    "tag"
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
            GF: Mutable<Maybe<Fx<Tuple0, Int32>>> = null
            pub Keeper: Struct {
                pub k: Int32 = 0
                pub @op_add: (f: Fx<Tuple0, Int32>) Int32 {
                    GF = f
                    0
                }
            }
            pub Caller: Struct {
                pub k: Int32 = 0
                pub @op_add: (f: Fx<Tuple0, Int32>) Int32 {
                    f()
                }
            }
            pub viaOp: (f: Fx<Tuple0, Int32>) Int32 {
                Keeper { } + f
            }
            pub passOn: (g: Fx<Tuple0, Int32>) Int32 {
                Caller { } + g
            }
            pub grab: (v: View<Int32>) Int32 {
                viaOp(() Int32 { v[0] })
            }
            """,
        )
        assertTrue(p.model.fxEscapes(fn(p, "viaOp").params[0]), "viaOp.f goes to an overload that stores it")
        assertFalse(p.model.fxEscapes(fn(p, "passOn").params[0]), "passOn.g goes to an overload that only calls it")
        assertTrue(p.diagnostics.map { it.code } == listOf("rules.view.capture"), net.exoad.kira.types.TyperTestSupport.render(p))
    }
}
