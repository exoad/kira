package net.exoad.kira.types.rules

import net.exoad.kira.types.rules.RulesTestSupport.cls
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.fn
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.method
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun viewsOfWhatTheCallerOwnsMayBeReturned() {
        val p = snippet(
            """
            pub fx tail: (s: View<Char>, n: Size) View<Char> {
                return s.from(n)
            }
            pub fx tail2: (s: View<Char>, n: Size) Maybe<View<Char>> {
                v: View<Char> = s.from(n)
                if v.isEmpty() {
                    return null
                }
                return v
            }
            pub fx whole: (xs: Arr<Int32>) View<Int32> {
                return xs.view()
            }
            pub struct Cursor {
                pub text: View<Char> = ""
            }
            pub fx read: (v: View<UInt8>) Int32 {
                n: Size = v.size()
                return n as Int32
            }
            """,
        )
        expectClean(p)
        assertTrue(p.model.viewEscapes(fn(p, "tail").params[0]), "tail.s is returned (through a lent view)")
        assertFalse(p.model.viewEscapes(fn(p, "read").params[0]), "read.v is only read")
    }

    @Test
    fun aViewOfTheCallersStorageMayTravelThroughStructsCallsAndCoercions() {
        val p = snippet(
            """
            pub struct Cursor {
                pub text: View<Char> = ""
            }
            pub fx keep: (v: View<Int32>) View<Int32> {
                return v
            }
            pub fx viaStruct: (s: View<Char>) Cursor {
                return Cursor { text = s }
            }
            pub fx viaLocal: (s: View<Char>) View<Char> {
                c: Cursor = Cursor { text = s }
                return c.text
            }
            pub fx viaCall: (xs: Arr<Int32, 4>) View<Int32> {
                return keep(xs.view())
            }
            pub fx coerced: (xs: Arr<Int32>) View<Int32> {
                return xs
            }
            pub fx applyTo: (f: Fx<Tuple1<Int32>, Int32>, x: Int32) Int32 {
                return f(x)
            }
            pub fx taken: () Int32 {
                h: Fx<Tuple2<Fx<Tuple1<Int32>, Int32>, Int32>, Int32> = applyTo
                return 1
            }
            """,
        )
        expectClean(p)
        assertTrue(p.model.fxEscapes(fn(p, "applyTo").params[0]), "applyTo is taken as a value, so it is no template: its Fx parameter is a std::function")
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun aViewOfALocalCannotBeReturned() {
        val p = snippet(
            """
            pub fx bad: () View<Int32> {
                xs: Arr<Int32, 3> = [1, 2, 3]
                return xs.view()
            }
            pub fx bad2: () View<Int32> {
                xs: Arr<Int32, 3> = [1, 2, 3]
                v: View<Int32> = xs.slice(0, 2)
                w: View<Int32> = v.from(1)
                return w
            }
            pub fx bad3: (b: Bool) Maybe<View<Char>> {
                s: Str = "abc"
                if b {
                    return null
                }
                return s.view()
            }
            """,
        )
        expectExactly(p, "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return")
        assertTrue(message(p, "rules.escape.view-return").contains("view of the local 'xs'"))
    }

    @Test
    fun aViewOfALocalCannotLeaveByCoercionCallOrStruct() {
        val p = snippet(
            """
            pub struct Cursor {
                pub text: View<Char> = ""
            }
            pub fx keep: (v: View<Int32>) View<Int32> {
                return v
            }
            pub fx coercedArr: () View<Int32> {
                a: Arr<Int32, 4> = [1, 2, 3, 4]
                return a
            }
            pub fx coercedList: () View<Int32> {
                mut xs: List<Int32> = List<Int32> {}
                return xs
            }
            pub fx throughCall: () View<Int32> {
                a: Arr<Int32, 4> = [1, 2, 3, 4]
                return keep(a.view())
            }
            pub fx inStruct: () Cursor {
                s: Str = "abc"
                return Cursor { text = s.view() }
            }
            pub fx inLocalStruct: () Cursor {
                s: Str = "abc"
                mut c: Cursor = Cursor {}
                c.text = s.view()
                return c
            }
            pub fx outOfLocalStruct: () View<Char> {
                s: Str = "abc"
                c: Cursor = Cursor { text = s.view() }
                return c.text
            }
            """,
        )
        expectExactly(p, "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("a is returned as a view of itself, a local") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("keep(a.view()) is a view of the local 'a'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("c is a view of the local 's'") }, messages.joinToString("\n"))
    }

    @Test
    fun aViewOfALocalCannotLeaveThroughAMethodReceiverOrAReturnedParameter() {
        // Round 2, issue 4a: a method that returns a view of its receiver (all: data.view(); get: a view field)
        // lends the receiver, and keep2 returns a view of its Arr parameter. Each is a fixpoint over the call
        // graph, so a method calling such a method on this lends this too.
        val p = snippet(
            """
            pub struct Buf {
                pub data: Arr<Int32, 4> = [1, 2, 3, 4]
                pub fx all: () View<Int32> {
                    return data.view()
                }
                pub fx tail: () View<Int32> {
                    return all().from(1)
                }
            }
            pub struct Cursor {
                pub text: View<Char> = ""
                pub fx get: () View<Char> {
                    return text
                }
            }
            pub fx keep2: (a: Arr<Int32>) View<Int32> {
                return a
            }
            pub fx bad1: () View<Int32> {
                b: Buf = Buf { }
                return b.all()
            }
            pub fx bad2: () View<Char> {
                s: Str = "abc"
                c: Cursor = Cursor { text = s.view() }
                return c.get()
            }
            pub fx bad3: () View<Int32> {
                xs: Arr<Int32> = [1, 2]
                return keep2(xs)
            }
            pub fx bad4: () View<Int32> {
                b: Buf = Buf { }
                return b.tail()
            }
            pub fx ok1: (b: Buf) View<Int32> {
                return b.all()
            }
            pub fx ok2: (s: View<Char>) View<Char> {
                c: Cursor = Cursor { text = s }
                return c.get()
            }
            """,
        )
        expectExactly(p, "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return", "rules.escape.view-return")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("b.all() is a view of the local 'b'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("c.get() is a view of the local 's'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("keep2(xs) is a view of the local 'xs'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("b.tail() is a view of the local 'b'") }, messages.joinToString("\n"))
    }

    @Test
    fun aViewOfALocalCannotBeStoredWhereItOutlivesTheLocal() {
        // Round 2, issue 4b: a global, a mut parameter, a field of a mut parameter, this, and a callee that
        // keeps its parameter that way (a fixpoint: keep stores into GV, keepVia passes on to keep).
        val p = snippet(
            """
            pub mut GV: View<Char> = ""
            pub struct Holder {
                pub v: View<Char> = ""
                pub mut fx take: () Void {
                    s: Str = "abc"
                    v = s.view()
                }
            }
            pub fx keep: (v: View<Char>) Void {
                GV = v
            }
            pub fx keepVia: (w: View<Char>) Void {
                keep(w)
            }
            pub fx look: (v: View<Char>) Size {
                return v.size()
            }
            pub fx toGlobal: () Void {
                s: Str = "abc"
                GV = s.view()
            }
            pub fx toParam: (mut out: View<Char>) Void {
                s: Str = "abc"
                out = s.view()
            }
            pub fx toParamField: (mut h: Holder) Void {
                s: Str = "abc"
                h.v = s.view()
            }
            pub fx toKeeper: () Void {
                s: Str = "abc"
                keep(s.view())
                keepVia(s.view())
                n: Size = look(s.view())
            }
            pub fx ok: (p: View<Char>) Void {
                GV = p
                keep(p)
                mut h: Holder = Holder { }
                s: Str = "abc"
                h.v = s.view()
            }
            """,
        )
        expectExactly(p, "rules.escape.view-store", "rules.escape.view-store", "rules.escape.view-store", "rules.escape.view-store", "rules.escape.view-store", "rules.escape.view-store")
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.startsWith("s.view() is a view of the local 's', and 'v' outlives Holder.take") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("s.view() is a view of the local 's', and 'GV' outlives 'toGlobal'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("s.view() is a view of the local 's', and 'h.v' outlives 'toParamField'") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.startsWith("s.view() is a view of the local 's', and 'keepVia' keeps it beyond the call") }, messages.joinToString("\n"))
    }

    @Test
    fun aClosureKeepsWhatItCapturedOnlyWhenItLeavesTheCall() {
        // A lambda held in a local and called there outlives nothing; one stored into a mut parameter does.
        val p = snippet(
            """
            pub fx each: (v: View<Int32>, f: Fx<Tuple1<Int32>, Int32>) Int32 {
                g: Fx<Tuple0, Int32> = fx() Int32 {
                    return v[0]
                }
                return g() + f(1)
            }
            pub fx hold: (v: View<Int32>, mut out: Fx<Tuple0, Int32>) Void {
                out = fx() Int32 {
                    return v[0]
                }
            }
            pub fx f: (mut hook: Fx<Tuple0, Int32>) Int32 {
                a: Arr<Int32, 2> = [1, 2]
                n: Int32 = each(a.view(), fx(x: Int32) Int32 { return x })
                hold(a.view(), mut hook)
                return n
            }
            """,
        )
        expectExactly(p, "rules.escape.view-store")
        assertTrue(message(p, "rules.escape.view-store").startsWith("a.view() is a view of the local 'a', and 'hold' keeps it beyond the call"))
    }

    @Test
    fun aViewOfATemporaryDanglesAtOnce() {
        // Round 2, issue 4c: a lender on a call result, a construction or an array literal.
        val p = snippet(
            """
            pub fx mk: () Arr<Int32, 4> {
                return [1, 2, 3, 4]
            }
            pub fx sum: (v: View<Int32>) Int32 {
                return v[0]
            }
            pub struct Cursor {
                pub text: View<Int32>
            }
            pub fx t1: () Int32 {
                v: View<Int32> = mk().view()
                return v[0]
            }
            pub fx t2: () View<Int32> {
                return mk().view()
            }
            pub fx t3: () Int32 {
                c: Cursor = Cursor { text = mk().from(1) }
                return c.text[0]
            }
            pub fx t4: (p: View<Int32>) Int32 {
                mut v: View<Int32> = p
                v = mk().view()
                return v[0]
            }
            pub fx ok: () Int32 {
                return sum(mk().view()) + sum(Cursor { text = mk().view() }.text)
            }
            """,
        )
        expectExactly(p, "rules.escape.view-store", "rules.escape.view-return", "rules.escape.view-store", "rules.escape.view-store")
        assertTrue(message(p, "rules.escape.view-store").startsWith("mk().view() is a view of a temporary, which is destroyed at the end of this statement, and 'v' would keep it"))
        assertTrue(message(p, "rules.escape.view-return").startsWith("mk().view() is a view of a temporary, which is destroyed when 't2' returns"))
    }

    @Test
    fun aClassConstructedWithAViewTypeArgumentHoldsAView() {
        // Round 2, issue 7: the declaration of K2<T> cannot see it; the construction can.
        val p = snippet(
            """
            pub class K2<T> {
                pub mut f: Maybe<T> = null
            }
            pub fx mk: () Int32 {
                k: K2<View<Char>> = K2<View<Char>> { }
                ok: K2<Int32> = K2<Int32> { }
                return 1
            }
            """,
        )
        expectExactly(p, "rules.escape.view-field")
        assertTrue(message(p, "rules.escape.view-field").startsWith("Field 'f' of this K2<View<Char>> is a Maybe<View<Char>>, which holds a View<Char>"))
    }

    @Test
    fun aClassFieldCannotHoldAView() {
        val p = snippet(
            """
            pub class Holder {
                pub text: View<Char> = ""
                pub maybe: Maybe<MutView<UInt8>> = null
                pub n: Int32 = 0
            }
            pub struct Cursor {
                pub v: View<Char> = ""
            }
            pub class Keeper {
                pub mut h: Cursor = Cursor {}
                pub mut vs: List<View<Char>> = List<View<Char>> {}
                pub mut ok: List<Int32> = List<Int32> {}
            }
            """,
        )
        expectExactly(p, "rules.escape.view-field", "rules.escape.view-field", "rules.escape.view-field", "rules.escape.view-field")
        assertTrue(message(p, "rules.escape.view-field").contains("keep the view in a struct"))
        assertTrue(p.diagnostics.any { it.message.startsWith("Field 'h' of class Keeper is a Cursor, which holds a View<Char>") }, p.diagnostics.joinToString("\n") { it.message })
        assertTrue(p.diagnostics.any { it.message.startsWith("Field 'vs' of class Keeper is a List<View<Char>>, which holds a View<Char>") }, p.diagnostics.joinToString("\n") { it.message })
    }

    @Test
    fun aViewLocalEscapesWithWhatItAliases() {
        val p = snippet(
            """
            pub fx keep: (v: View<Int32>) View<Int32> {
                return v
            }
            pub fx f: (xs: View<Int32>) View<Int32> {
                v: View<Int32> = xs.from(1)
                w: View<Int32> = v
                return keep(w)
            }
            pub fx g: (ys: View<Int32>) Int32 {
                v: View<Int32> = ys.from(1)
                return v.size() as Int32
            }
            """,
        )
        expectClean(p)
        assertTrue(p.model.viewEscapes(fn(p, "f").params[0]), "f.xs escapes through v, w and keep")
        assertFalse(p.model.viewEscapes(fn(p, "g").params[0]), "g.ys is only read through v")
        assertEquals(true, p.model.viewEscapes[fn(p, "keep").params[0]])
    }
}
