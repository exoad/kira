package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R19 and D33 on the emitted text: which calls and reads are copied into typed temporaries,
 * which places have the values along their path ordered and the path applied where it is
 * used, and which operands are left alone (the evalorder golden runs the same rules on gcc,
 * clang and msvc). Without EffectsPass (W2.5) a call's absent effect is impure, a stdlib
 * binding marked `pure: true` is pure, a read of a `mut` global, a field or a by-reference
 * parameter is READS, and an operand holding neither is pure. A Str, struct or container
 * read beside an effect is copied first (D33 reads it before the effect; C++'s const& would
 * hand the callee the object as the effect left it), except where the call may lend a view
 * from it, which would point into the copy: through its result, a `View` parameter, a `mut`
 * argument or receiver, or anything else the callee writes. Without EscapePass (W2.5) the
 * hoister decides that from the callee's body, conservatively (CppEscapes).
 */
class CppHoisterTest {
    private val module = Module(
        "hoist:cases",
        """
        mut ticks: Int32 = 0
        mut idx: Size = 0
        mut row: Size = 1
        mut garr: Arr<Int32, 4> = [10, 20, 30, 40]
        mut gl: List<Int32> = List<Int32> { }
        mut gm: Map<Int32, Int32> = Map<Int32, Int32> { }

        fx next: () Int32 {
            ticks += 1
            return ticks
        }

        fx nextSize: () Size {
            idx += 1
            return idx
        }

        fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }

        fx put: (mut into: Int32, a: Int32, b: Int32) Void {
            into = a - b
        }

        pub struct Box {
            pub v: Int32 = 0

            pub fx pair: (a: Int32, b: Int32) Int32 {
                return v * 100 + a * 10 + b
            }

            pub mut fx grow: (a: Int32, b: Int32) Void {
                v = v * 100 + a * 10 + b
            }
        }

        mut gboxes: Arr<Box, 2> = [Box { }, Box { }]

        fx makeBox: () Box {
            ticks += 1
            return Box { v = ticks }
        }

        fx pushed: () Size {
            gl.add(7)
            return gl.size() - 1
        }

        fx putKey: () Int32 {
            gm.put(1, 42)
            return 1
        }

        fx tail: (xs: List<Int32>, at: Size) View<Int32> {
            return xs.from(at)
        }

        fx total: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        pub fx nestedTarget: () Void {
            mut grid: Arr<Arr<Int32, 3>, 3> = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
            grid[nextSize()][nextSize()] = 5
            grid[nextSize()][nextSize()] += 1
        }

        pub fx nestedMutArgument: () Void {
            mut grid: Arr<Arr<Int32, 3>, 3> = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
            put(mut grid[row][nextSize()], 1, 2)
        }

        pub fx nestedMutReceiver: () Void {
            mut boxes: Arr<Arr<Box, 3>, 3> = [[Box { }, Box { }, Box { }], [Box { }, Box { }, Box { }], [Box { }, Box { }, Box { }]]
            boxes[nextSize()][nextSize()].grow(1, 2)
        }

        pub fx lentFromParameter: (xs: List<Int32>) Int32 {
            return total(tail(xs, nextSize()))
        }

        pub fx lentFromGlobal: () Int32 {
            return total(garr.from(nextSize()))
        }

        pub fx mapReadBeside: () Int32 {
            return gm.get(putKey()).unwrapOr(-1)
        }

        pub fx listReadBeside: () Int32 {
            return gl.get(pushed())
        }

        mut gs: Str = "a"

        fx changeS: () Int32 {
            gs = "b"
            return 1
        }

        fx resetL: () Int32 {
            gl = List<Int32> { values = [8] }
            return 0
        }

        fx pairS: (s: Str, k: Int32) Str {
            return "${'$'}{s}${'$'}{k}"
        }

        fx firstL: (xs: List<Int32>, k: Int32) Int32 {
            return xs.get(0) + k
        }

        pub fx strBeside: () Str {
            return pairS(gs, changeS())
        }

        pub fx strHole: () Str {
            return "${'$'}{gs}:${'$'}{changeS()}"
        }

        pub fx listBeside: () Int32 {
            return firstL(gl, resetL())
        }

        pub fx localStrBeside: () Str {
            s: Str = "x"
            return pairS(s, changeS())
        }

        pub fx setOnce: (mut bufs: Arr<StrBuf<8>, 3>) Void {
            bufs[nextSize()].set("x")
        }

        pub fx setPlain: (mut buf: StrBuf<8>) Void {
            buf.set("x")
        }

        pub fx piecesOnce: (mut bufs: Arr<StrBuf<8>, 3>, n: Int32) Void {
            bufs[nextSize()].set("a${'$'}{n}b")
        }

        pub fx piecesPlain: (mut buf: StrBuf<8>, n: Int32) Void {
            buf.set("a${'$'}{n}b")
        }

        pub fx scalarElementBeside: () Int32 {
            return sub(garr[row], next())
        }

        pub fx structElementReceiver: () Int32 {
            return gboxes[row].pair(next(), next())
        }

        pub fx readBeside: () Int32 {
            return sub(ticks, next())
        }

        pub fx readAfter: () Int32 {
            return sub(next(), ticks)
        }

        pub fx twoReads: () Int32 {
            return sub(ticks, ticks)
        }

        pub fx localBeside: (x: Int32) Int32 {
            return sub(x, next())
        }

        pub fx placeIndex: () Int32 {
            mut q: Arr<Int32, 4> = [0, 0, 0, 0]
            put(mut q[nextSize()], next(), next())
            return q[1]
        }

        pub fx receiverFirst: () Int32 {
            return makeBox().pair(next(), next())
        }

        pub fx localReceiver: (b: Box) Int32 {
            return b.pair(next(), next())
        }

        pub fx targetFirst: () Void {
            mut s: Arr<Int32, 4> = [0, 0, 0, 0]
            s[nextSize()] = next()
            s[0] = next()
        }

        pub fx twoImpure: () Int32 {
            return sub(next(), next())
        }

        pub fx oneImpure: (x: Int32) Int32 {
            return sub(next(), x + 1)
        }

        pub fx operands: () Int32 {
            return next() * next()
        }

        pub fx shortCircuit: () Bool {
            return next() > 0 && next() > 1
        }

        pub fx mutStays: () Int32 {
            mut slot: Int32 = 0
            put(mut slot, next(), next())
            return slot
        }

        pub fx pureBindings: (s: Str) Size {
            return s.length() + s.length()
        }

        pub fx holes: () Str {
            return "${'$'}{next()}-${'$'}{next()}"
        }

        pub fx nested: () Int32 {
            return sub(sub(next(), next()), next())
        }

        pub struct Win {
            pub v: View<Int32>
            pub k: Size = 0
        }

        pub struct Wrap {
            pub inner: Win
            pub m: Maybe<Win> = null
        }

        fx mkWin: (v: View<Int32>, k: Size) Win {
            return Win { v = v, k = k }
        }

        fx wrapOf: (v: View<Int32>, k: Size) Wrap {
            return Wrap { inner = Win { v = v, k = k } }
        }

        pub fx structResultLends: () Win {
            return mkWin(gl, nextSize())
        }

        pub fx constructionLends: () Win {
            return Win { v = gl, k = nextSize() }
        }

        pub fx nestedStructResultLends: () Wrap {
            return wrapOf(gl, nextSize())
        }

        pub struct Acc {
            pub n: Int32 = 1

            pub mut fx bump: () Int32 {
                n = 2
                return 10
            }

            pub mut fx viaThis: () Int32 {
                return peek(this, bump())
            }

            pub fx plus: (k: Int32) Int32 {
                return n * 100 + k
            }

            pub mut fx viaExplicit: () Int32 {
                return this.plus(bump())
            }

            pub mut fx viaImplicit: () Int32 {
                return plus(bump())
            }
        }

        fx peek: (a: Acc, k: Int32) Int32 {
            return a.n + k
        }

        mut gacc: Acc = Acc { }

        fx bumpAcc: () Int32 {
            gacc.n = 2
            return 10
        }

        pub fx namedBeside: () Int32 {
            return gacc.plus(bumpAcc())
        }

        pub struct Sink {
            pub v: View<Int32>
            pub k: Size = 0

            pub mut fx attach: (src: View<Int32>, k: Size) Void {
                v = src
                this.k = k
            }
        }

        mut gview: Maybe<View<Int32>> = null

        fx fillFrom: (mut w: Sink, xs: List<Int32>, k: Size) Void {
            w.v = xs.from(0)
            w.k = k
        }

        fx stashTail: (xs: List<Int32>, k: Int32) Void {
            gview = xs.from(0)
            ticks = k
        }

        fx viaStash: (xs: List<Int32>, k: Int32) Int32 {
            stashTail(xs, k)
            return k
        }

        fx adder: (s: Str, k: Int32) Fx<Tuple1<Int32>, Int32> {
            n: Int32 = s.length() as Int32
            return fx (x: Int32) Int32 {
                return x + n + k
            }
        }

        fx viaPair: (s: Str, k: Int32) Str {
            return pairS(s, k)
        }

        pub struct Bag {
            pub items: List<Int32> = List<Int32> { }

            pub fx head: (k: Size) View<Int32> {
                return items.from(k)
            }

            pub fx firstPlus: (k: Int32) Int32 {
                return items.get(0) + k
            }
        }

        mut gbag: Bag = Bag { }

        pub fx attachBeside: (mut w: Sink) Void {
            w.attach(gl, nextSize())
        }

        pub fx fillBeside: (mut w: Sink) Void {
            fillFrom(mut w, gl, nextSize())
        }

        pub fx stashBeside: () Void {
            stashTail(gl, next())
        }

        pub fx viaStashBeside: () Int32 {
            return viaStash(gl, next())
        }

        pub fx assignView: (mut sinks: Arr<Sink, 2>) Void {
            sinks[nextSize()].v = gl
        }

        pub fx adderBeside: () Int32 {
            f: Fx<Tuple1<Int32>, Int32> = adder(gs, changeS())
            return f(0)
        }

        pub fx viaPairBeside: () Str {
            return viaPair(gs, changeS())
        }

        pub fx bagLends: () Int32 {
            return total(gbag.head(nextSize()))
        }

        pub fx bagReads: () Int32 {
            return gbag.firstPlus(next())
        }

        fx eachOf: (xs: List<Int32>, each: Fx<Tuple1<Int32>, Void>) Void {
            for x: Int32 in xs {
                each(x)
            }
        }

        fx handOn: (xs: List<Int32>, each: Fx<Tuple1<Int32>, Void>) Void {
            eachOf(xs, each)
        }

        fx inc: (mut v: Int32) Int32 {
            v += 10
            return 1
        }

        fx countOf: (v: View<Int32>, k: Size) Size {
            return v.size()
        }

        pub struct Holder<T> {
            pub v: T
            pub k: Size = 0
        }

        fx keep<T>: (mut into: Holder<T>, value: T, k: Size) Void {
            into.v = value
            into.k = k
        }

        fx stashLocal: (mut w: Sink, xs: List<Int32>, k: Size) Void {
            t: View<Int32> = xs.from(0)
            w.v = t
        }

        fx firstRow: (mut w: Sink, grid: List<List<Int32>>, k: Size) Void {
            for r: List<Int32> in grid {
                w.v = r.from(0)
            }
        }

        fx countViews: (xs: List<Int32>, k: Size) Size {
            mut vs: List<View<Int32>> = List<View<Int32>> { }
            vs.add(xs.from(0))
            return vs.size()
        }

        fx closeOver: (v: View<Int32>, k: Size) Fx<Tuple1<Size>, Int32> {
            return fx (i: Size) Int32 {
                return v.get(i)
            }
        }

        fx zero: (m: MutView<Int32>) Int32 {
            m[0] = 9
            return 0
        }

        fx applyTo: (f: Fx<Tuple1<Int32>, Int32>, n: Int32) Int32 {
            return f(n)
        }

        fx twice: (n: Int32) Int32 {
            return n * 2
        }

        mut gg: List<List<Int32>> = List<List<Int32>> { }

        pub fx mutArgumentSibling: () Int32 {
            mut x: Int32 = 5
            return sub(x, inc(mut x))
        }

        pub fx mutArgumentCompound: () Int32 {
            mut z: Int32 = 5
            z += inc(mut z)
            return z
        }

        pub fx mutViewSibling: () Int32 {
            mut p: Arr<Int32, 4> = [1, 2, 3, 4]
            return sub(p[0], zero(p.from(0)))
        }

        pub fx heldMutViewSibling: () Int32 {
            mut q: Arr<Int32, 4> = [1, 2, 3, 4]
            mv: MutView<Int32> = q.from(0)
            return sub(q[0], zero(mv))
        }

        pub fx viewKeptNowhere: () Size {
            return countOf(gl, nextSize())
        }

        pub fx genericKeepsAView: (mut h: Holder<View<Int32>>) Void {
            keep<View<Int32>>(mut h, gl, nextSize())
        }

        pub fx genericKeepsAList: (mut h: Holder<List<Int32>>) Void {
            keep<List<Int32>>(mut h, gl, nextSize())
        }

        pub fx keptThroughALocal: (mut w: Sink) Void {
            stashLocal(mut w, gl, nextSize())
        }

        pub fx keptThroughALoopVariable: (mut w: Sink) Void {
            firstRow(mut w, gg, nextSize())
        }

        pub fx keptInALocalContainer: () Size {
            return countViews(gl, nextSize())
        }

        pub fx keptByAClosure: () Int32 {
            f: Fx<Tuple1<Size>, Int32> = closeOver(gl, nextSize())
            return f(0)
        }

        pub fx functionAsValue: () Int32 {
            g: Fx<Tuple2<Fx<Tuple1<Int32>, Int32>, Int32>, Int32> = applyTo
            return g(twice, 5)
        }

        mut gll: List<List<Int32>> = List<List<Int32>> { }
        mut gls: List<Int32> = List<Int32> { }
        mut gv3: List<Int32> = List<Int32> { values = [1, 2, 3] }

        fx growGll: () Int32 {
            gll.add(List<Int32> { values = [1] })
            return 7
        }

        fx growGls: () Int32 {
            gls.add(5)
            return 7
        }

        fx setG3: () Int32 {
            gv3.set(0, 9)
            return 1
        }

        fx viewPlus: (v: View<Int32>, k: Int32) Int32 {
            return total(v) + k
        }

        fx pokeAll: (ms: List<MutView<Int32>>) Int32 {
            ms.get(0).set(0, 9)
            return 1
        }

        pub fx boundReceiverBeside: () Void {
            gll[0].add(growGll())
        }

        pub fx boundArgumentBeside: () Void {
            put(mut gls[0], growGls(), 1)
        }

        pub fx boundArrElement: () Void {
            put(mut garr[0], next(), 1)
        }

        pub fx boundLocalList: () Void {
            mut ll: List<Int32> = List<Int32> { values = [1] }
            put(mut ll[0], next(), 1)
        }

        pub fx viewReadBeside: () Int32 {
            v: View<Int32> = gv3.view()
            return sub(v.get(0), setG3())
        }

        pub fx closureWritesThroughAMutView: () Int32 {
            mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
            mv: MutView<Int32> = xs.from(0)
            pk: Fx<Tuple0, Int32> = fx () Int32 {
                mv.set(0, 9)
                return 1
            }
            return sub(xs[0], pk())
        }

        pub fx listOfMutViewsWrites: () Int32 {
            mut xs: List<Int32> = List<Int32> { values = [1, 2, 3] }
            mut ms: List<MutView<Int32>> = List<MutView<Int32>> { }
            ms.add(xs.from(0))
            return sub(xs[0], pokeAll(ms))
        }

        pub fx arrViewBeside: () Int32 {
            return viewPlus(garr.view(), growGls())
        }

        pub fx literalView: () Size {
            v: View<Char> = "abc".view()
            return v.size()
        }
        """,
    )

    private val text by lazy { CppExprTestSupport.emit("hoister", listOf(module)).source(module) }

    /** The body of the exported function [fn]'s definition (module namespace, two-space indent). */
    private fun body(fn: String): String {
        val head = Regex("\\n  [^\\n ][^\\n]* $fn\\([^\\n]*\\)\\n  \\{\\n").find(text) ?: error("no definition of $fn in:\n$text")
        val close = text.indexOf("\n  }\n", head.range.last)
        return text.substring(head.range.last, close)
    }

    @Test
    fun twoImpureArgumentsAreSpilledInSourceOrder() {
        val b = body("twoImpure")
        assertTrue(b.contains("return [&]() -> std::int32_t\n      {\n          const std::int32_t t0_ = next();\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);\n      }();"), b)
    }

    @Test
    fun oneImpureArgumentIsNot() {
        assertTrue(body("oneImpure").contains("return sub(next(), x + 1);"), body("oneImpure"))
    }

    @Test
    fun anOperatorsOperandsAreSpilledToo() {
        assertTrue(body("operands").contains("return t0_ * t1_;"), body("operands"))
    }

    @Test
    fun theRightSideOfAndIsNeverSpilled() {
        val b = body("shortCircuit")
        assertTrue(b.contains("return next() > 0 && next() > 1;"), b)
        assertFalse(b.contains("[&]"), b)
    }

    @Test
    fun aMutArgumentIsAPlaceAndStaysOne() {
        val b = body("mutStays")
        assertTrue(b.contains("put(slot, t0_, t1_);"), b)
        assertFalse(b.contains("= slot;"), "a mut argument is never copied into a temporary:\n$b")
    }

    @Test
    fun pureBindingsNeedNoSpill() {
        assertTrue(body("pureBindings").contains("return kira::str::length(s) + kira::str::length(s);"), body("pureBindings"))
    }

    @Test
    fun interpolationHolesAreArgumentsOfCat() {
        assertTrue(body("holes").contains("return kira::cat(t0_, \"-\", t1_);"), body("holes"))
    }

    @Test
    fun aReadOfSharedStateBesideAnEffectIsCopiedFirst() {
        val b = body("readBeside")
        assertTrue(b.contains("const std::int32_t t0_ = ticks;\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);"), b)
        val after = body("readAfter")
        assertTrue(after.contains("const std::int32_t t0_ = next();\n          const std::int32_t t1_ = ticks;\n          return sub(t0_, t1_);"), after)
    }

    @Test
    fun twoReadsOrALocalBesideAnEffectNeedNothing() {
        assertTrue(body("twoReads").contains("return sub(ticks, ticks);"), body("twoReads"))
        assertTrue(body("localBeside").contains("return sub(x, next());"), body("localBeside"))
    }

    @Test
    fun aMutArgumentsImpureIndexRunsFirstAndTheElementIsWrittenAtTheCall() {
        val b = body("placeIndex")
        assertTrue(b.contains("const kira::Size t0_ = nextSize();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          put(kira::at(q, t0_), t1_, t2_);"), b)
        assertFalse(b.contains("& r"), "no reference is held across the siblings' effects:\n$b")
    }

    @Test
    fun anImpureReceiverIsCopiedBeforeTheArgumentsAndSoIsAStructParameter() {
        val b = body("receiverFirst")
        assertTrue(b.contains("const Box t0_ = makeBox();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);"), b)
        // A struct parameter is a const& to the caller's object, which next() may write: D33
        // reads the receiver first, so it is copied before the arguments run.
        val local = body("localReceiver")
        assertTrue(local.contains("const Box t0_ = b;\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);"), local)
    }

    @Test
    fun aStrStructOrListGlobalBesideACallThatReassignsItIsCopiedFirst() {
        // D33: `show(gs, changeS())` passes the old gs; C++'s const& would pass the new one.
        val s = body("strBeside")
        assertTrue(s.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return pairS(t0_, t1_);"), s)
        val hole = body("strHole")
        assertTrue(hole.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return kira::cat(t0_, \":\", t1_);"), hole)
        val list = body("listBeside")
        assertTrue(list.contains("const kira::List<std::int32_t> t0_ = gl;\n          const std::int32_t t1_ = resetL();\n          return firstL(t0_, t1_);"), list)
        // A local is nothing a sibling can change: read where it is (a parameter is a const&
        // to the caller's object, so it is copied like a global).
        val local = body("localStrBeside")
        assertTrue(local.contains("return pairS(s, changeS());"), local)
    }

    @Test
    fun aPlaceAssignmentLocatesItsTargetBeforeAnImpureValue() {
        val b = body("targetFirst")
        assertTrue(b.contains("const kira::Size t0_ = nextSize();\n          const std::int32_t t1_ = next();\n          kira::at(s, t0_) = t1_;"), b)
        assertTrue(b.contains("kira::at(s, 0) = next();"), b)
    }

    @Test
    fun aNestedElementPlaceOrdersEveryIndexAndAppliesThePathAtTheEnd() {
        val b = body("nestedTarget")
        assertTrue(b.contains("const kira::Size t0_ = nextSize();\n          const kira::Size t1_ = nextSize();\n          kira::at(kira::at(grid, t0_), t1_) = 5;"), b)
        assertTrue(b.contains("const kira::Size t0_ = nextSize();\n          const kira::Size t1_ = nextSize();\n          kira::at(kira::at(grid, t0_), t1_) += 1;"), b)
        // A mut argument two elements deep: the row (shared state) is read first, then the column's call.
        val arg = body("nestedMutArgument")
        assertTrue(arg.contains("const kira::Size t0_ = row;\n          const kira::Size t1_ = nextSize();\n          put(kira::at(kira::at(grid, t0_), t1_), 1, 2);"), arg)
        // The receiver of a mut fx two elements deep is the element itself, never a temporary the write would land in.
        val recv = body("nestedMutReceiver")
        assertTrue(recv.contains("const kira::Size t0_ = nextSize();\n          const kira::Size t1_ = nextSize();\n          kira::at(kira::at(boxes, t0_), t1_).grow(1, 2);"), recv)
        assertFalse(recv.contains("-> Box"), "the element is not returned by value:\n$recv")
    }

    @Test
    fun aContainerACallLendsAViewFromIsNeverCopied() {
        // A List parameter is a place C++ holds by const&: it is never copied, and the view
        // tail() returns points at the caller's list, not at a temporary of the lambda. It is
        // read after the sibling's effect, so the view is made of it as the effect left it.
        val param = body("lentFromParameter")
        assertTrue(param.contains("const kira::Size t0_ = nextSize();\n          return tail(xs, t0_);"), param)
        assertFalse(param.contains("= xs;"), param)
        // A mut global Arr lends a MutView (the typer's MutView lending), of the global itself,
        // made after the index: `kira::mutView(garr)` in the call's object expression would run
        // before its argument.
        val global = body("lentFromGlobal")
        assertTrue(global.contains("const kira::Size t0_ = nextSize();\n          return kira::mutView(garr).from(t0_);"), global)
    }

    @Test
    fun aStructResultThatHoldsAViewLendsTooSoItsOperandsAreNeverCopied() {
        // Win holds a View: a copy of gl in the lambda would be what w.v points into after
        // the lambda's end (gcc printed garbage, MSVC's ASan a heap-use-after-free), so the
        // call, the construction and a result nesting Win read gl where it lives, after the
        // sibling (mkWin's View parameter was built from gl unsequenced against nextSize()).
        val call = body("structResultLends")
        assertTrue(call.contains("const kira::Size t0_ = nextSize();\n          return mkWin(gl, t0_);"), call)
        val construction = body("constructionLends")
        assertTrue(construction.contains("const kira::Size t0_ = nextSize();\n          return Win{.v = gl, .k = t0_};"), construction)
        val nested = body("nestedStructResultLends")
        assertTrue(nested.contains("const kira::Size t0_ = nextSize();\n          return wrapOf(gl, t0_);"), nested)
        listOf(call, construction, nested).forEach { assertFalse(it.contains("= gl;"), it) }
    }

    @Test
    fun aStructsThisReadAsAValueBesideAMutFxOfItsOwnIsCopiedFirst() {
        // `this` in a struct is the receiver C++ holds by reference, shared state bump() writes:
        // D33 reads it before the sibling's effect, as it reads a field (viaField in r6).
        assertTrue(text.contains("const Acc t0_ = *this;\n          const std::int32_t t1_ = bump();\n          return peek(t0_, t1_);"), text)
    }

    @Test
    fun thisAsAReceiverReadsLikeANamedStructReceiver() {
        // A named struct receiver beside a mut fx that writes it is copied first (D33), and so
        // is `this`, explicit or implicit: `this.plus(bump())` and `plus(bump())` read the same
        // as `gacc.plus(bumpAcc())`, never the state after the effect.
        val named = body("namedBeside")
        assertTrue(named.contains("const Acc t0_ = gacc;\n          const std::int32_t t1_ = bumpAcc();\n          return t0_.plus(t1_);"), named)
        val copiedThis = "const Acc t0_ = *this;\n          const std::int32_t t1_ = bump();\n          return t0_.plus(t1_);"
        assertEquals(2, text.windowed(copiedThis.length).count { it == copiedThis }, "the explicit and the implicit this:\n$text")
    }

    @Test
    fun anOperandTheCalleeCanLendFromIsNeverCopiedWhateverTheResult() {
        // attach stores its View parameter in the receiver, fillFrom a view of its List
        // parameter in a mut argument, stashTail one in a global: a copy of gl would be what
        // the view points into after the lambda's end (gcc printed garbage, MSVC's ASan a
        // heap-use-after-free), so gl stays where it lives, though the results are Void, and
        // is read after the sibling: attach's View was built from gl unsequenced against
        // nextSize() (gcc and clang disagreed where nextSize() grew gl).
        val attach = body("attachBeside")
        assertTrue(attach.contains("const kira::Size t0_ = nextSize();\n          w.attach(gl, t0_);"), attach)
        val fill = body("fillBeside")
        assertTrue(fill.contains("const kira::Size t0_ = nextSize();\n          fillFrom(w, gl, t0_);"), fill)
        val stash = body("stashBeside")
        assertTrue(stash.contains("const std::int32_t t0_ = next();\n          stashTail(gl, t0_);"), stash)
        // Through a callee that hands the parameter on to one that lends: the same.
        val via = body("viaStashBeside")
        assertTrue(via.contains("const std::int32_t t0_ = next();\n          return viaStash(gl, t0_);"), via)
        // An assignment whose target holds a view points into its value, made after the
        // target is located (Kira's order; C++ runs the right side of `=` first).
        val assign = body("assignView")
        assertTrue(assign.contains("const kira::Size t0_ = nextSize();\n          kira::at(sinks, t0_).v = gl;"), assign)
        listOf(attach, fill, stash, via, assign).forEach { assertFalse(it.contains("const kira::List<std::int32_t> t"), it) }
    }

    @Test
    fun anOperandTheCalleeCannotLendFromIsCopiedWhateverTheResult() {
        // adder returns an Fx (its captures are its own: n and k, not s) and makes no view:
        // D33 reads gs before changeS() writes it (f(0) is 4 for "aaa", not 2 for "b").
        val adder = body("adderBeside")
        assertTrue(adder.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return adder(t0_, t1_);"), adder)
        // viaPair only hands s to pairS, which only interpolates it: still a copy.
        val via = body("viaPairBeside")
        assertTrue(via.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return viaPair(t0_, t1_);"), via)
    }

    @Test
    fun aStructReceiverIsCopiedUnlessTheMethodCanLendFromIt() {
        // Bag holds a List: head lends a view of it (never copied, read after the sibling),
        // firstPlus only reads an element (copied first).
        val lends = body("bagLends")
        assertTrue(lends.contains("const kira::Size t0_ = nextSize();\n          return gbag.head(t0_);"), lends)
        assertFalse(lends.contains("const Bag"), lends)
        val reads = body("bagReads")
        assertTrue(reads.contains("const Bag t0_ = gbag;\n          const std::int32_t t1_ = next();\n          return t0_.firstPlus(t1_);"), reads)
    }

    @Test
    fun anFxParameterOnlyCalledIsATemplateParameterAndOnePassedOnIsNot() {
        // Without EscapePass (W2.5), an Fx parameter the body only calls is known not to
        // escape (design 5.6: a template parameter); one handed to another function is not
        // seen through, so it is escaping (a kira::Fn).
        // Both are module-private, so they are declared in the source's own namespace.
        assertTrue(text.contains("template<typename F_each>\n      requires kira::Callable<F_each, void, std::int32_t>\n    void eachOf(const kira::List<std::int32_t>& xs, F_each&& each);"), text)
        assertTrue(text.contains("void handOn(const kira::List<std::int32_t>& xs, const kira::Fn<void(std::int32_t)>& each);"), text)
    }

    @Test
    fun aMemberStyleAndAFreeFunctionBindingReadTheirReceiverTheSameWay() {
        // A container receiver beside an effect is read before it (D33): copied, whichever
        // binding spells the call, unless the call lends a view from it.
        val map = body("mapReadBeside")
        assertTrue(map.contains("const kira::Map<std::int32_t, std::int32_t> t0_ = gm;\n          const std::int32_t t1_ = putKey();\n          return t0_.get(t1_);"), map)
        val list = body("listReadBeside")
        assertTrue(list.contains("const kira::List<std::int32_t> t0_ = gl;\n          const kira::Size t1_ = pushed();\n          return kira::at(t0_, t1_);"), list)
    }

    @Test
    fun aScalarElementAndAStructElementReceiverBesideAnEffectAreCopiedAfterTheirPath() {
        val scalar = body("scalarElementBeside")
        assertTrue(scalar.contains("const std::int32_t t0_ = kira::at(garr, row);\n          const std::int32_t t1_ = next();\n          return sub(t0_, t1_);"), scalar)
        // The element's path is ordered first (row is shared state), then the element is copied.
        val element = body("structElementReceiver")
        assertTrue(element.contains("const kira::Size t0_ = row;\n          const Box t1_ = kira::at(gboxes, t0_);\n          const std::int32_t t2_ = next();\n          const std::int32_t t3_ = next();\n          return t1_.pair(t2_, t3_);"), element)
    }

    @Test
    fun aBindingThatRepeatsItsReceiverLocatesItOnce() {
        // StrBuf.set is `({self}.clear(), {self}.add({0}))`: an impure index in the receiver runs once.
        val set = body("setOnce")
        assertTrue(set.contains("const kira::Size t0_ = nextSize();\n          (kira::at(bufs, t0_).clear(), kira::at(bufs, t0_).add(kira::lit(\"x\")));"), set)
        assertFalse(set.contains("kira::at(bufs, nextSize())"), set)
        // A receiver that is a plain place needs nothing.
        val plain = body("setPlain")
        assertTrue(plain.contains("(buf.clear(), buf.add(kira::lit(\"x\")));"), plain)
        assertFalse(plain.contains("[&]"), plain)
    }

    @Test
    fun aStrBufInterpolationLocatesItsReceiverOnceBeforeThePieces() {
        val pieces = body("piecesOnce")
        assertTrue(
            pieces.contains("const kira::Size t0_ = nextSize();\n          kira::at(bufs, t0_).clear(), kira::at(bufs, t0_).add(kira::lit(\"a\")), kira::at(bufs, t0_).addInt(n), kira::at(bufs, t0_).add(kira::lit(\"b\"));"),
            pieces,
        )
        assertFalse(pieces.contains("kira::at(bufs, nextSize())"), pieces)
        // A plain receiver: one statement per piece (design 10).
        val plain = body("piecesPlain")
        assertTrue(plain.contains("buf.clear();\n      buf.add(kira::lit(\"a\"));\n      buf.addInt(n);\n      buf.add(kira::lit(\"b\"));"), plain)
    }

    @Test
    fun aNestedSpillNeverReusesAnEnclosingTemporarysName() {
        val b = body("nested")
        // The inner IIFE sits in t0_'s initializer, where t0_ is already declared (-Wshadow).
        assertTrue(b.contains("const std::int32_t t0_ = [&]() -> std::int32_t"), b)
        assertTrue(b.contains("const std::int32_t t1_ = next();\n              const std::int32_t t2_ = next();\n              return sub(t1_, t2_);"), b)
        assertTrue(b.contains("return sub(t0_, t1_);"), b)
    }

    @Test
    fun aLocalASiblingWritesIsReadBeforeTheWrite() {
        // inc(mut x) writes x while it runs: D33 reads x first (C++ left `sub(x, inc(x))`
        // unsequenced; gcc printed 14 and clang 4).
        val arg = body("mutArgumentSibling")
        assertTrue(arg.contains("const std::int32_t t0_ = x;\n          const std::int32_t t1_ = inc(x);\n          return sub(t0_, t1_);"), arg)
        // A compound assignment reads its target before a value that writes it: z is 5 + 1.
        val compound = body("mutArgumentCompound")
        assertTrue(compound.contains("const std::int32_t t0_ = z;\n          const std::int32_t t1_ = inc(z);\n          z = t0_ + t1_;"), compound)
        // A MutView handed to a callee writes what it is lent from.
        val view = body("mutViewSibling")
        assertTrue(view.contains("const std::int32_t t0_ = kira::at(p, 0);\n          const std::int32_t t1_ = zero(kira::mutView(p).from(0));\n          return sub(t0_, t1_);"), view)
        // A MutView held in a variable writes what it was lent from, untraced: every buffer
        // the siblings read counts as written.
        val held = body("heldMutViewSibling")
        assertTrue(held.contains("const std::int32_t t0_ = kira::at(q, 0);\n          const std::int32_t t1_ = zero(mv);\n          return sub(t0_, t1_);"), held)
        // A local no sibling writes stays where it is.
        assertTrue(body("localBeside").contains("return sub(x, next());"), body("localBeside"))
    }

    @Test
    fun aListConvertedToAViewTheCalleeKeepsNowhereIsCopiedFirst() {
        // countOf only reads v.size(): the view points into the copy for the length of the call (D33).
        val b = body("viewKeptNowhere")
        assertTrue(b.contains("const kira::List<std::int32_t> t0_ = gl;\n          const kira::Size t1_ = nextSize();\n          return countOf(t0_, t1_);"), b)
        // So does a List whose views stay in the callee's own local container.
        val local = body("keptInALocalContainer")
        assertTrue(local.contains("const kira::List<std::int32_t> t0_ = gl;\n          const kira::Size t1_ = nextSize();\n          return countViews(t0_, t1_);"), local)
    }

    @Test
    fun aGenericParameterInstantiatedWithAViewIsTheViewItKeeps() {
        // keep<T> stores its T: instantiated with View<Int32>, that is a view of gl, which a
        // copy would leave dangling; instantiated with List<Int32>, it is a copy of its own.
        val view = body("genericKeepsAView")
        assertTrue(view.contains("const kira::Size t0_ = nextSize();\n          keep<kira::View<std::int32_t>>(h, gl, t0_);"), view)
        assertFalse(view.contains("= gl;"), view)
        val list = body("genericKeepsAList")
        assertTrue(list.contains("const kira::List<std::int32_t> t0_ = gl;\n          const kira::Size t1_ = nextSize();\n          keep<kira::List<std::int32_t>>(h, t0_, t1_);"), list)
    }

    @Test
    fun aViewKeptThroughALocalALoopVariableOrAClosureIsNeverCopied() {
        val local = body("keptThroughALocal")
        assertTrue(local.contains("const kira::Size t0_ = nextSize();\n          stashLocal(w, gl, t0_);"), local)
        val loop = body("keptThroughALoopVariable")
        assertTrue(loop.contains("const kira::Size t0_ = nextSize();\n          firstRow(w, gg, t0_);"), loop)
        val closure = body("keptByAClosure")
        assertTrue(closure.contains("const kira::Size t0_ = nextSize();\n          return closeOver(gl, t0_);"), closure)
        listOf(local, loop, closure).forEach { assertFalse(it.contains(" = gl;") || it.contains(" = gg;"), it) }
    }

    @Test
    fun aFunctionUsedAsAValueTakesItsFxParameterAsAKiraFn() {
        // A template is no value a kira::Fn holds: applyTo, only calling f, would otherwise be one.
        assertTrue(text.contains("std::int32_t applyTo(const kira::Fn<std::int32_t(std::int32_t)>& f, std::int32_t n);"), text)
        assertTrue(body("functionAsValue").contains("= applyTo;"), body("functionAsValue"))
    }

    @Test
    fun aBoundPlaceInAListIsLocatedAfterASiblingThatMayMoveTheList() {
        // C++17 sequences a call's object expression before its arguments, and leaves a mut
        // argument's binding unsequenced against the others: `kira::at(gll, 0)` bound first
        // pointed into the storage growGll() freed (gcc, clang and MSVC pushed into it; MSVC's
        // ASan reported a heap-use-after-free). The sibling runs first, and the path after it.
        val receiver = body("boundReceiverBeside")
        assertTrue(receiver.contains("const std::int32_t t0_ = growGll();\n          kira::at(gll, 0).push_back(t0_);"), receiver)
        val argument = body("boundArgumentBeside")
        assertTrue(argument.contains("const std::int32_t t0_ = growGls();\n          put(kira::at(gls, 0), t0_, 1);"), argument)
        // An Arr's elements never move, and a local List no sibling reaches is not moved: nothing to order.
        assertTrue(body("boundArrElement").contains("put(kira::at(garr, 0), next(), 1);"), body("boundArrElement"))
        assertTrue(body("boundLocalList").contains("put(kira::at(ll, 0), next(), 1);"), body("boundLocalList"))
    }

    @Test
    fun aPureReadThroughAViewIsReadBeforeASiblingsWrite() {
        // v is a local, but the storage behind it is gv3, which setG3() writes: v.get(0) is a
        // read of shared state, like v[0] (gcc and MSVC printed 8, clang 0; Kira gives 0).
        val b = body("viewReadBeside")
        assertTrue(b.contains("const std::int32_t t0_ = v[0];\n          const std::int32_t t1_ = setG3();\n          return sub(t0_, t1_);"), b)
    }

    @Test
    fun aLocalAMutViewWasLentFromIsReadBeforeAnySiblingsCall() {
        // A MutView of xs captured by a closure, or held in a List, writes xs from wherever it
        // went: xs[0] is read first (gcc and MSVC printed 8, clang 0; Kira gives 0).
        val closure = body("closureWritesThroughAMutView")
        assertTrue(closure.contains("const std::int32_t t0_ = kira::at(xs, 0);\n          const std::int32_t t1_ = pk();\n          return sub(t0_, t1_);"), closure)
        val list = body("listOfMutViewsWrites")
        assertTrue(list.contains("const std::int32_t t0_ = kira::at(xs, 0);\n          const std::int32_t t1_ = pokeAll(ms);\n          return sub(t0_, t1_);"), list)
    }

    @Test
    fun aViewOfAStrLiteralIsAViewOfStaticStorage() {
        // kira::str::view("abc") viewed a temporary kira::Str, which died with the statement;
        // kira::lit is the literal's own storage, as the implicit conversion spells it.
        val b = body("literalView")
        assertTrue(b.contains("const kira::View<char> v = kira::lit(\"abc\");"), b)
    }

    @Test
    fun aViewOfAnArrMayBeMadeBeforeASiblingsEffect() {
        // An Arr keeps its storage where the variable is: D33's copy is safe.
        val b = body("arrViewBeside")
        assertTrue(b.contains("const kira::MutView<std::int32_t> t0_ = kira::mutView(garr);\n          const std::int32_t t1_ = growGls();\n          return viewPlus(t0_, t1_);"), b)
    }

    private val refusing = """
        mut gl: List<Int32> = List<Int32> { values = [1, 2, 3] }
        mut idx: Size = 0
        mut sbs: List<StrBuf<8>> = List<StrBuf<8>> { }

        fx nextSize: () Size {
            idx += 1
            return idx
        }

        fx growL: () Int32 {
            gl.add(4)
            return 7
        }

        fx growSbs: () Int32 {
            sbs.add(StrBuf<8> { })
            return 1
        }

        fx makeList: () List<Int32> {
            return List<Int32> { values = [10, 20, 30] }
        }

        fx tail: (xs: List<Int32>, at: Size) View<Int32> {
            return xs.from(at)
        }

        fx pick: (v: View<Int32>, k: Size) View<Int32> {
            return v
        }

        fx total: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        fx viewPlus: (v: View<Int32>, k: Int32) Int32 {
            return total(v) + k
        }

        pub fx lentFreshArgument: () Int32 {
            return total(tail(makeList(), nextSize()))
        }

        pub fx lentFreshReceiver: () Int32 {
            return total(makeList().from(nextSize()))
        }

        pub fx temporaryViewCopied: () Int32 {
            return total(pick(tail(makeList(), 1), nextSize()))
        }

        pub fx movableViewBeforeAGrowth: () Int32 {
            return viewPlus(gl.view(), growL())
        }

        pub fx localKeepsATemporaryView: () Int32 {
            v: View<Int32> = makeList().view()
            return total(v)
        }

        pub fx returnsATemporaryView: () View<Int32> {
            return tail(makeList(), 2)
        }

        pub fx loopsOverATemporaryView: () Int32 {
            mut s: Int32 = 0
            for x: Int32 in tail(makeList(), 0) {
                s += x
            }
            return s
        }

        pub fx assignsATemporaryView: () Size {
            mut v: View<Int32> = gl.view()
            v = makeList().from(1)
            return v.size()
        }

        pub fx containerKeepsATemporaryView: () Size {
            mut vs: List<View<Int32>> = List<View<Int32>> { }
            vs.add(tail(makeList(), 3))
            return vs.size()
        }

        pub fx branchReturnsItsLocalsView: (c: Bool) Size {
            v: View<Int32> = if c {
                l: List<Int32> = makeList()
                l.view()
            } else {
                gl.view()
            }
            return v.size()
        }

        pub fx strBufElementBesideAGrowth: () Void {
            sbs[0].set("a${'$'}{growSbs()}")
        }

        pub fx safeInOneStatement: () Int32 {
            return total(tail(makeList(), 1))
        }
    """.trimIndent()

    @Test
    fun aViewTheLoweringWouldLeaveDanglingIsRefusedWithWhatToWriteInstead() {
        // The convergence policy: never a use after free. Each of these compiled before and
        // read freed memory on some compiler (gcc printed 1651771218 for lentFreshArgument
        // where clang gave the sum; MSVC's ASan reported a heap-use-after-free).
        val emitted = net.exoad.kira.cpp.decls.DeclTestSupport.emit(net.exoad.kira.cpp.decls.DeclTestSupport.module("vl:bad", refusing))
        val lines = "module \"vl:bad\"\n\n$refusing\n".lines()
        fun line(snippet: String): Int = lines.indexOfFirst { it.trim().startsWith(snippet) }.also { assertTrue(it >= 0, snippet) } + 1
        val found = emitted.diagnostics("vl:bad")
            .filter { it.code == net.exoad.kira.compiler.backend.codegen.cpp.CppHoister.VIEW_LIFETIME_CODE }
            .map { (it.position?.lineNumber ?: -1) to it.message }
        val expected = listOf(
            line("return total(tail(makeList(), nextSize()))") to "a view the call may keep into the result of 'makeList', a temporary",
            line("return total(makeList().from(nextSize()))") to "a view the call may keep into the result of 'makeList', a temporary",
            line("return total(pick(tail(makeList(), 1), nextSize()))") to "this value holds a view into the result of 'makeList', a temporary, which would not outlive the lambda",
            line("return viewPlus(gl.view(), growL())") to "this view into 'gl' would be made before the call behind the result of 'growL' runs (D33)",
            line("v: View<Int32> = makeList().view()") to "but the local 'v' outlives it: store the result of 'makeList' in a local first",
            line("return tail(makeList(), 2)") to "but the returned value outlives it",
            line("for x: Int32 in tail(makeList(), 0)") to "but the loop outlives it",
            line("v = makeList().from(1)") to "but the assignment outlives it",
            line("vs.add(tail(makeList(), 3))") to "'add' may keep a view into the result of 'makeList', a temporary, past the call",
            line("l.view()") to "this value may hold a view into 'l', a local of the branch",
            line("sbs[0].set(") to "this hole's effect may move the container the StrBuf is an element of",
        )
        assertEquals(expected.size, found.size, "one refusal per unsafe shape, and none for safeInOneStatement:\n" + found.joinToString("\n"))
        expected.forEach { (at, text) ->
            assertTrue(found.any { (l, m) -> l == at && m.contains(text) }, "line $at: '$text' in:\n" + found.joinToString("\n"))
        }
    }
}
