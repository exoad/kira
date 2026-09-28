package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.PlaceKind
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.ViewOrigin
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
 * w2-4's S1-S3, w2-6's 0-2) among them.
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
        // examples of section 3.3 that the rule allows.
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
            pub fx header: (buf: List<UInt8>, mut h: Header) Bool {
                mut local: Header = Header {}
                return readHeader(buf.view(), mut local) && readHeader(buf.view(), mut h)
            }
            pub fx scalars: (xs: List<Int32>) Int32 {
                return total(tail(xs, nextSize())) + total2(xs.view(), bump())
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun aHiddenWriteOfAScalarFieldOrAPrintMovesNothing() {
        // 3.2's reading of "impure": a call that only prints, or writes storage no view can point into, has no
        // hidden write that moves a place. trace(total(this.buf.view())) is the design's example.
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
                    return total(buf.view(), log.note())
                }
            }
            """,
        )
        expectAllowed(p)
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
        // List of views at its parameter.
        assertEquals(List(10) { "rules.view.type" }, view(p), TyperTestSupport.render(p))
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
        // view parameter the lambda is refused for escaping into keep, whatever CB2's initially does.
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
        expectView(p, "rules.view.local", "rules.view.capture")
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
        assertTrue(messages(p, "rules.view.write").any { it.contains("it writes the global 'gl'") }, messages(p, "rules.view.write").joinToString("\n"))
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
            """,
        )
        expectView(p, "rules.view.extern", "rules.view.extern", "rules.view.extern", "rules.view.extern", "rules.view.type")
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
}
