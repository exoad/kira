package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.PlaceKind
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.ViewOrigin
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ViewPass: views are second-class (decision 4b, design 30-second-class). The positive cases are
 * the shapes the rule allows (section 9's A1-A6 among them); the negative ones are one or more
 * programs per code, every probe of the last converge verdict (w2-5-rules issues 0-5, w2-3's h1-h10,
 * w2-4's S1-S3, w2-6's 0-2) among them, and the second-class round 1 verdict's probes (P2*, P12*,
 * P13*, P5*, and the probes w2-3's and w2-4's notes left here), each as its whole program.
 * `rules.view.write` reads decision 4b literally: any IMPURE call in the span of a view of a
 * shared place or a mut global is refused.
 */
class ViewPassTest {
    private fun view(p: TypedProgram): List<String> = p.diagnostics.filter { it.code.startsWith("rules.view.") }.map { it.code }.sorted()

    /** The errors are exactly [codes] (in any order), all of them this pass's. */
    private fun expectView(p: TypedProgram, vararg codes: String) {
        assertEquals(codes.toList().sorted(), p.diagnostics.filter { it.isError }.map { it.code }.sorted(), TyperTestSupport.render(p))
    }

    private fun expectAllowed(p: TypedProgram) {
        assertTrue(p.diagnostics.none { it.isError }, "expected no error, got:\n${TyperTestSupport.render(p)}")
    }

    private fun messages(p: TypedProgram, code: String): List<String> = p.diagnostics.filter { it.code == code }.map { it.message }

    // ---- allowed (2.1, 2.2, 3.3) ---------------------------------------------------------------

    @Test
    fun argumentsReceiversAndReturnsOfViewParametersAreAllowed() {
        val p = snippet(
            """
            pub fx tail: (s: View<Char>, n: Size) View<Char> {
                return s.from(n)
            }
            pub fx wordEnd: (s: View<Char>, w: View<Char>) Maybe<Size> {
                n: Size = w.size()
                if s.size() < n || s.slice(0, n) != w {
                    return null
                }
                if s.size() > n && s[n] != ' ' {
                    return null
                }
                return n
            }
            pub fx pick: (a: View<Char>, b: View<Char>, first: Bool) View<Char> {
                return if first { a } else { b.from(1) }
            }
            pub fx sum: (buf: View<UInt8>) UInt32 {
                mut s: UInt32 = 0
                for b: UInt8 in buf {
                    s += b as UInt32
                }
                return s
            }
            pub fx useIt: (text: View<Char>, bytes: View<UInt8>) Size {
                n: Size = tail(tail(text, 1), 1).size()
                t: UInt32 = sum(bytes.slice(0, 2)) + sum(bytes.from(1))
                return n + wordEnd(text, "ab").unwrapOr(0)
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun aMethodReturnsAViewOfItsReceiverAndCallersPassItOn() {
        val p = snippet(
            """
            pub fx total: (v: View<Char>) Size {
                return v.size()
            }
            pub struct SBuf {
                pub data: Arr<Char, 4> = ['a', 'b', 'c', 'd']
                pub fx all: () View<Char> {
                    return data.view()
                }
                pub fx rest: () View<Char> {
                    return all().from(1)
                }
            }
            pub class CBuf {
                pub mut data: List<Char> = List<Char> {}
                pub fx all: () View<Char> {
                    return this.data.view()
                }
            }
            pub fx useIt: (c: CBuf) Size {
                b: SBuf = SBuf {}
                local: CBuf = CBuf {}
                return total(b.all()) + total(b.rest()) + total(c.all()) + total(local.all())
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun aViewOfATemporaryIsAnArgumentForTheWholeFullExpression() {
        // A1 and A2 of section 9: C++ keeps the temporary to the end of the full-expression. The typer's
        // types.view.temporary refused total(makeList()) before decision 4b; it is TEMP now.
        val p = snippet(
            """
            mut idx: Size = 0
            pub fx makeList: () List<Int32> {
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                xs.add(2)
                return xs
            }
            pub fx nextSize: () Size {
                idx = idx + 1
                return idx
            }
            pub fx total: (v: View<Int32>) Int32 {
                mut t: Int32 = 0
                for x: Int32 in v {
                    t += x
                }
                return t
            }
            pub fx tail: (xs: View<Int32>, at: Size) View<Int32> {
                return xs.from(at)
            }
            pub fx text: (v: View<Char>) Size {
                return v.size()
            }
            pub fx useIt: (s: Str) Int32 {
                n: Size = text((s + "x").view()) + text("lit")
                return total(makeList()) + total(makeList().view().from(1)) + total(tail(makeList(), nextSize()))
            }
            """,
        )
        expectAllowed(p)
        val origins = p.model.viewOrigins.values.flatten()
        assertTrue(origins.any { it is ViewOrigin.Temp }, origins.toString())
    }

    @Test
    fun aWriteOutsideTheSpanOrOfAnotherPlaceIsAllowed() {
        // A5 (the growth after the consumer returns), A6 (unilidar: element writes through a MutView), and the
        // examples of section 3.3 that the rule allows. An impure call beside a view of a PRIVATE place moves
        // nothing the view points into; beside a shared one it is refused (decision 4b read literally, next tests),
        // so header and scalars view locals now, and first's impure bump() runs before its view is formed.
        val p = snippet(
            """
            pub struct Header {
                pub type: UInt32 = 0
                pub size: UInt32 = 0
            }
            mut idx: Size = 0
            mut count: Int32 = 0
            pub fx nextSize: () Size {
                idx = idx + 1
                return idx
            }
            pub fx bump: () Int32 {
                count += 1
                return count
            }
            pub fx total: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub fx total2: (v: View<Int32>, n: Int32) Int32 {
                return n
            }
            pub fx tail: (xs: View<Int32>, at: Size) View<Int32> {
                return xs.from(at)
            }
            pub fx crc: (buf: View<UInt8>) UInt32 {
                return buf.size() as UInt32
            }
            pub fx writeU32: (p: MutView<UInt8>, v: UInt32) Void {
                p[0] = (v & 0xFF) as UInt8
            }
            pub fx readHeader: (buf: View<UInt8>, mut out: Header) Bool {
                out.size = buf.size() as UInt32
                return true
            }
            pub fx a5: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                xs.add(total(xs.view()))
                return total(xs)
            }
            pub fx a6: () UInt32 {
                mut p: Arr<UInt8, 32> = Arr<UInt8, 32> {}
                writeU32(p.from(20), crc(p.slice(12, 8)))
                return crc(p)
            }
            pub fx first: (n: Int32, v: View<Int32>) Int32 {
                return n
            }
            pub fx header: (mut h: Header) Bool {
                mut buf: List<UInt8> = List<UInt8> {}
                mut local: Header = Header {}
                return readHeader(buf.view(), mut local) && readHeader(buf.view(), mut h)
            }
            pub fx scalars: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                return total(tail(xs, nextSize())) + total2(xs.view(), bump())
            }
            pub fx before: (xs: List<Int32>) Int32 {
                return first(bump(), xs.view()) + total(xs.view())
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun anImpureCallBeforeTheViewOrAfterItsConsumerIsOutsideTheSpan() {
        // Replaces round 1's aHiddenWriteOfAScalarFieldOrAPrintMovesNothing, which relied on 3.2's softer reading
        // (total(buf.view(), log.note()) with note bumping an Int32 is refused now, below). A print or a note()
        // outside the span of a view of this.buf is fine: trace runs after size returns, and log.note() runs
        // before buf.view() is formed; the old total(...) is split so that trace(t) runs in its own statement.
        val p = snippet(
            """
            pub class Log {
                pub mut n: Int32 = 0
                pub mut fx note: () Int32 {
                    n += 1
                    return n
                }
            }
            pub fx total: (v: View<Int32>, k: Int32) Int32 {
                return k
            }
            pub fx size: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub class Sensor {
                pub mut buf: List<Int32> = List<Int32> {}
                pub log: Log = Log {}
                pub fx read: () Int32 {
                    trace(size(buf.view()))
                    k: Int32 = log.note()
                    t: Int32 = total(buf.view(), k)
                    trace(t)
                    return log.note() + size(buf.view())
                }
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun anyImpureCallInTheSpanOfASharedOrGlobalViewIsAWrite() {
        // Decision 4b read literally (DECISIONS-user 4b, 30-second-class 3.2's "one-line swap"): beside a view of a
        // shared place (a field of a class, a const& or mut parameter, anything behind a Ref or handle) or a mut
        // global, any IMPURE call in the span is refused, whatever it writes: a note() bumping an Int32 field, a
        // trace, a consumer that writes only its own mut Header parameter, a scalar global's writer, and a callee
        // writing an Arr field's elements through the MutView it is lent.
        val p = snippet(
            """
            pub struct Header {
                pub type: UInt32 = 0
                pub size: UInt32 = 0
            }
            mut idx: Size = 0
            mut count: Int32 = 0
            mut COUNTS: List<Int32> = List<Int32> {}
            pub fx nextSize: () Size {
                idx = idx + 1
                return idx
            }
            pub fx bump: () Int32 {
                count += 1
                return count
            }
            pub class Log {
                pub mut n: Int32 = 0
                pub mut fx note: () Int32 {
                    n += 1
                    return n
                }
            }
            pub fx total: (v: View<Int32>, k: Int32) Int32 {
                return k
            }
            pub fx size: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub fx tail: (xs: View<Int32>, at: Size) View<Int32> {
                return xs.from(at)
            }
            pub fx fill: (p: MutView<Int32>, v: Int32) Void {
                p[0] = v
            }
            pub fx readHeader: (buf: View<UInt8>, mut out: Header) Bool {
                out.size = buf.size() as UInt32
                return true
            }
            pub class Sensor {
                pub mut buf: List<Int32> = List<Int32> {}
                pub mut raw: Arr<Int32, 4> = [1, 2, 3, 4]
                pub log: Log = Log {}
                pub fx read: () Int32 {
                    return total(buf.view(), log.note())
                }
                pub fx show: () Void {
                    trace(size(buf.view()) + total(buf.view(), log.note()))
                }
                pub mut fx put: () Void {
                    fill(raw.from(0 as Size), 6)
                }
            }
            pub fx header: (buf: List<UInt8>, mut h: Header) Bool {
                return readHeader(buf.view(), mut h)
            }
            pub fx scalars: (xs: List<Int32>) Int32 {
                return size(tail(xs, nextSize())) + total(xs.view(), bump())
            }
            pub fx global: () Int32 {
                return total(COUNTS.view(), bump())
            }
            """,
        )
        expectView(p, *Array(7) { "rules.view.write" })
        val m = messages(p, "rules.view.write")
        assertTrue(m.any { it.contains("'log.note()' may replace, grow or free 'this.buf' (it is impure, and 'this.buf' is shared storage") }, m.joinToString("\n"))
        assertTrue(m.any { it.contains("'readHeader(buf.view(), mut h)'") }, m.joinToString("\n"))
        assertTrue(m.any { it.contains("'bump()' may replace, grow or free 'COUNTS' (it is impure, and 'COUNTS' is a mut global") }, m.joinToString("\n"))
    }

    @Test
    fun aBorrowingLambdaMayBePassedToAParameterThatDoesNotEscape() {
        val p = snippet(
            """
            pub fx run: (f: Fx<Tuple0, Size>) Size {
                return f()
            }
            pub fx eachPacket: (buf: View<UInt8>, each: Fx<Tuple1<View<UInt8>>, Void>) Size {
                mut at: Size = 0
                while at < buf.size() {
                    each(buf.slice(at, 1))
                    at += 1
                }
                return at
            }
            pub fx withView: (v: View<UInt8>) Size {
                n: Size = run(fx() Size {
                    return v.size()
                })
                return n + eachPacket(v, fx(p: View<UInt8>) Void {
                    trace(p.size() + v.size())
                })
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun aViewSafeGenericTakesAView() {
        val p = snippet(
            """
            pub fx twice<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                return f(x) + f(x)
            }
            pub fx relay<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                return twice<T>(x, f)
            }
            pub fx useIt: (s: View<Char>) Size {
                return relay<View<Char>>(s, fx(v: View<Char>) Size {
                    return v.size()
                })
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun anExternTakesViewsCStrsAndUnsafePointersAsParameters() {
        val p = snippet(
            """
            @_extern(cpp = "ext::put", header = "ext.hxx")
            pub fx put: (p: Unsafe<Int32>, mut q: Unsafe<Int32>, v: View<UInt8>, t: CStr) Int32;
            @_extern(cpp = "ext::name", header = "ext.hxx")
            pub fx name: () Str;
            pub fx peek: (p: Unsafe<Int32>, mut q: Unsafe<Int32>) Int32;
            pub fx total: (points: View<Int32>, mut sum: Int32) Bool;
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun theOriginsAreRecordedForTheEmitter() {
        val p = snippet(
            """
            mut GL: List<Int32> = List<Int32> {}
            pub fx total: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub fx mk: () List<Int32> {
                return List<Int32> {}
            }
            pub fx useIt: (xs: List<Int32>, v: View<Int32>) Int32 {
                mut ys: List<Int32> = List<Int32> {}
                return total(ys.view()) + total(xs.view()) + total(GL.view()) + total(v) + total(mk().view())
            }
            """,
        )
        expectAllowed(p)
        fun origin(text: String): Set<ViewOrigin> = p.model.viewOrigins(BodyTestSupport.node<FunctionCallExpr>(p, text, "test:main"))
        assertEquals(PlaceKind.PRIVATE, (origin("ys.view()").single() as ViewOrigin.Stored).kind)
        assertEquals(PlaceKind.SHARED, (origin("xs.view()").single() as ViewOrigin.Stored).kind, "a const& parameter may be bound to anyone's storage")
        assertEquals(PlaceKind.GLOBAL, (origin("GL.view()").single() as ViewOrigin.Stored).kind)
        assertTrue(origin("mk().view()").single() is ViewOrigin.Temp)
        val stored = origin("ys.view()").single() as ViewOrigin.Stored
        assertTrue(stored.place is Place.Local && !stored.within, "a lender views its receiver's own buffer")
    }

    @Test
    fun theUnilidarAndTextGoldensAreSecondClassAlready() {
        // 30-second-class 8: unilidar needs no change, and text's word became wordEnd, an offset. A6 is unilidar's
        // writeU32(p.from(20), crc32(p.slice(12, 8))): p is command's own Frame, a private place.
        val corpus = java.io.File("src/test/resources/cpp-golden")
        for (case in listOf("unilidar", "text")) {
            val p = TyperTestSupport.project(java.io.File(corpus, case), net.exoad.kira.compiler.analysis.types.TyperMode.STRICT)
            assertTrue(view(p).isEmpty(), "$case:\n${TyperTestSupport.render(p)}")
        }
        val p = TyperTestSupport.project(java.io.File(corpus, "unilidar"), net.exoad.kira.compiler.analysis.types.TyperMode.STRICT)
        val slice = p.model.viewOrigins(BodyTestSupport.node<FunctionCallExpr>(p, "p.slice(12, 8)", "pilot:unilidar")).single() as ViewOrigin.Stored
        assertEquals(PlaceKind.PRIVATE, slice.kind)
        val packet = p.model.viewOrigins(BodyTestSupport.node<FunctionCallExpr>(p, "buf.from(at)", "pilot:unilidar")).single()
        assertTrue(packet is ViewOrigin.Param && packet.param.name == "buf", packet.toString())
    }

    // ---- rules.view.local ---------------------------------------------------------------------

    @Test
    fun aViewIsNeverKeptInALocal() {
        // w2-5 #0 Q1e, w2-4 S1 (arrview...) and S2 (strelem...): each is refused at the local; its later uses are not
        // reported again.
        val p = snippet(
            """
            pub class Buf {
                pub mut data: List<Char> = List<Char> {}
                pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub class Sub: Buf {
            }
            pub fx tail: (xs: View<Int32>, at: Size) View<Int32> {
                return xs.from(at)
            }
            pub fx q1e: () View<Char> {
                b: Buf = Buf {}
                v: View<Char> = b.all()
                return v
            }
            pub fx arrview: () Int32 {
                mut arr: Arr<Int32, 3> = [1, 2, 3]
                w: View<Int32> = arr.view()
                arr = [4, 5, 6]
                return w[0]
            }
            pub fx strelem: (names: List<Str>) Size {
                s: View<Char> = names[0].view()
                return s.size()
            }
            pub fx lent: (xs: List<Int32>) Int32 {
                u: View<Int32> = tail(xs, 1)
                m: View<Int32> = xs
                return u[0] + m[0]
            }
            """,
        )
        expectView(p, "rules.view.local", "rules.view.local", "rules.view.local", "rules.view.local", "rules.view.local")
        assertTrue(messages(p, "rules.view.local").any { it.startsWith("'v' would hold b.all(), a View<Char>: a view is never kept in a local") }, messages(p, "rules.view.local").joinToString("\n"))
    }

    // ---- rules.view.type ----------------------------------------------------------------------

    @Test
    fun aTypeThatHoldsAViewCannotBeWritten() {
        // w2-5 #0 Q10, #1 Q7, #3 Q4b; w2-3 #0 h1 (a view-typed global); w2-4 S3 viewmutparam, viewmutlist; the text
        // golden's old Maybe<View<Char>> result.
        val p = snippet(
            """
            pub mut GV: View<Char> = ""
            pub struct Cursor {
                pub text: View<Char> = ""
            }
            pub class Holder<T> {
                pub mut item: Maybe<T> = null
            }
            pub fx word: (s: View<Char>) Maybe<View<Char>> {
                return s.from(1)
            }
            pub fx q10: (mut out: View<Char>) Void {
                out = "x"
            }
            pub fx q7: (a: View<Char>) Size {
                mut vs: List<View<Char>> = List<View<Char>> {}
                vs.add(a)
                return vs.size()
            }
            pub fx q4b: (r: Ref<View<Char>>, a: Str) Void {
                r.value = a.view()
            }
            pub fx pair: (a: View<UInt8>) Tuple2<View<UInt8>, Size> {
                return Tuple2<View<UInt8>, Size> { a, a.size() }
            }
            pub fx holder: () Void {
                h: Holder<View<Int32>> = Holder<View<Int32>> {}
            }
            pub fx h9: (sink: Ref<List<View<Int32>>>, r: Ref<List<Int32>>) Void {
                sink.value.add(r.value.view())
            }
            """,
        )
        // Ten: the tuple is refused at pair's result and again at the construction that writes it; w2-3 #0 h9's Ref of a
        // List of views at its parameter. And one write: h9's sink.value.add is impure, and r.value lies behind a Ref
        // (sink and r may be one box, so the add may grow the List the view points into).
        assertEquals(List(10) { "rules.view.type" } + "rules.view.write", view(p), TyperTestSupport.render(p))
        assertTrue(messages(p, "rules.view.type").any { it.contains("return an index, Maybe<Size>, and let the caller slice") }, messages(p, "rules.view.type").joinToString("\n"))
    }

    // ---- rules.view.generic -------------------------------------------------------------------

    @Test
    fun aGenericThatReturnsOrStoresItsTCannotTakeAView() {
        val p = snippet(
            """
            pub fx idG<T>: (x: T) T {
                return x
            }
            pub fx keepG<T>: (x: T) Size {
                mut xs: List<T> = List<T> {}
                xs.add(x)
                return xs.size()
            }
            pub fx relayG<T>: (x: T) Size {
                return keepG<T>(x)
            }
            pub fx g1: (s: View<Char>) Size {
                return idG<View<Char>>(s).size()
            }
            pub fx g2: (s: View<Char>) Size {
                return keepG<View<Char>>(s)
            }
            pub fx g3: (s: View<Char>) Size {
                return relayG<View<Char>>(s)
            }
            pub fx ok: (s: View<Char>) Int32 {
                return idG<Int32>(s.size() as Int32) + (keepG<Int32>(1) as Int32)
            }
            """,
        )
        expectView(p, "rules.view.generic", "rules.view.generic", "rules.view.generic")
        val m = messages(p, "rules.view.generic")
        assertTrue(m.any { it.startsWith("'idG' cannot take T = View<Char>: 'idG' returns a T") }, m.joinToString("\n"))
        assertTrue(m.any { it.startsWith("'relayG' cannot take T = View<Char>: 'relayG' passes a View") || it.contains("relayG' passes") }, m.joinToString("\n"))
    }

    @Test
    fun aDispatchedGenericIsViewSafeOnlyWhenEveryOverrideIs() {
        // w2-5 round 1 minor (P5a, P5b): the trait method Keeper.keep<T> is bodiless, and the virtual Base.keep<T>
        // keeps nothing, but the override a call runs may capture x: T in a lambda stored in a global. Control:
        // Counter.count<T>, whose one implementation only calls f.
        val p = snippet(
            """
            mut KEPT: List<Fx<Tuple0, Size>> = List<Fx<Tuple0, Size>> {}
            pub trait Keeper {
                pub fx keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size;
            }
            pub class K: Keeper {
                override pub fx keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    KEPT.add(fx() Size {
                        return f(x)
                    })
                    return 0
                }
            }
            pub class Base {
                pub fx keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    return 0
                }
            }
            pub class Sub: Base {
                override pub fx keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    KEPT.add(fx() Size {
                        return f(x)
                    })
                    return 0
                }
            }
            pub trait Counter {
                pub fx count<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size;
            }
            pub class C: Counter {
                override pub fx count<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    return f(x)
                }
            }
            pub fx p5a: (k: Keeper, s: View<Char>) Size {
                return k.keep<View<Char>>(s, fx(v: View<Char>) Size {
                    return v.size()
                })
            }
            pub fx p5b: (b: Base, s: View<Char>) Size {
                return b.keep<View<Char>>(s, fx(v: View<Char>) Size {
                    return v.size()
                })
            }
            pub fx ok: (c: Counter, s: View<Char>) Size {
                return c.count<View<Char>>(s, fx(v: View<Char>) Size {
                    return v.size()
                })
            }
            """,
        )
        // No rule M (50-round4 2.3 row 1, round 5b): each KEPT.add is handed a lambda that runs a captured Fx value, but
        // add takes it as a T and only keeps it, so it is not charged.
        expectView(p, "rules.view.generic", "rules.view.generic")
        val m = messages(p, "rules.view.generic")
        assertTrue(m.any { it.startsWith("'keep' cannot take T = View<Char>: its override in K captures the T 'x' in a lambda that escapes") }, m.joinToString("\n"))
        assertTrue(m.any { it.startsWith("'keep' cannot take T = View<Char>: its override in Sub captures") }, m.joinToString("\n"))
    }

    // ---- rules.view.return --------------------------------------------------------------------

    @Test
    fun aViewOfALocalsObjectIsNeverReturnedWhateverTheDispatch() {
        // w2-5 #0 Q1, Q1d; #1 Q3b, Q11; #2 Q9, Q2, Q3: the origin is the local's object, whatever the dispatch and
        // whatever the class holds.
        val p = snippet(
            """
            pub class Buf {
                pub mut data: List<Char> = List<Char> {}
                pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub class Sub: Buf {
                override pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub trait Viewer {
                pub fx all: () View<Char>;
            }
            pub class Impl: Viewer {
                pub mut data: List<Char> = List<Char> {}
                override pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub class Holder {
                pub mut data: List<Char> = List<Char> {}
                pub mut cb: Maybe<Fx<Tuple0, Size>> = null
                pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub class Box<T> {
                pub mut data: List<Char> = List<Char> {}
                pub mut item: Maybe<T> = null
                pub fx all: () View<Char> {
                    return data.view()
                }
            }
            pub fx q1: () View<Char> {
                b: Buf = Buf {}
                b.data.add('a')
                return b.all()
            }
            pub fx q1d: () View<Char> {
                b: Buf = Buf {}
                return b.data.view()
            }
            pub fx q2: () View<Char> {
                h: Viewer = Impl {}
                return h.all()
            }
            pub fx q3b: () View<Char> {
                h: Holder = Holder {}
                return h.all()
            }
            pub fx q9: () View<Char> {
                i: Impl = Impl {}
                return i.all()
            }
            pub fx q11: () View<Char> {
                b: Box<Fx<Tuple0, Int32>> = Box<Fx<Tuple0, Int32>> {}
                return b.all()
            }
            """,
        )
        expectView(p, *Array(6) { "rules.view.return" })
        assertTrue(messages(p, "rules.view.return").any { it.startsWith("'q1' returns b.all(), a view of storage inside 'b'") }, messages(p, "rules.view.return").joinToString("\n"))
    }

    @Test
    fun aViewOfALocalAParameterThatIsNoViewOrATemporaryIsNeverReturned() {
        // w2-5 #1 Q7b (a List<Fx> local), Q7c (a generic body's own List<T>), Q7d's control, and the remedy for a
        // container parameter: take it as a View parameter.
        val p = snippet(
            """
            pub fx mk: () List<Int32> {
                return List<Int32> {}
            }
            pub fx q7b: () View<Fx<Tuple0, Size>> {
                mut fs: List<Fx<Tuple0, Size>> = List<Fx<Tuple0, Size>> {}
                fs.add(fx() Size { return 1 as Size })
                return fs.view()
            }
            pub fx q7c<T>: (a: T) View<T> {
                mut xs: List<T> = List<T> {}
                xs.add(a)
                return xs.view()
            }
            pub fx q7d: () View<Int32> {
                mut xs: List<Int32> = List<Int32> {}
                return xs
            }
            pub fx q7e<T>: (a: T) View<T> {
                mut xs: List<T> = List<T> {}
                xs.add(a)
                return xs
            }
            pub fx param: (xs: List<Int32>) View<Int32> {
                return xs.from(1)
            }
            pub fx temp: () View<Int32> {
                return mk().view()
            }
            pub fx concat: (a: Str, b: Str) View<Char> {
                return (a + b).view()
            }
            pub fx inLambda: (v: View<Char>) Size {
                f: Fx<Tuple1<View<Char>>, View<Char>> = fx(w: View<Char>) View<Char> {
                    return w.from(1)
                }
                return f(v).size()
            }
            """,
        )
        // q7e is Q7c with the List converted to its view by the typer (Coercion.ToView) rather than by .view().
        expectView(p, *Array(7) { "rules.view.return" })
        assertTrue(messages(p, "rules.view.return").any { it.contains("Take xs as a View<T> parameter") }, messages(p, "rules.view.return").joinToString("\n"))
    }

    @Test
    fun aLambdaReturnsOnlyItsOwnViewParameters() {
        val p = snippet(
            """
            pub fx apply: (f: Fx<Tuple0, Size>) Size {
                return f()
            }
            pub fx useIt: (v: View<Char>) Size {
                g: Fx<Tuple1<View<Char>>, View<Char>> = fx(w: View<Char>) View<Char> {
                    return v
                }
                return g(v).size()
            }
            """,
        )
        // The lambda captures the view parameter v (a borrowing lambda) and is kept in a local, and it returns v,
        // which is not its own parameter.
        assertEquals(listOf("rules.view.capture", "rules.view.return"), view(p), TyperTestSupport.render(p))
    }

    // ---- rules.view.position ------------------------------------------------------------------

    @Test
    fun aViewIsOnlyAnArgumentAReceiverOrAReturn() {
        // R5 of section 9 (a for over a temporary's view), a for over a local's view, an if-expression with statements,
        // and a view whose value is dropped.
        val p = snippet(
            """
            pub fx mk: () List<Int32> {
                return List<Int32> {}
            }
            pub fx total: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub fx r5: () Int32 {
                mut t: Int32 = 0
                for x: Int32 in mk().view() {
                    t += x
                }
                return t
            }
            pub fx local: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                mut t: Int32 = 0
                for x: Int32 in xs.view() {
                    t += x
                }
                return t
            }
            pub fx branches: (a: View<Int32>, xs: List<Int32>, c: Bool) Int32 {
                return total(if c {
                    n: Int32 = 1
                    a
                } else {
                    xs.view()
                })
            }
            pub fx dropped: (a: View<Int32>) Void {
                a.from(1)
            }
            """,
        )
        expectView(p, *Array(5) { "rules.view.position" })
        assertTrue(messages(p, "rules.view.position").any { it.contains("Iterate the owner, for x in xs") }, messages(p, "rules.view.position").joinToString("\n"))
    }

    // ---- rules.view.capture -------------------------------------------------------------------

    @Test
    fun aLambdaThatCapturesAViewNeverEscapes() {
        // w2-5 #3 and #4 with a view parameter in place of the refused view local: kept in a local, returned, handed to
        // a function that stores it (Q5b's link), to a mut fx that stores it (Q6's keep) and to a stdlib container.
        val p = snippet(
            """
            pub class CB2 {
                pub mut f: Maybe<Fx<Tuple0, Char>> = null
                pub mut fx keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub mut GH: Maybe<CB2> = null
            pub fx link: (h: CB2, g: Fx<Tuple0, Char>) Void {
                h.f = g
            }
            pub fx inLocal: (v: View<Char>) Char {
                f: Fx<Tuple0, Char> = fx() Char {
                    return v[0]
                }
                return f()
            }
            pub fx returned: (v: View<Char>) Fx<Tuple0, Char> {
                return fx() Char {
                    return v[0]
                }
            }
            pub fx q5b: (v: View<Char>) Void {
                link(GH.unwrap(), fx() Char {
                    return v[0]
                })
            }
            pub fx q6: (v: View<Char>) Void {
                GH.unwrap().keep(fx() Char {
                    return v[0]
                })
            }
            pub fx listed: (v: View<Char>, mut fs: List<Fx<Tuple0, Char>>) Void {
                fs.add(fx() Char {
                    return v[0]
                })
            }
            """,
        )
        expectView(p, *Array(5) { "rules.view.capture" })
        assertTrue(messages(p, "rules.view.capture").all { it.startsWith("This lambda captures the view 'v'") }, messages(p, "rules.view.capture").joinToString("\n"))
    }

    @Test
    fun theConvergeProbesOfALambdaOverAViewLocalAreRefusedAtTheLocal() {
        // w2-5 #3 Q4, Q4c, Q4d; #4 Q5, Q5b, Q5c; #5 Q6, Q6b, Q6c, exactly as the verifier wrote them: the view local
        // is refused, and the lambda that captures it is not reported again.
        val p = snippet(
            """
            pub class CB2 {
                pub mut f: Maybe<Fx<Tuple0, Char>> = null
                pub mut fx keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub class Box2 {
                pub mut item: Maybe<CB2> = null
            }
            pub mut GH: Maybe<CB2> = null
            pub mut GR: Maybe<Ref<Maybe<Fx<Tuple0, Char>>>> = null
            pub fx link: (h: CB2, g: Fx<Tuple0, Char>) Void {
                h.f = g
            }
            pub fx put: (bx: Box2, x: CB2) Void {
                bx.item = x
            }
            pub fx get: () CB2 {
                return GH.unwrap()
            }
            pub fx q4: (r: Ref<Maybe<Fx<Tuple0, Char>>>, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r.value = fx() Char {
                    return v.get(0 as Size)
                }
            }
            pub fx q4c: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r: Ref<Maybe<Fx<Tuple0, Char>>> = GR.unwrap()
                r.value = fx() Char {
                    return v.get(0 as Size)
                }
            }
            pub fx q4d: (r: Ref<List<Fx<Tuple0, Char>>>, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r.value.add(fx() Char {
                    return v.get(0 as Size)
                })
            }
            pub fx q5: (holder: CB2, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                link(holder, fx() Char {
                    return v.get(0 as Size)
                })
            }
            pub fx q5c: (bx: Box2, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                c: CB2 = CB2 {}
                c.f = fx() Char {
                    return v.get(0 as Size)
                }
                put(bx, c)
            }
            pub fx q6b: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                get().keep(fx() Char {
                    return v.get(0 as Size)
                })
            }
            pub fx q5b: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                link(GH.unwrap(), fx() Char {
                    return v.get(0 as Size)
                })
            }
            pub fx q6: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                GH.unwrap().keep(fx() Char {
                    return v.get(0 as Size)
                })
            }
            """,
        )
        expectView(p, *Array(8) { "rules.view.local" })
    }

    @Test
    fun q6cAClassThatPublishesItselfChangesNothingForALambdaOverAView() {
        // w2-5 #5 Q6c: CB2's initially stores this in a global; the view local is refused all the same, and with a
        // view parameter the lambda is refused for escaping into keep, whatever CB2's initially does. (Publishing this
        // from initially is itself rules.escape.this-in-initially since W2.9 1.2.11.)
        val p = snippet(
            """
            pub class CB2 {
                pub mut f: Maybe<Fx<Tuple0, Char>> = null
                initially {
                    GH = this
                }
                pub mut fx keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub mut GH: Maybe<CB2> = null
            pub fx q6c: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                CB2 {}.keep(fx() Char {
                    return v.get(0 as Size)
                })
            }
            pub fx q6cParam: (v: View<Char>) Void {
                CB2 {}.keep(fx() Char {
                    return v[0]
                })
            }
            """,
        )
        expectView(p, "rules.view.local", "rules.view.capture", "rules.escape.this-in-initially")
    }

    // ---- rules.view.store ---------------------------------------------------------------------

    @Test
    fun aViewIsNeverPassedWhereAFirstClassValueIsKept() {
        // View<T> is an Iterable<T>, and Any takes anything: a view is never boxed into either (2.1). This typer has
        // no implicit conversion to a trait value or to Any yet (W2.9 adds boxing), so it refuses each of these as a
        // mismatch too; the store rule is what still refuses them once the conversion exists.
        val p = snippet(
            """
            pub fx count: (it: Iterable<Char>) Size {
                return it.size()
            }
            pub struct K {
                pub it: Maybe<Iterable<Char>> = null
            }
            pub fx asTrait: (v: View<Char>) Size {
                return count(v)
            }
            pub fx inField: (v: View<Char>) Size {
                k: K = K { it = v }
                return 0
            }
            pub fx asAny: (v: View<UInt8>) UInt64 {
                return bitCast<UInt64>(v)
            }
            """,
        )
        assertEquals(listOf("rules.view.store", "rules.view.store", "rules.view.store"), view(p), TyperTestSupport.render(p))
        assertTrue(messages(p, "rules.view.store").any { it.startsWith("v is a view, passed to 'count' where a Iterable<Char> value is kept") }, messages(p, "rules.view.store").joinToString("\n"))
    }

    @Test
    fun aViewConsumedByTheCallItIsPassedToIsNoStore() {
        // A view formatted into a Str, or measured, is consumed during the call: nothing keeps it.
        val p = snippet(
            """
            pub fx size: (v: View<Char>) Size {
                return v.size()
            }
            pub fx texts: (v: View<Char>, mut out: StrBuf<32>) Str {
                out.add(v)
                out.set("n=${'$'}{size(v)}")
                trace(size(v.from(1)))
                return "${'$'}{v.size()}"
            }
            """,
        )
        expectAllowed(p)
    }

    // ---- rules.view.write ---------------------------------------------------------------------

    @Test
    fun aPlaceIsNotMovedWhileItsViewIsInUse() {
        // 3.3's refused examples: countOf(gl, growL()) (a global's hidden write, R3 of section 9), f(xs.view(), mut xs)
        // and sb.add(sb.view()) (named writes containing the viewed place), use(this.buf.view(), log.note()) (a class
        // method that grows a List through a reference: heap), w2-4 S1 arrhanded (total refills b.arr through b).
        val p = snippet(
            """
            mut gl: List<Int32> = List<Int32> {}
            pub fx growL: () Int32 {
                gl.add(1)
                return 1
            }
            pub fx countOf: (v: View<Int32>, n: Int32) Int32 {
                return v.size() as Int32 + n
            }
            pub fx f: (v: View<Int32>, mut xs: List<Int32>) Int32 {
                xs.add(1)
                return 1
            }
            pub class Log {
                pub mut lines: List<Int32> = List<Int32> {}
                pub mut fx note: () Int32 {
                    lines.add(1)
                    return 1
                }
            }
            pub class Sensor {
                pub mut buf: List<Int32> = List<Int32> {}
                pub log: Log = Log {}
                pub fx read: () Int32 {
                    return countOf(buf.view(), log.note())
                }
            }
            pub class B {
                pub mut arr: Arr<Int32> = [1, 2, 3]
            }
            pub fx refill: (v: View<Int32>, b: B) Int32 {
                b.arr = [4, 5, 6]
                return v[0]
            }
            pub fx r3: () Int32 {
                return countOf(gl, growL())
            }
            pub fx named: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                return f(xs.view(), mut xs)
            }
            pub fx self: (mut sb: StrBuf<16>) Void {
                sb.add(sb.view())
            }
            pub fx arrhanded: (b: B) Int32 {
                return refill(b.arr.view(), b)
            }
            """,
        )
        // The two named writes are D37's too: ExclusivityPass refuses a mut argument or receiver that overlaps another operand.
        expectView(p, "rules.exclusivity.argument", "rules.exclusivity.receiver", *Array(5) { "rules.view.write" })
        assertTrue(messages(p, "rules.view.write").any { it.contains("'growL()' may replace, grow or free 'gl' (it is impure") }, messages(p, "rules.view.write").joinToString("\n"))
    }

    @Test
    fun aCallThatMayWriteAnythingOrStorageTheViewMayBeIsAWriteToo() {
        // A trait call and a callback write anything (3.2); a mut parameter may be bound to a global; a const&
        // parameter's view is shared, and a hidden write of a global of its type may be the same storage; the call that
        // forms a view of its receiver may free it first (b.all() on a class whose all() resets a global holding it).
        val p = snippet(
            """
            mut gl: List<Int32> = List<Int32> {}
            pub trait Sink {
                pub fx poke: () Int32;
            }
            pub fx countOf: (v: View<Int32>, n: Int32) Int32 {
                return n
            }
            pub fx both: (v: View<Int32>, mut xs: List<Int32>) Int32 {
                return 1
            }
            pub fx run: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            pub fx grow: () Int32 {
                gl.add(1)
                return 1
            }
            pub fx viaTrait: (s: Sink) Int32 {
                return countOf(gl.view(), s.poke())
            }
            pub fx viaLambda: () Int32 {
                return countOf(gl.view(), run(fx() Int32 {
                    gl.add(2)
                    return 2
                }))
            }
            pub fx viaMutParam: (mut other: List<Int32>) Int32 {
                return both(gl.view(), mut other)
            }
            pub fx viaSharedParam: (xs: List<Int32>) Int32 {
                return countOf(xs.view(), grow())
            }
            """,
        )
        expectView(p, *Array(4) { "rules.view.write" })
    }

    // ---- round 1's probes, each whole program as the verifier or its sibling package wrote it --------

    private class Probe(val source: String, val codes: List<String>)

    private fun expectProbes(probes: List<Pair<String, Probe>>) {
        val wrong = probes.mapNotNull { (name, probe) ->
            val p = snippet(probe.source)
            val got = p.diagnostics.filter { it.isError }.map { it.code }.sorted()
            if (got == probe.codes.sorted()) null else "$name: want ${probe.codes.sorted()}, got $got\n${TyperTestSupport.render(p)}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun theProbesW24AndW23LeftToThisPassAreRefused() {
        // Round 1's notes: w2-4's S1-S3 probes (scratchpad v-w24c4/probes) and w2-3's h1, h5, h6, h7, h9, h10 and k3
        // (vw23cr4, sc31), which their branches no longer refuse themselves. Each is refused here: a view kept in a
        // local or a type, or a view of shared or global storage beside an impure call (arrhanded's total refills
        // b.arr, viewmutlist's and h9's add grows a List behind a reference, k3's growThenSum grows gl).
        expectProbes(
            listOf(
            "w24-arrview" to Probe(
                """
                // A view of a class's Arr<Int32> field; the field is assigned a new Arr (a std::vector move-assign frees the old buffer); the view is read.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    v: View<Int32> = b.arr.view()
                    b.arr = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                    trace(v.size())
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrsame" to Probe(
                """
                // arrview with an Arr of the same length: `a = [4, 5]` on an Arr<Int32> field, the shape the classes part says leaves the buffer in place.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    v: View<Int32> = b.arr.view()
                    b.arr = [4, 5]
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrview2" to Probe(
                """
                // A method views its own Arr<Int32> field, assigns the field a new Arr, then reads the view.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]

                    pub mut fx swap: () Int32 {
                        v: View<Int32> = arr.view()
                        arr = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                        return v.get(0) + v.get(1)
                    }
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    trace(b.swap())
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrparam" to Probe(
                """
                // A const& Arr<Int32> parameter bound to a class field; the body views it, the field is assigned a new Arr through
                // another handle, and the view is read.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]

                    pub mut fx refill: () Void {
                        arr = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                    }
                }

                pub fx sum: (a: Arr<Int32>, b: Bag) Int32 {
                    v: View<Int32> = a.view()
                    b.refill()
                    return v.get(0) + v.get(1)
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    c: Bag = b
                    trace(sum(c.arr, b))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrhanded" to Probe(
                """
                // A view of a class's Arr<Int32> field handed to a function that assigns the field a new Arr, then reads the view.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]

                    pub mut fx refill: () Void {
                        arr = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                    }
                }

                pub fx total: (v: View<Int32>, b: Bag) Int32 {
                    b.refill()
                    return v.get(0) + v.get(1)
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    trace(total(b.arr.view(), b))
                    return 0
                }
                """,
                listOf("rules.view.write"),
            ),
            "w24-arrmut" to Probe(
                """
                // A view of a class's Arr<Int32> field; the field is passed mut to a function that assigns it a new Arr; the view is read.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]
                }

                pub fx refill: (mut a: Arr<Int32>) Void {
                    a = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    v: View<Int32> = b.arr.view()
                    refill(mut b.arr)
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrmethod" to Probe(
                """
                // A method returns a view of its Arr<Int32> field; the caller assigns the field a new Arr, then reads the view.
                pub class Bag {
                    pub mut arr: Arr<Int32> = [1, 2]

                    pub fx all: () View<Int32> {
                        return arr.view()
                    }
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    v: View<Int32> = b.all()
                    b.arr = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-arrref" to Probe(
                """
                // A view of the Arr<Int32> a Ref box holds; the box is given a new Arr; the view is read.
                pub fx main: () Int32 {
                    r: Ref<Arr<Int32>> = Ref<Arr<Int32>> { value = [1, 2] }
                    v: View<Int32> = r.value.view()
                    r.value = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36]
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-strelem" to Probe(
                """
                // A view of a Str element of a class's List<Str>; the element is assigned a new Str by index; the view is read.
                pub class Bag {
                    pub mut names: List<Str> = List<Str> {}
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    b.names.add("the first long name that does not fit in the small string buffer")
                    v: View<Char> = b.names[0].view()
                    b.names[0] = "another long name that does not fit in the small string buffer either, and it goes on and on and on well past any capacity the first one had, so the assignment has to allocate a new buffer and free the old one"
                    trace(v.size())
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-strelem2" to Probe(
                """
                // A method views a Str element of its own List<Str> field, assigns that element by index, then reads the view.
                pub class Bag {
                    pub mut names: List<Str> = List<Str> {}

                    pub mut fx rename: () Size {
                        v: View<Char> = names[0].view()
                        names[0] = "another long name that does not fit in the small string buffer either, and it goes on and on and on well past any capacity the first one had, so the assignment has to allocate a new buffer and free the old one"
                        trace(v.get(0))
                        return v.size()
                    }
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    b.names.add("the first long name that does not fit in the small string buffer")
                    trace(b.rename())
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-strparam" to Probe(
                """
                // A Str parameter bound to an element of a class's List<Str>; the body views it, the element is assigned by index
                // through another handle, and the view is read.
                pub class Bag {
                    pub mut names: List<Str> = List<Str> {}

                    pub mut fx rename: () Void {
                        names[0] = "another long name that does not fit in the small string buffer either, and it goes on and on and on well past any capacity the first one had, so the assignment has to allocate a new buffer and free the old one"
                    }
                }

                pub fx firstChar: (s: Str, b: Bag) Char {
                    v: View<Char> = s.view()
                    b.rename()
                    return v.get(0)
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    b.names.add("the first long name that does not fit in the small string buffer")
                    c: Bag = b
                    trace(firstChar(c.names[0], b))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-listelemparam" to Probe(
                """
                // A view of an element List of a class's List<List<Int32>>, element replaced by index inside a method call; the view is read.
                pub class Bag {
                    pub mut lists: List<List<Int32>> = List<List<Int32>> {}

                    pub mut fx replace: () Void {
                        mut other: List<Int32> = List<Int32> {}
                        mut i: Int32 = 0
                        while i < 100 {
                            other.add(9)
                            i = i + 1
                        }
                        lists[0] = other
                    }
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    mut first: List<Int32> = List<Int32> {}
                    first.add(1)
                    first.add(2)
                    b.lists.add(first)
                    v: View<Int32> = b.lists[0].view()
                    b.replace()
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-refelem" to Probe(
                """
                // A view of a Str element of the List a Ref box holds; the element is assigned by index; the view is read.
                pub fx main: () Int32 {
                    mut names: List<Str> = List<Str> {}
                    names.add("the first long name that does not fit in the small string buffer")
                    r: Ref<List<Str>> = Ref<List<Str>> { value = names }
                    v: View<Char> = r.value[0].view()
                    r.value[0] = "another long name that does not fit in the small string buffer either, and it goes on and on and on well past any capacity the first one had, so the assignment has to allocate a new buffer and free the old one"
                    trace(v.size())
                    trace(v.get(0))
                    trace(v.get(1))
                    return 0
                }
                """,
                listOf("rules.view.local"),
            ),
            "w24-viewmutparam" to Probe(
                """
                // A view local passed `mut` to a function that grows the field the view points into, then reads the view.
                pub class Bag {
                    pub mut items: List<Int32> = List<Int32> {}

                    pub mut fx grow: () Void {
                        mut i: Int32 = 0
                        while i < 100 {
                            items.add(9)
                            i = i + 1
                        }
                    }
                }

                pub fx peek: (mut v: View<Int32>, b: Bag) Int32 {
                    b.grow()
                    n: Int32 = v.get(1)
                    v = v.from(0)
                    return n
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    b.items.add(1)
                    b.items.add(2)
                    mut v: View<Int32> = b.items.view()
                    trace(peek(mut v, b))
                    return 0
                }
                """,
                listOf("rules.view.type", "rules.view.local"),
            ),
            "w24-viewmutlist" to Probe(
                """
                // A local List of views passed `mut` to a function that grows the field the views point into, then reads one.
                pub class Bag {
                    pub mut items: List<Int32> = List<Int32> {}

                    pub mut fx grow: () Void {
                        mut i: Int32 = 0
                        while i < 100 {
                            items.add(9)
                            i = i + 1
                        }
                    }
                }

                pub fx peek: (mut vs: List<View<Int32>>, b: Bag) Int32 {
                    b.grow()
                    n: Int32 = vs.get(0).get(1)
                    vs.clear()
                    return n
                }

                pub fx main: () Int32 {
                    b: Bag = Bag {}
                    b.items.add(1)
                    b.items.add(2)
                    mut vs: List<View<Int32>> = List<View<Int32>> {}
                    vs.add(b.items.view())
                    trace(peek(mut vs, b))
                    return 0
                }
                """,
                listOf("rules.view.type", "rules.view.type", "rules.view.write"),
            ),
            "w23-h1" to Probe(
                """
                // A user function stores a view of its Ref parameter's field in a global; the caller hands it
                // a fresh Ref, whose box the kira::Rc temporary frees at the end of the statement.

                mut gv: Maybe<MutView<Int32>> = null

                fx makeRef: () Ref<List<Int32>> {
                    return Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } }
                }

                fx keepField: (r: Ref<List<Int32>>) Void {
                    gv = r.value.view()
                }

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx main: () Void {
                    keepField(makeRef())
                    // Kira: 1360.
                    trace(total(gv.unwrap()))
                }
                """,
                listOf("rules.view.type"),
            ),
            "w23-h5" to Probe(
                """
                // An Fx value whose lambda stores a view of its Ref parameter's field in a global, handed a
                // fresh Ref.

                mut gv: Maybe<MutView<Int32>> = null

                fx makeRef: () Ref<List<Int32>> {
                    return Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } }
                }

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx main: () Void {
                    k: Fx<Tuple1<Ref<List<Int32>>>, Void> = fx (r: Ref<List<Int32>>) Void {
                        gv = r.value.view()
                    }
                    k(makeRef())
                    // Kira: 1360.
                    trace(total(gv.unwrap()))
                }
                """,
                listOf("rules.view.type"),
            ),
            "w23-h6" to Probe(
                """
                use "kira:sync"

                // A temporary Mutex whose value is a Ref: lock hands the Ref to a lambda that keeps a view of
                // the Ref's field. The Mutex temporary is the Ref's only owner.

                mut gv: Maybe<MutView<Int32>> = null

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx main: () Void {
                    Mutex<Ref<List<Int32>>> { value = Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } } }.lock(fx (mut l: Ref<List<Int32>>) Void {
                        gv = l.value.view()
                    })
                    // Kira: 1360.
                    trace(total(gv.unwrap()))
                }
                """,
                listOf("rules.view.type"),
            ),
            "w23-h7" to Probe(
                """
                // A user function stores a view of the field of the Ref inside its Maybe parameter; the
                // caller passes a fresh Maybe of a fresh Ref.

                mut gv: Maybe<MutView<Int32>> = null

                fx makeMRef: () Maybe<Ref<List<Int32>>> {
                    return Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } }
                }

                fx keepM: (m: Maybe<Ref<List<Int32>>>) Void {
                    gv = m.value.value.view()
                }

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx main: () Void {
                    keepM(makeMRef())
                    // Kira: 1360.
                    trace(total(gv.unwrap()))
                }
                """,
                listOf("rules.view.type"),
            ),
            "w23-h9" to Probe(
                """
                // A lambda written in a generic function keeps a view of its Ref argument's field; called
                // through a concrete Fx value with a fresh Ref.

                mut gref: Ref<List<MutView<Int32>>> = Ref<List<MutView<Int32>>> { value = List<MutView<Int32>> { } }

                fx makeRef: () Ref<List<Int32>> {
                    return Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } }
                }

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx mkKeeper<T>: (sink: Ref<List<MutView<T>>>) Fx<Tuple1<Ref<List<T>>>, Void> {
                    return fx (r: Ref<List<T>>) Void {
                        sink.value.add(r.value.view())
                    }
                }

                fx main: () Void {
                    k: Fx<Tuple1<Ref<List<Int32>>>, Void> = mkKeeper<Int32>(gref)
                    k(makeRef())
                    // Kira: 1360.
                    trace(total(gref.value[0]))
                }
                """,
                listOf("rules.view.type", "rules.view.type", "rules.view.type", "rules.view.type", "rules.view.write"),
            ),
            "w23-h10" to Probe(
                """
                use "kira:sync"

                // A user function hands its Mutex parameter's Ref value to a lambda that keeps a view of the
                // Ref's field; the caller passes a temporary Mutex (round 4's n5, with a Ref inside).

                mut gv: Maybe<MutView<Int32>> = null

                fx total: (v: MutView<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx lockAndKeep: (m: Mutex<Ref<List<Int32>>>) Void {
                    m.lock(fx (mut l: Ref<List<Int32>>) Void {
                        gv = l.value.view()
                    })
                }

                fx main: () Void {
                    lockAndKeep(Mutex<Ref<List<Int32>>> { value = Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160] } } })
                    // Kira: 1360.
                    trace(total(gv.unwrap()))
                }
                """,
                listOf("rules.view.type"),
            ),
            "w23-k3" to Probe(
                """
                mut gl: List<Int32> = List<Int32> { values = [100, 200, 300] }

                fx growL: () Int32 {
                    mut i: Int32 = 0
                    while i < 64 {
                        gl.add(i)
                        i += 1
                    }
                    return 0
                }

                fx total: (v: View<Int32>) Int32 {
                    mut s: Int32 = 0
                    for x: Int32 in v {
                        s += x
                    }
                    return s
                }

                fx growThenSum: (v: View<Int32>) Int32 {
                    k: Int32 = growL()
                    return total(v) + k
                }

                fx main: () Void {
                    trace(growThenSum(gl.view()))
                }
                """,
                listOf("rules.view.write"),
            ),
            ),
        )
    }

    @Test
    fun theShapesRound1BrokeAreImpureSoTheViewBesideThemIsRefused() {
        // w2-5 round 1's three significant findings, accepted with 0 diagnostics then, each a use-after-free under
        // ASan: a finally run by a drop outside a Kira body (P2a, P2d, P2e through an extern's Fx; P2f and P2g, a
        // Maker whose initially drops the last Dropper, sequenced first by C++17), a MutView of a mut global formed
        // inside a callee (P12, P12b, P12d), and an Fx nested in an extern argument (P13a, P13b, P13d). EffectsPass
        // is conservative on each, and decision 4b refuses any impure call in the span. Two new ones: a Kira callee
        // that only drops a local Dropper, and a trait-typed or generic parameter, which may be one. Control: a
        // class whose finally writes only its own locals.
        expectProbes(
            listOf(
            "r1-p2a" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                mut GD: Maybe<Dropper> = null
                @_extern(cpp = "ext::apply", header = "ext.hxx")
                pub fx apply: (v: View<Int32>, f: Fx<Tuple0, Void>) Int32;
                pub fx p2a: () Int32 {
                    GL.add(1000)
                    GD = Dropper {}
                    return apply(GL.view(), fx() Void {
                        GD = null
                    })
                }
                """,
                listOf("rules.view.write"),
            ),
            "r1-p2d" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                pub class Holder {
                    pub mut d: Maybe<Dropper> = null
                }
                mut GH: Maybe<Holder> = null
                @_extern(cpp = "ext::apply", header = "ext.hxx")
                pub fx apply: (v: View<Int32>, f: Fx<Tuple0, Void>) Int32;
                pub fx p2d: () Int32 {
                    GL.add(1000)
                    GH = Holder {}
                    return apply(GL.view(), fx() Void {
                        GH.value.d = null
                    })
                }
                """,
                listOf("rules.view.write"),
            ),
            "r1-p2e" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                mut GFS: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> {}
                @_extern(cpp = "ext::apply", header = "ext.hxx")
                pub fx apply: (v: View<Int32>, f: Fx<Tuple0, Void>) Int32;
                pub fx arm: () Void {
                    d: Dropper = Dropper {}
                    GFS.add(fx() Void {
                        trace(d.n)
                    })
                }
                pub fx p2e: () Int32 {
                    GL.add(1000)
                    arm()
                    return apply(GL.view(), fx() Void {
                        GFS.clear()
                    })
                }
                """,
                // Round 4's rule M too: GFS is a mut global, and clear on it may drop the last Dropper, whose IMPURE
                // finally may write anything, GFS included, while the binding writes through GFS (not CONFINED). add
                // drops nothing, and its lambda is CONFINED (round 5).
                listOf("rules.view.write", "rules.exclusivity.mut"),
            ),
            "r1-p2f" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                mut GD: Maybe<Dropper> = null
                pub class Maker {
                    pub mut n: Int32 = 0
                    initially {
                        GD = null
                    }
                }
                pub fx p2f: () Int32 {
                    GL.add(1000)
                    GD = Dropper {}
                    return GL.view().get((Maker {}).n as Size)
                }
                """,
                listOf("rules.view.write"),
            ),
            "r1-p2g" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                mut GD: Maybe<Dropper> = null
                pub class Maker {
                    pub mut n: Int32 = 0
                    initially {
                        GD = null
                    }
                }
                pub fx p2g: () Int32 {
                    GL.add(1000)
                    GD = Dropper {}
                    return GL.view()[(Maker {}).n as Size]
                }
                """,
                listOf("rules.view.write"),
            ),
            "r2-drop-local" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Dropper {
                    pub mut n: Int32 = 0
                    finally {
                        GL = List<Int32> {}
                    }
                }
                pub fx take: () Int32 {
                    d: Dropper = Dropper {}
                    return d.n
                }
                pub fx total: (v: View<Int32>, k: Int32) Int32 {
                    return v.size() as Int32 + k
                }
                pub fx p: () Int32 {
                    GL.add(1000)
                    return total(GL.view(), take())
                }
                """,
                listOf("rules.view.write"),
            ),
            "r2-drop-trait" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub trait Shape {
                    pub fx area: () Int32;
                }
                pub class Sq: Shape {
                    pub mut n: Int32 = 0
                    override pub fx area: () Int32 {
                        return n
                    }
                    finally {
                        GL = List<Int32> {}
                    }
                }
                pub fx forget: (s: Maybe<Shape>) Int32 {
                    return 0
                }
                pub fx dropG<T>: (x: T) Int32 {
                    return 0
                }
                pub fx total: (v: View<Int32>, k: Int32) Int32 {
                    return v.size() as Int32 + k
                }
                pub fx p: () Int32 {
                    return total(GL.view(), forget(null)) + total(GL.view(), dropG<Int32>(1))
                }
                """,
                listOf("rules.view.write", "rules.view.write"),
            ),
            "r2-drop-pure-finally" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub class Quiet {
                    pub mut n: Int32 = 0
                    finally {
                        mut k: Int32 = 1
                        k += 1
                    }
                }
                pub fx take: () Int32 {
                    d: Quiet = Quiet {}
                    return d.n
                }
                pub fx total: (v: View<Int32>, k: Int32) Int32 {
                    return v.size() as Int32 + k
                }
                pub fx p: () Int32 {
                    GL.add(1000)
                    return total(GL.view(), take())
                }
                """,
                listOf(),
            ),
            "r1-p12" to Probe(
                """
                mut GXSS: List<List<Int32>> = List<List<Int32>> {}
                pub fx clobber: (mv: MutView<List<Int32>>) Int32 {
                    mv[0] = List<Int32> {}
                    return 0
                }
                pub fx consumeG: (v: View<Int32>) Int32 {
                    n: Int32 = clobber(GXSS.view())
                    return v[0] + n
                }
                pub fx p12: () Int32 {
                    GXSS.add(List<Int32> {})
                    GXSS[0].add(7)
                    return consumeG(GXSS[0].view())
                }
                """,
                listOf("rules.view.write", "rules.view.write"),
            ),
            "r1-p12b" to Probe(
                """
                mut GXSS: List<List<Int32>> = List<List<Int32>> {}
                pub fx clobber: (mv: MutView<List<Int32>>) Int32 {
                    mv[0] = List<Int32> {}
                    return 0
                }
                pub fx clobberG: () Int32 {
                    return clobber(GXSS.view())
                }
                pub fx total: (v: View<Int32>, k: Int32) Int32 {
                    return v[0] + k
                }
                pub fx p12b: () Int32 {
                    GXSS.add(List<Int32> {})
                    GXSS[0].add(7)
                    return total(GXSS[0].view(), clobberG())
                }
                """,
                listOf("rules.view.write", "rules.view.write"),
            ),
            "r1-p12d" to Probe(
                """
                mut GXSS: List<List<Int32>> = List<List<Int32>> {}
                pub fx grow: (mv: MutView<List<Int32>>) Int32 {
                    mv[0].add(1)
                    return 0
                }
                pub fx consumeG: (v: View<Int32>) Int32 {
                    n: Int32 = grow(GXSS.view())
                    return v[0] + n
                }
                pub fx p12d: () Int32 {
                    GXSS.add(List<Int32> {})
                    GXSS[0].add(7)
                    return consumeG(GXSS[0].view())
                }
                """,
                listOf("rules.view.write", "rules.view.write"),
            ),
            "r1-p13a" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                @_extern(cpp = "ext::applyAll", header = "ext.hxx")
                pub fx applyAll: (v: View<Int32>, fs: Arr<Fx<Tuple0, Void>>) Int32;
                pub fx p13a: () Int32 {
                    return applyAll(GL.view(), [fx() Void {
                        GL = List<Int32> {}
                    }])
                }
                """,
                listOf("rules.view.write"),
            ),
            "r1-p13b" to Probe(
                """
                mut GL: List<Int32> = List<Int32> {}
                pub struct Cb {
                    pub f: Fx<Tuple0, Void> = fx() Void {}
                }
                @_extern(cpp = "ext::applyCb", header = "ext.hxx")
                pub fx applyCb: (v: View<Int32>, c: Cb) Int32;
                pub fx p13b: () Int32 {
                    return applyCb(GL.view(), Cb { f = fx() Void {
                        GL = List<Int32> {}
                    } })
                }
                """,
                listOf("rules.view.write"),
            ),
            "r1-p13d" to Probe(
                """
                pub struct Cb {
                    pub f: Fx<Tuple0, Void> = fx() Void {}
                }
                @_extern(cpp = "ext::applyCb", header = "ext.hxx")
                pub fx applyCb: (v: View<Int32>, c: Cb) Int32;
                pub class Rx {
                    pub mut data: List<Int32> = List<Int32> {}
                    pub mut fx reset: () Void {
                        data = List<Int32> {}
                    }
                    pub mut fx go: () Int32 {
                        return applyCb(data.view(), Cb { f = fx() Void {
                            reset()
                        } })
                    }
                }
                """,
                listOf("rules.view.write"),
            ),
            ),
        )
    }

    // ---- rules.view.extern and rules.view.unsafe ----------------------------------------------

    @Test
    fun anExternNeverReturnsAPointer() {
        // w2-6 #0 (a List<CStr> result: a type that holds a pointer, 1.2), #1 (slot() returning an Unsafe), #2a (an Fx
        // returning a CStr).
        val p = snippet(
            """
            @_extern(cpp = "ext::name", header = "ext.hxx")
            pub fx name: () CStr;
            @_extern(cpp = "ext::text", header = "ext.hxx")
            pub fx text: () View<Char>;
            @_extern(cpp = "ext::tokens", header = "ext.hxx")
            pub fx tokens: (line: Str) List<CStr>;
            @_extern(cpp = "ext::slot", header = "ext.hxx")
            pub fx slot: () Unsafe<Int32>;
            @_extern(cpp = "ext::tailFn", header = "ext.hxx")
            pub fx tailFn: () Fx<Tuple0, CStr>;
            pub @_opaque class Buf {
                pub fx label: () CStr;
                pub fx bytes: () View<UInt8>;
            }
            """,
        )
        // An @_opaque class's bodiless methods are C++'s too (w2-5 round 1 minor): refused at the declaration.
        expectView(p, *Array(6) { "rules.view.extern" }, "rules.view.type")
        assertTrue(messages(p, "rules.view.extern").any { it.startsWith("The extern 'bytes' returns a View<UInt8>") }, messages(p, "rules.view.extern").joinToString("\n"))
    }

    @Test
    fun unsafeIsAnExternParameterTypeOnly() {
        // w2-6 #1's mut out: Unsafe<CStr> (a view of stored pointers) and #2b's Maybe<CStr> global.
        val p = snippet(
            """
            pub fx kira: (p: Unsafe<Int32>) Int32 {
                return 1
            }
            pub struct Holder {
                pub p: Unsafe<Int32>
            }
            pub fx made: () Unsafe<Int32> {
                return made()
            }
            @_extern(cpp = "ext::fill", header = "ext.hxx")
            pub fx fill: (mut out: Unsafe<CStr>) Void;
            pub mut SAVED: Maybe<CStr> = null
            """,
        )
        assertEquals(listOf("rules.view.type", "rules.view.type", "rules.view.unsafe", "rules.view.unsafe", "rules.view.unsafe"), view(p), TyperTestSupport.render(p))
    }

    // ---- round 3 (40-round3 F2, F3, and round 2's minors) ------------------------------------------

    @Test
    fun aSecondClassArgumentIsNeverANamedWrite() {
        // F2 (w2-6 #0, a regression): a `mut p: Unsafe<T>` is a T* by value (1.4), so passing it a MutView of a place
        // moves nothing; with a local the canonical call of design 1.4 (fillP(xs.view(), xs.size(), 7)) is allowed.
        // With a shared place (a mut List parameter) the extern is IMPURE beside a view of shared storage: literal 4b
        // refuses it. W2.6's typer lets a view reach a `mut Unsafe<T>` and keeps the call's byRef (CallResolver); this
        // branch's typer does not type that call yet, so the test types the same calls through a MutView parameter and
        // sets byRef on the binding as W2.6's CallResolver does, then runs the passes again: no new refusal.
        val p = snippet(
            """
            @_extern(cpp = "ext::fillP", header = "ext.hxx")
            pub fx fillP: (p: MutView<Int32>, n: Size, v: Int32) Void;
            @_extern(cpp = "ext::setFirst", header = "ext.hxx")
            pub fx setFirst: (p: MutView<Int32>, v: Int32) Void;
            @_extern(cpp = "ext::peek", header = "ext.hxx")
            pub fx peek: (p: View<Int32>, q: MutView<Int32>) Int32;
            pub fx locals: () Int32 {
                mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
                mut arr: Arr<Int32, 3> = [1, 2, 3]
                ys: List<Int32> = List<Int32> { values = [4] }
                fillP(xs.view(), xs.size(), 7)
                fillP(arr.view(), 3, 1)
                setFirst(xs.view(), 9)
                return peek(ys.view(), xs.view())
            }
            pub fx shared: (mut xs: List<Int32>) Void {
                fillP(xs.view(), xs.size(), 7)
            }
            """,
        )
        fun check() {
            assertEquals(listOf("rules.view.write"), p.diagnostics.filter { it.isError }.map { it.code }, TyperTestSupport.render(p))
            assertTrue(messages(p, "rules.view.write").single().contains("'xs' is shared storage any impure call may write"), messages(p, "rules.view.write").single())
        }
        check()
        for (call in BodyTestSupport.every<FunctionCallExpr>(p)) {
            val rc = p.model.calls[call] ?: continue
            if (rc.fn?.name !in setOf("fillP", "setFirst", "peek")) {
                continue
            }
            val q = rc.args.lastIndex.takeIf { rc.fn?.name == "peek" } ?: 0
            p.model.calls[call] = rc.copy(args = rc.args.mapIndexed { i, a -> if (i == q) (a as ArgBinding.Given).copy(byRef = true) else a })
        }
        net.exoad.kira.compiler.analysis.types.rules.ViewPass().run(p)
        net.exoad.kira.compiler.analysis.types.rules.ExclusivityPass().run(p)
        check()
    }

    @Test
    fun anIfExpressionThatPicksAViewOfATemporaryIsRefusedWhereItStands() {
        // F3 (KI-13, r1 w2-3 minor #1): C++ cannot keep one branch's temporary without making the other's, so an
        // if-expression with a TEMP origin in a branch is rules.view.position, as a for over a temporary is. The
        // remedy, an if statement with one call per branch, is allowed; so is an if-expression over places.
        val p = snippet(
            """
            pub mut gl: List<Int32> = List<Int32> { values = [1] }
            pub mut ticks: Int32 = 0
            pub fx next: () Int32 {
                ticks += 1
                return ticks
            }
            pub fx makeList: () List<Int32> {
                return List<Int32> { values = [2] }
            }
            pub fx minus: (v: View<Int32>, k: Int32) Int32 {
                return v.size() as Int32 - k
            }
            pub fx total: (v: View<Int32>) Int32 {
                return v.size() as Int32
            }
            pub fx f: (c: Bool) Int32 {
                a: Int32 = minus(if c { makeList().view() } else { gl.view() }, next())
                b: Int32 = total(if c { makeList().view() } else { gl.view() })
                mut d: Int32 = 0
                if c {
                    d = total(makeList().view())
                } else {
                    d = total(gl.view())
                }
                mut xs: List<Int32> = List<Int32> { values = [3] }
                e: Int32 = total(if c { xs.view() } else { xs.view().from(1) })
                return a + b + d + e
            }
            """,
        )
        // The design's own example also forms a view of the mut global gl beside the impure next(): literal 4b, at gl.view().
        assertEquals(listOf("rules.view.position", "rules.view.position", "rules.view.write"), view(p), TyperTestSupport.render(p))
        assertTrue(messages(p, "rules.view.position").all { it.contains("in an if-expression") && it.contains("Use an if statement, one call per branch") }, messages(p, "rules.view.position").joinToString("\n"))
    }

    @Test
    fun aBorrowingLambdaGivenToAnFxValueNamesTheCalleeAsWritten() {
        // Round 2's w2-3 minor #2 (probe c3): the message read "is passed to '', which keeps it" for an Fx-value callee.
        val p = snippet(
            """
            pub fx user: (v: View<Int32>, sink: Fx<Tuple1<Fx<Tuple0, Int32>>, Void>) Void {
                sink(fx () Int32 { return v[0] })
            }
            """,
        )
        assertEquals(listOf("rules.view.capture"), view(p), TyperTestSupport.render(p))
        assertTrue(messages(p, "rules.view.capture").single().contains("is passed to 'sink', which keeps it"), messages(p, "rules.view.capture").single())
    }

    // ---- round 4 (50-round4 2.4, O1 and O2): storage behind a handle that is no place is SHARED --------

    @Test
    fun storageBehindAReferenceTypedExpressionThatIsNoPlaceIsSharedInBothSpellings() {
        // w2-5 #0 (round 3, probes a2/a2i, a3b/a3c, mx callref and pickM, tw): a view of storage reached through a call
        // result of class type was TEMP, so a writer beside it was never checked (gcc printed 515117665 for 16000, MSVC
        // ASan a heap-use-after-free) while the index spelling was refused. O2: a receiver-less root through a reference
        // step (pick(h).items) is SHARED, and a lent result on it is a place; O1: the receiver of a view-returning
        // method that is no place (pick(h)) names the object behind it, a Referent, never TEMP. Every row is refused, in
        // both spellings, and so is h.itemsView() on a parameter (the a3c control).
        val p = snippet(
            """
            pub class H {
                pub mut items: List<List<Int32>> = List<List<Int32>> { }
                pub mut m: Maybe<List<Int32>> = null
                pub fx itemsView: () View<Int32> {
                    return items.get(0).view()
                }
                pub mut fx wipeThen: (v: View<Int32>) Int32 {
                    items = List<List<Int32>> { }
                    return total(v)
                }
                pub fx me: () H {
                    return this
                }
                pub mut fx reset: () Int32 {
                    items = List<List<Int32>> { }
                    return 0
                }
                pub mut fx viaMe: () Int32 {
                    return sumK(me().items.get(0).view(), reset())
                }
                pub mut fx viaMeIdx: () Int32 {
                    return sumK(me().items[0].view(), reset())
                }
                pub mut fx viaThisMe: () Int32 {
                    return sumK(this.me().items.get(0).view(), reset())
                }
            }
            pub fx total: (v: View<Int32>) Int32 {
                mut s: Int32 = 0
                for x: Int32 in v {
                    s += x
                }
                return s
            }
            pub fx sumK: (v: View<Int32>, k: Int32) Int32 {
                return total(v) + k
            }
            pub fx wipe: (h: H) Int32 {
                h.items = List<List<Int32>> { }
                return 0
            }
            pub fx wipeThen: (v: View<Int32>, h: H) Int32 {
                wipe(h)
                return total(v)
            }
            pub fx pick: (h: H) H {
                return h
            }
            pub fx pickM: (h: H) Maybe<H> {
                return h
            }
            pub fx rows: (h: H) Int32 {
                a: Int32 = wipeThen(pick(h).items.get(0).view(), h)
                b: Int32 = wipeThen(pick(h).items[0].view(), h)
                c: Int32 = wipeThen(pick(h).m.unwrap().view(), h)
                d: Int32 = wipeThen(pick(h).m.value.view(), h)
                e: Int32 = h.wipeThen(h.me().items.get(0).view())
                f: Int32 = h.wipeThen(h.me().items[0].view())
                g: Int32 = sumK(pick(h).itemsView(), wipe(h))
                k: Int32 = sumK(h.itemsView(), wipe(h))
                m: Int32 = sumK(pickM(h).unwrap().items.get(0).view(), wipe(h))
                n: Int32 = sumK(pickM(h).value.items[0].view(), wipe(h))
                q: Int32 = wipeThen(pickM(h).unwrap().items.get(0).view(), h)
                return a + b + c + d + e + f + g + k + m + n + q
            }
            """,
        )
        assertEquals(List(14) { "rules.view.write" }, p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        // O2 as LentPlaces records it: the call spelling is the index spelling's place, rooted at pick(h).items.
        val lent = p.model.readPlace(BodyTestSupport.node<Expr>(p, "pick(h).items.get(0)"))
        val indexed = p.model.readPlace(BodyTestSupport.node<Expr>(p, "pick(h).items[0]"))
        assertEquals((indexed as Place.Index).container, (lent as Place.Index).container)
        // O1: the view pick(h).itemsView() forms points into the object behind pick(h).
        val formed = BodyTestSupport.all<Expr>(p, "pick(h).itemsView()").flatMap { p.model.viewOrigins(it) }
        assertTrue(formed.any { it is ViewOrigin.Referent }, formed.toString())
    }

    @Test
    fun aLentResultOnAValueTemporaryIsStillATemporary() {
        // O2's other side: mk() returns a struct, so mk().items reaches no handle; a view of it is TEMP and only its own
        // full-expression names it, which no writer can reach.
        val p = snippet(
            """
            pub struct S {
                pub items: List<List<Int32>> = List<List<Int32>> { }
            }
            pub mut G: Int32 = 0
            pub fx mk: () S {
                return S { }
            }
            pub fx bump: () Int32 {
                G += 1
                return 0
            }
            pub fx sumK: (v: View<Int32>, k: Int32) Int32 {
                return k
            }
            pub fx rows: () Int32 {
                return sumK(mk().items.get(0).view(), bump()) + sumK(mk().items[0].view(), bump())
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.code.startsWith("rules.view") }, TyperTestSupport.render(p))
    }

    @Test
    fun aViewBesideACallThatStartsAThreadWritingItsPlaceIsRefused() {
        // Round 6, scratchpad w25r5c x/t7 as the verifier wrote it: bump constructs a Thread whose body replaces gl, and
        // joins it at the drop. Round 5b ranked the construction PURE, so the view of gl beside bump() was accepted
        // (gcc and MSVC 15300; clang formed the view first and read the freed buffer: 6).
        val p = snippet(
            """
            use "kira:sync"

            mut gl: List<Int32> = [1, 2, 3]

            fx bump: () Int32 {
                t: Thread = Thread { name = "w", body = fx () Void {
                    gl = [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700]
                } }
                return 0
            }

            fx sumView: (v: View<Int32>, n: Int32) Int32 {
                mut t: Int32 = n
                for x: Int32 in v {
                    t += x
                }
                return t
            }

            fx main: () Void {
                trace(sumView(gl.view(), bump()))
            }
            """,
        )
        expectView(p, "rules.view.write")
        assertTrue(messages(p, "rules.view.write").single().contains("gl"), messages(p, "rules.view.write").toString())
    }
}
