package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import org.junit.jupiter.api.Test
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
 * hand the callee the object as the effect left it), except where the call lends a view from
 * it, which would point into the copy.
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
        }

        fx peek: (a: Acc, k: Int32) Int32 {
            return a.n + k
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
        // A List parameter is a place C++ holds by const&: it stays where it is, and the view
        // tail() returns points at the caller's list, not at a temporary of the lambda.
        val param = body("lentFromParameter")
        assertTrue(param.contains("return total(tail(xs, nextSize()));"), param)
        assertFalse(param.contains("[&]"), param)
        // A mut global Arr lends a MutView (the typer's MutView lending), of the global itself.
        val global = body("lentFromGlobal")
        assertTrue(global.contains("return total(kira::mutView(garr).from(nextSize()));"), global)
    }

    @Test
    fun aStructResultThatHoldsAViewLendsTooSoItsOperandsStayWhereTheyLive() {
        // Win holds a View: a copy of gl in the lambda would be what w.v points into after
        // the lambda's end (gcc printed garbage, MSVC's ASan a heap-use-after-free), so the
        // call, the construction and a result nesting Win read gl in place.
        val call = body("structResultLends")
        assertTrue(call.contains("return mkWin(gl, nextSize());"), call)
        assertFalse(call.contains("[&]"), call)
        val construction = body("constructionLends")
        assertTrue(construction.contains("return Win{.v = gl, .k = nextSize()};"), construction)
        assertFalse(construction.contains("[&]"), construction)
        val nested = body("nestedStructResultLends")
        assertTrue(nested.contains("return wrapOf(gl, nextSize());"), nested)
        assertFalse(nested.contains("[&]"), nested)
    }

    @Test
    fun aStructsThisReadAsAValueBesideAMutFxOfItsOwnIsCopiedFirst() {
        // `this` in a struct is the receiver C++ holds by reference, shared state bump() writes:
        // D33 reads it before the sibling's effect, as it reads a field (viaField in r6).
        assertTrue(text.contains("const Acc t0_ = *this;\n          const std::int32_t t1_ = bump();\n          return peek(t0_, t1_);"), text)
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
}
