package net.exoad.kira.types.rules

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
            pub tail: (s: View<Char>, n: Size) View<Char> {
                s.from(n)
            }
            pub wordEnd: (s: View<Char>, w: View<Char>) Maybe<Size> {
                n: Size = w.size()
                s.size() < n || s.slice(0, n) != w ? null
                    : s.size() > n && s[n] != ' ' ? null : n
            }
            pub pick: (a: View<Char>, b: View<Char>, first: Bool) View<Char> {
                first ? a : b.from(1)
            }
            pub sum: (buf: View<UInt8>) UInt32 {
                s: Mutable<UInt32> = 0
                for b: UInt8 in buf {
                    s += b as UInt32
                }
                s
            }
            pub useIt: (text: View<Char>, bytes: View<UInt8>) Size {
                n: Size = tail(tail(text, 1), 1).size()
                t: UInt32 = sum(bytes.slice(0, 2)) + sum(bytes.from(1))
                n + wordEnd(text, "ab").unwrapOr(0)
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun aMethodReturnsAViewOfItsReceiverAndCallersPassItOn() {
        val p = snippet(
            """
            pub total: (v: View<Char>) Size {
                v.size()
            }
            pub SBuf: Struct {
                pub data: Arr<Char, 4> = @arrOf('a', 'b', 'c', 'd')
                pub all: () View<Char> {
                    data.view()
                }
                pub rest: () View<Char> {
                    all().from(1)
                }
            }
            pub CBuf: Mutable<Any> {
                pub data: Mutable<List<Char>> = List<Char> {}
                pub all: () View<Char> {
                    this.data.view()
                }
            }
            pub useIt: (c: CBuf) Size {
                b: SBuf = SBuf {}
                local: CBuf = CBuf {}
                total(b.all()) + total(b.rest()) + total(c.all()) + total(local.all())
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
            idx: Mutable<Size> = 0
            pub makeList: () List<Int32> {
                xs: Mutable<List<Int32>> = List<Int32> {}
                xs.add(1)
                xs.add(2)
                xs
            }
            pub nextSize: () Size {
                idx = idx + 1
                idx
            }
            pub total: (v: View<Int32>) Int32 {
                t: Mutable<Int32> = 0
                for x: Int32 in v {
                    t += x
                }
                t
            }
            pub tail: (xs: View<Int32>, at: Size) View<Int32> {
                xs.from(at)
            }
            pub text: (v: View<Char>) Size {
                v.size()
            }
            pub useIt: (s: Str) Int32 {
                n: Size = text((s + "x").view()) + text("lit")
                total(makeList()) + total(makeList().view().from(1)) + total(tail(makeList(), nextSize()))
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
            pub Header: Mutable<Any> {
                pub type: UInt32 = 0
                pub size: Mutable<UInt32> = 0
            }
            idx: Mutable<Size> = 0
            count: Mutable<Int32> = 0
            pub nextSize: () Size {
                idx = idx + 1
                idx
            }
            pub bump: () Int32 {
                count += 1
                count
            }
            pub total: (v: View<Int32>) Int32 {
                v.size() as Int32
            }
            pub total2: (v: View<Int32>, n: Int32) Int32 {
                n
            }
            pub tail: (xs: View<Int32>, at: Size) View<Int32> {
                xs.from(at)
            }
            pub crc: (buf: View<UInt8>) UInt32 {
                buf.size() as UInt32
            }
            pub writeU32: (p: MutView<UInt8>, v: UInt32) Void {
                p[0] = (v & 0xFF) as UInt8
            }
            pub readHeader: (buf: View<UInt8>, out: Header) Bool {
                out.size = buf.size() as UInt32
                true
            }
            pub a5: () Int32 {
                xs: Mutable<List<Int32>> = List<Int32> {}
                xs.add(1)
                xs.add(total(xs.view()))
                total(xs)
            }
            pub a6: () UInt32 {
                p: Mutable<Arr<UInt8, 32>> = Arr<UInt8, 32> {}
                writeU32(p.from(20), crc(p.slice(12, 8)))
                crc(p)
            }
            pub first: (n: Int32, v: View<Int32>) Int32 {
                n
            }
            pub header: (h: Header) Bool {
                buf: Mutable<List<UInt8>> = List<UInt8> {}
                local: Mutable<Header> = Header {}
                readHeader(buf.view(), local) && readHeader(buf.view(), h)
            }
            pub scalars: () Int32 {
                xs: Mutable<List<Int32>> = List<Int32> {}
                total(tail(xs, nextSize())) + total2(xs.view(), bump())
            }
            pub before: (xs: List<Int32>) Int32 {
                first(bump(), xs.view()) + total(xs.view())
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
            pub Log: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                pub note: () Int32 {
                    n += 1
                    n
                }
            }
            pub total: (v: View<Int32>, k: Int32) Int32 {
                k
            }
            pub size: (v: View<Int32>) Int32 {
                v.size() as Int32
            }
            pub Sensor: Mutable<Any> {
                pub buf: Mutable<List<Int32>> = List<Int32> {}
                pub log: Log = Log {}
                pub read: () Int32 {
                    trace(size(buf.view()))
                    k: Int32 = log.note()
                    t: Int32 = total(buf.view(), k)
                    trace(t)
                    log.note() + size(buf.view())
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
            pub run: (f: Fx<Tuple0, Size>) Size {
                f()
            }
            pub eachPacket: (buf: View<UInt8>, each: Fx<Tuple1<View<UInt8>>, Void>) Size {
                at: Mutable<Size> = 0
                while at < buf.size() {
                    each(buf.slice(at, 1))
                    at += 1
                }
                at
            }
            pub withView: (v: View<UInt8>) Size {
                n: Size = run(() Size {
                    v.size()
                })
                n + eachPacket(v, (p: View<UInt8>) Void {
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
            pub twice<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                f(x) + f(x)
            }
            pub relay<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                twice<T>(x, f)
            }
            pub useIt: (s: View<Char>) Size {
                relay<View<Char>>(s, (v: View<Char>) Size {
                    v.size()
                })
            }
            """,
        )
        expectAllowed(p)
    }

    @Test
    fun theOriginsAreRecordedForTheEmitter() {
        val p = snippet(
            """
            GL: Mutable<List<Int32>> = List<Int32> {}
            pub total: (v: View<Int32>) Int32 {
                v.size() as Int32
            }
            pub mk: () List<Int32> {
                List<Int32> {}
            }
            pub useIt: (xs: List<Int32>, v: View<Int32>) Int32 {
                ys: Mutable<List<Int32>> = List<Int32> {}
                total(ys.view()) + total(xs.view()) + total(GL.view()) + total(v) + total(mk().view())
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
        val slice = p.model.viewOrigins(BodyTestSupport.node<FunctionCallExpr>(p, "p.slice(12, 8)", "golden.unilidar:pilot.unilidar")).single() as ViewOrigin.Stored
        assertEquals(PlaceKind.PRIVATE, slice.kind)
        val packet = p.model.viewOrigins(BodyTestSupport.node<FunctionCallExpr>(p, "buf.from(at)", "golden.unilidar:pilot.unilidar")).single()
        assertTrue(packet is ViewOrigin.Param && packet.param.name == "buf", packet.toString())
    }

    // ---- rules.view.local ---------------------------------------------------------------------

    @Test
    fun aViewIsNeverKeptInALocal() {
        // w2-5 #0 Q1e, w2-4 S1 (arrview...) and S2 (strelem...): each is refused at the local; its later uses are not
        // reported again.
        val p = snippet(
            """
            pub Buf: Mutable<Any> {
                pub data: Mutable<List<Char>> = List<Char> {}
                pub all: () View<Char> {
                    data.view()
                }
            }
            pub Sub: Mutable<Buf> {
            }
            pub tail: (xs: View<Int32>, at: Size) View<Int32> {
                xs.from(at)
            }
            pub q1e: () View<Char> {
                b: Buf = Buf {}
                v: View<Char> = b.all()
                v
            }
            pub arrview: () Int32 {
                arr: Mutable<Arr<Int32, 3>> = @arrOf(1, 2, 3)
                w: View<Int32> = arr.view()
                arr = @arrOf(4, 5, 6)
                w[0]
            }
            pub strelem: (names: List<Str>) Size {
                s: View<Char> = names[0].view()
                s.size()
            }
            pub lent: (xs: List<Int32>) Int32 {
                u: View<Int32> = tail(xs, 1)
                m: View<Int32> = xs
                u[0] + m[0]
            }
            """,
        )
        expectView(p, "rules.view.local", "rules.view.local", "rules.view.local", "rules.view.local", "rules.view.local")
        assertTrue(messages(p, "rules.view.local").any { it.startsWith("'v' would hold b.all(), a View<Char>: a view is never kept in a local") }, messages(p, "rules.view.local").joinToString("\n"))
    }

    // ---- rules.view.generic -------------------------------------------------------------------

    @Test
    fun aGenericThatReturnsOrStoresItsTCannotTakeAView() {
        val p = snippet(
            """
            pub idG<T>: (x: T) T {
                x
            }
            pub keepG<T>: (x: T) Size {
                xs: Mutable<List<T>> = List<T> {}
                xs.add(x)
                xs.size()
            }
            pub relayG<T>: (x: T) Size {
                keepG<T>(x)
            }
            pub g1: (s: View<Char>) Size {
                idG<View<Char>>(s).size()
            }
            pub g2: (s: View<Char>) Size {
                keepG<View<Char>>(s)
            }
            pub g3: (s: View<Char>) Size {
                relayG<View<Char>>(s)
            }
            pub ok: (s: View<Char>) Int32 {
                idG<Int32>(s.size() as Int32) + (keepG<Int32>(1) as Int32)
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
            KEPT: Mutable<List<Fx<Tuple0, Size>>> = List<Fx<Tuple0, Size>> {}
            pub Keeper: Any {
                pub keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size;
            }
            pub K: Keeper {
                override pub keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    KEPT.add(() Size {
                        f(x)
                    })
                    0
                }
            }
            pub Base: Any {
                pub keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    0
                }
            }
            pub Sub: Base {
                override pub keep<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    KEPT.add(() Size {
                        f(x)
                    })
                    0
                }
            }
            pub Counter: Any {
                pub count<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size;
            }
            pub C: Counter {
                override pub count<T>: (x: T, f: Fx<Tuple1<T>, Size>) Size {
                    f(x)
                }
            }
            pub p5a: (k: Keeper, s: View<Char>) Size {
                k.keep<View<Char>>(s, (v: View<Char>) Size {
                    v.size()
                })
            }
            pub p5b: (b: Base, s: View<Char>) Size {
                b.keep<View<Char>>(s, (v: View<Char>) Size {
                    v.size()
                })
            }
            pub ok: (c: Counter, s: View<Char>) Size {
                c.count<View<Char>>(s, (v: View<Char>) Size {
                    v.size()
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
            pub Buf: Mutable<Any> {
                pub data: Mutable<List<Char>> = List<Char> {}
                pub all: () View<Char> {
                    data.view()
                }
            }
            pub Sub: Mutable<Buf> {
                override pub all: () View<Char> {
                    data.view()
                }
            }
            pub Viewer: Any {
                pub all: () View<Char>;
            }
            pub Impl: Mutable<Viewer> {
                pub data: Mutable<List<Char>> = List<Char> {}
                override pub all: () View<Char> {
                    data.view()
                }
            }
            pub Holder: Mutable<Any> {
                pub data: Mutable<List<Char>> = List<Char> {}
                pub cb: Mutable<Maybe<Fx<Tuple0, Size>>> = null
                pub all: () View<Char> {
                    data.view()
                }
            }
            pub Box<T>: Mutable<Any> {
                pub data: Mutable<List<Char>> = List<Char> {}
                pub item: Mutable<Maybe<T>> = null
                pub all: () View<Char> {
                    data.view()
                }
            }
            pub q1: () View<Char> {
                b: Buf = Buf {}
                b.data.add('a')
                b.all()
            }
            pub q1d: () View<Char> {
                b: Buf = Buf {}
                b.data.view()
            }
            pub q2: () View<Char> {
                h: Viewer = Impl {}
                h.all()
            }
            pub q3b: () View<Char> {
                h: Holder = Holder {}
                h.all()
            }
            pub q9: () View<Char> {
                i: Impl = Impl {}
                i.all()
            }
            pub q11: () View<Char> {
                b: Box<Fx<Tuple0, Int32>> = Box<Fx<Tuple0, Int32>> {}
                b.all()
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
            pub mk: () List<Int32> {
                List<Int32> {}
            }
            pub q7b: () View<Fx<Tuple0, Size>> {
                fs: Mutable<List<Fx<Tuple0, Size>>> = List<Fx<Tuple0, Size>> {}
                fs.add(() Size { 1 as Size })
                fs.view()
            }
            pub q7c<T>: (a: T) View<T> {
                xs: Mutable<List<T>> = List<T> {}
                xs.add(a)
                xs.view()
            }
            pub q7d: () View<Int32> {
                xs: Mutable<List<Int32>> = List<Int32> {}
                xs
            }
            pub q7e<T>: (a: T) View<T> {
                xs: Mutable<List<T>> = List<T> {}
                xs.add(a)
                xs
            }
            pub param: (xs: List<Int32>) View<Int32> {
                xs.from(1)
            }
            pub temp: () View<Int32> {
                mk().view()
            }
            pub concat: (a: Str, b: Str) View<Char> {
                (a + b).view()
            }
            pub inLambda: (v: View<Char>) Size {
                f: Fx<Tuple1<View<Char>>, View<Char>> = (w: View<Char>) View<Char> {
                    w.from(1)
                }
                f(v).size()
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
            pub apply: (f: Fx<Tuple0, Size>) Size {
                f()
            }
            pub useIt: (v: View<Char>) Size {
                g: Fx<Tuple1<View<Char>>, View<Char>> = (w: View<Char>) View<Char> {
                    v
                }
                g(v).size()
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
            pub mk: () List<Int32> {
                List<Int32> {}
            }
            pub total: (v: View<Int32>) Int32 {
                v.size() as Int32
            }
            pub r5: () Int32 {
                t: Mutable<Int32> = 0
                for x: Int32 in mk().view() {
                    t += x
                }
                t
            }
            pub local: () Int32 {
                xs: Mutable<List<Int32>> = List<Int32> {}
                t: Mutable<Int32> = 0
                for x: Int32 in xs.view() {
                    t += x
                }
                t
            }
            pub branches: (a: View<Int32>, xs: List<Int32>, c: Bool) Int32 {
                total(if c {
                    n: Int32 = 1
                    a
                } else {
                    xs.view()
                })
            }
            pub dropped: (a: View<Int32>) Void {
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
            pub Cb2: Mutable<Any> {
                pub f: Mutable<Maybe<Fx<Tuple0, Char>>> = null
                pub keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub GH: Mutable<Maybe<Cb2>> = null
            pub link: (h: Cb2, g: Fx<Tuple0, Char>) Void {
                h.f = g
            }
            pub inLocal: (v: View<Char>) Char {
                f: Fx<Tuple0, Char> = () Char {
                    v[0]
                }
                f()
            }
            pub returned: (v: View<Char>) Fx<Tuple0, Char> {
                () Char {
                    v[0]
                }
            }
            pub q5b: (v: View<Char>) Void {
                link(GH.unwrap(), () Char {
                    v[0]
                })
            }
            pub q6: (v: View<Char>) Void {
                GH.unwrap().keep(() Char {
                    v[0]
                })
            }
            pub listed: (v: View<Char>, fs: List<Fx<Tuple0, Char>>) Void {
                fs.add(() Char {
                    v[0]
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
            pub Cb2: Mutable<Any> {
                pub f: Mutable<Maybe<Fx<Tuple0, Char>>> = null
                pub keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub Box2: Mutable<Any> {
                pub item: Mutable<Maybe<Cb2>> = null
            }
            pub GH: Mutable<Maybe<Cb2>> = null
            pub GR: Mutable<Maybe<Ref<Maybe<Fx<Tuple0, Char>>>>> = null
            pub link: (h: Cb2, g: Fx<Tuple0, Char>) Void {
                h.f = g
            }
            pub put: (bx: Box2, x: Cb2) Void {
                bx.item = x
            }
            pub get: () Cb2 {
                GH.unwrap()
            }
            pub q4: (r: Ref<Maybe<Fx<Tuple0, Char>>>, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r.value = () Char {
                    v.get(0 as Size)
                }
            }
            pub q4c: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r: Ref<Maybe<Fx<Tuple0, Char>>> = GR.unwrap()
                r.value = () Char {
                    v.get(0 as Size)
                }
            }
            pub q4d: (r: Ref<List<Fx<Tuple0, Char>>>, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                r.value.add(() Char {
                    v.get(0 as Size)
                })
            }
            pub q5: (holder: Cb2, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                link(holder, () Char {
                    v.get(0 as Size)
                })
            }
            pub q5c: (bx: Box2, a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                c: Cb2 = Cb2 {}
                c.f = () Char {
                    v.get(0 as Size)
                }
                put(bx, c)
            }
            pub q6b: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                get().keep(() Char {
                    v.get(0 as Size)
                })
            }
            pub q5b: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                link(GH.unwrap(), () Char {
                    v.get(0 as Size)
                })
            }
            pub q6: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                GH.unwrap().keep(() Char {
                    v.get(0 as Size)
                })
            }
            """,
        )
        expectView(p, *Array(8) { "rules.view.local" })
    }

    @Test
    fun q6cAClassThatPublishesItselfChangesNothingForALambdaOverAView() {
        // w2-5 #5 Q6c: Cb2's initially stores this in a global; the view local is refused all the same, and with a
        // view parameter the lambda is refused for escaping into keep, whatever Cb2's initially does.
        val p = snippet(
            """
            pub Cb2: Mutable<Any> {
                pub f: Mutable<Maybe<Fx<Tuple0, Char>>> = null
                initially {
                    GH = this
                }
                pub keep: (g: Fx<Tuple0, Char>) Void {
                    f = g
                }
            }
            pub GH: Mutable<Maybe<Cb2>> = null
            pub q6c: (a: Str) Void {
                s: Str = a + "x"
                v: View<Char> = s.view()
                Cb2 {}.keep(() Char {
                    v.get(0 as Size)
                })
            }
            pub q6cParam: (v: View<Char>) Void {
                Cb2 {}.keep(() Char {
                    v[0]
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
            pub count: (it: Iterable<Char>) Size {
                it.size()
            }
            pub K: Struct {
                pub it: Maybe<Iterable<Char>> = null
            }
            pub asTrait: (v: View<Char>) Size {
                count(v)
            }
            pub inField: (v: View<Char>) Size {
                k: K = K { it = v }
                0
            }
            pub asAny: (v: View<UInt8>) UInt64 {
                bitCast<UInt64>(v)
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
            pub size: (v: View<Char>) Size {
                v.size()
            }
            pub texts: (v: View<Char>, out: StrBuf<32>) Str {
                out.add(v)
                out.set("n=${'$'}{size(v)}")
                trace(size(v.from(1)))
                "${'$'}{v.size()}"
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
            gl: Mutable<List<Int32>> = List<Int32> {}
            pub growL: () Int32 {
                gl.add(1)
                1
            }
            pub countOf: (v: View<Int32>, n: Int32) Int32 {
                v.size() as Int32 + n
            }
            pub f: (v: View<Int32>, xs: List<Int32>) Int32 {
                xs.add(1)
                1
            }
            pub Log: Mutable<Any> {
                pub lines: Mutable<List<Int32>> = List<Int32> {}
                pub note: () Int32 {
                    lines.add(1)
                    1
                }
            }
            pub Sensor: Mutable<Any> {
                pub buf: Mutable<List<Int32>> = List<Int32> {}
                pub log: Log = Log {}
                pub read: () Int32 {
                    countOf(buf.view(), log.note())
                }
            }
            pub B: Mutable<Any> {
                pub arr: Mutable<Arr<Int32>> = @arrOf(1, 2, 3)
            }
            pub refill: (v: View<Int32>, b: B) Int32 {
                b.arr = @arrOf(4, 5, 6)
                v[0]
            }
            pub r3: () Int32 {
                countOf(gl, growL())
            }
            pub named: () Int32 {
                xs: Mutable<List<Int32>> = List<Int32> {}
                f(xs.view(), xs)
            }
            pub self: (sb: StrBuf<16>) Void {
                sb.add(sb.view())
            }
            pub arrhanded: (b: B) Int32 {
                refill(b.arr.view(), b)
            }
            """,
        )
        // The two named writes are D37's too: ExclusivityPass refuses a mut argument or receiver that overlaps another operand.
        expectView(p, "rules.exclusivity.argument", "rules.exclusivity.receiver", *Array(5) { "rules.view.write" })
        assertTrue(messages(p, "rules.view.write").any { it.contains("'growL()' may replace, grow or free 'gl' (it is impure") }, messages(p, "rules.view.write").joinToString("\n"))
    }

    // ---- rules.view.extern and rules.view.unsafe ----------------------------------------------

    @Test
    fun anExternNeverReturnsAPointer() {
        // w2-6 #0 (a List<CStr> result: a type that holds a pointer, 1.2), #1 (slot() returning an Unsafe), #2a (an Fx
        // returning a CStr).
        val p = snippet(
            """
            pub @_extern(cpp = "ext::name", header = "ext.hxx") name: () CStr;
            pub @_extern(cpp = "ext::text", header = "ext.hxx") text: () View<Char>;
            pub @_extern(cpp = "ext::tokens", header = "ext.hxx") tokens: (line: Str) List<CStr>;
            pub @_extern(cpp = "ext::slot", header = "ext.hxx") slot: () Unsafe<Int32>;
            pub @_extern(cpp = "ext::tailFn", header = "ext.hxx") tailFn: () Fx<Tuple0, CStr>;
            pub @_opaque Buf: Any {
                pub label: () CStr;
                pub bytes: () View<UInt8>;
            }
            """,
        )
        // An @_opaque class's bodiless methods are C++'s too (w2-5 round 1 minor): refused at the declaration.
        expectView(p, *Array(6) { "rules.view.extern" }, "rules.view.type")
        assertTrue(messages(p, "rules.view.extern").any { it.startsWith("The extern 'bytes' returns a View<UInt8>") }, messages(p, "rules.view.extern").joinToString("\n"))
    }

    // ---- round 3 (40-round3 F2, F3, and round 2's minors) ------------------------------------------

    @Test
    fun anIfExpressionThatPicksAViewOfATemporaryIsRefusedWhereItStands() {
        // F3 (KI-13, r1 w2-3 minor #1): C++ cannot keep one branch's temporary without making the other's, so an
        // if-expression with a TEMP origin in a branch is rules.view.position, as a for over a temporary is. The
        // remedy, an if statement with one call per branch, is allowed; so is an if-expression over places.
        val p = snippet(
            """
            pub gl: Mutable<List<Int32>> = List<Int32> { values = @arrOf(1) }
            pub ticks: Mutable<Int32> = 0
            pub next: () Int32 {
                ticks += 1
                ticks
            }
            pub makeList: () List<Int32> {
                List<Int32> { values = @arrOf(2) }
            }
            pub minus: (v: View<Int32>, k: Int32) Int32 {
                v.size() as Int32 - k
            }
            pub total: (v: View<Int32>) Int32 {
                v.size() as Int32
            }
            pub f: (c: Bool) Int32 {
                a: Int32 = minus(c ? makeList().view() : gl.view(), next())
                b: Int32 = total(c ? makeList().view() : gl.view())
                d: Mutable<Int32> = 0
                if c {
                    d = total(makeList().view())
                } else {
                    d = total(gl.view())
                }
                xs: Mutable<List<Int32>> = List<Int32> { values = @arrOf(3) }
                e: Int32 = total(c ? xs.view() : xs.view().from(1))
                a + b + d + e
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
            pub user: (v: View<Int32>, sink: Fx<Tuple1<Fx<Tuple0, Int32>>, Void>) Void {
                sink(() Int32 { v[0] })
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
            pub H: Mutable<Any> {
                pub items: Mutable<List<List<Int32>>> = List<List<Int32>> { }
                pub m: Mutable<Maybe<List<Int32>>> = null
                pub itemsView: () View<Int32> {
                    items.get(0).view()
                }
                pub wipeThen: (v: View<Int32>) Int32 {
                    items = List<List<Int32>> { }
                    total(v)
                }
                pub me: () H {
                    this
                }
                pub reset: () Int32 {
                    items = List<List<Int32>> { }
                    0
                }
                pub viaMe: () Int32 {
                    sumK(me().items.get(0).view(), reset())
                }
                pub viaMeIdx: () Int32 {
                    sumK(me().items[0].view(), reset())
                }
                pub viaThisMe: () Int32 {
                    sumK(this.me().items.get(0).view(), reset())
                }
            }
            pub total: (v: View<Int32>) Int32 {
                s: Mutable<Int32> = 0
                for x: Int32 in v {
                    s += x
                }
                s
            }
            pub sumK: (v: View<Int32>, k: Int32) Int32 {
                total(v) + k
            }
            pub wipe: (h: H) Int32 {
                h.items = List<List<Int32>> { }
                0
            }
            pub wipeThen: (v: View<Int32>, h: H) Int32 {
                wipe(h)
                total(v)
            }
            pub pick: (h: H) H {
                h
            }
            pub pickM: (h: H) Maybe<H> {
                h
            }
            pub rows: (h: H) Int32 {
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
                a + b + c + d + e + f + g + k + m + n + q
            }
            """,
        )
        // The program reads Maybe values with no isNull() check on purpose; types.maybe.unchecked-value is that warning, not this test's subject.
        assertEquals(List(14) { "rules.view.write" }, p.diagnostics.filter { it.code != "types.maybe.unchecked-value" }.map { it.code }, TyperTestSupport.render(p))
        // O2 as LentPlaces records it: the call spelling is the index spelling's place, rooted at pick(h).items.
        val lent = p.model.readPlace(BodyTestSupport.node<Expr>(p, "pick(h).items.get(0)"))
        val indexed = p.model.readPlace(BodyTestSupport.node<Expr>(p, "pick(h).items[0]"))
        assertEquals((indexed as Place.Index).container, (lent as Place.Index).container)
        // O1: the view pick(h).itemsView() forms points into the object behind pick(h).
        val formed = BodyTestSupport.all<Expr>(p, "pick(h).itemsView()").flatMap { p.model.viewOrigins(it) }
        assertTrue(formed.any { it is ViewOrigin.Referent }, formed.toString())
    }

    @Test
    fun aViewBesideACallThatStartsAThreadWritingItsPlaceIsRefused() {
        // Round 6, scratchpad w25r5c x/t7 as the verifier wrote it: bump constructs a Thread whose body replaces gl, and
        // joins it at the drop. Round 5b ranked the construction PURE, so the view of gl beside bump() was accepted
        // (gcc and MSVC 15300; clang formed the view first and read the freed buffer: 6).
        val p = snippet(
            """
            kira.sync as sync

            gl: Mutable<List<Int32>> = @listOf(1, 2, 3)

            bump: () Int32 {
                t: Thread = Thread { name = "w", body = () Void {
                    gl = @listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700)
                } }
                0
            }

            sumView: (v: View<Int32>, n: Int32) Int32 {
                t: Mutable<Int32> = n
                for x: Int32 in v {
                    t += x
                }
                t
            }

            main: () Void {
                trace(sumView(gl.view(), bump()))
            }
            """,
        )
        expectView(p, "rules.view.write")
        assertTrue(messages(p, "rules.view.write").single().contains("gl"), messages(p, "rules.view.write").toString())
    }
}
