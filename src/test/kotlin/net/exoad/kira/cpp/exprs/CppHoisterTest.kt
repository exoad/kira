package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.TypedCppModuleEmitter
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
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
 * hand the callee the object as the effect left it), except a place a view is formed of,
 * which is never copied (design 30, E1). Views are second-class (decision 4b): every program
 * here keeps a view only as an argument, a receiver or a return, and a view of a temporary is
 * formed inside the lambda that holds its owner (E2, E3).
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

        fx tail: (xs: View<Int32>, at: Size) View<Int32> {
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

        fx tailAt: (at: Size, xs: View<Int32>) View<Int32> {
            return xs.from(at)
        }

        pub fx lentFromParameter: (xs: List<Int32>) Int32 {
            return total(tailAt(nextSize(), xs))
        }

        pub fx lentFromGlobal: () Int32 {
            return total(garr.from(nextSize()))
        }

        pub fx listIndexBeside: () Int32 {
            return gl[pushed()]
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

        fx countAfter: (k: Size, v: View<Int32>) Size {
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

        pub fx viewKeptNowhere: () Size {
            return countAfter(nextSize(), gl)
        }

        pub fx genericKeepsAList: (mut h: Holder<List<Int32>>) Void {
            keep<List<Int32>>(mut h, gl, nextSize())
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

        fx plusView: (k: Int32, v: View<Int32>) Int32 {
            return total(v) + k
        }

        fx sizeOfChars: (v: View<Char>) Size {
            return v.size()
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
            return sub(gv3.view().get(0), setG3())
        }

        pub fx arrViewBeside: () Int32 {
            return plusView(growGls(), garr.view())
        }

        pub fx literalView: () Size {
            return sizeOfChars("abc".view())
        }
        """,
    )

    private val text by lazy { CppExprTestSupport.emit("hoister", listOf(module)).source(module) }

    /** The body of the exported function [fn]'s definition (module namespace, two-space indent). */
    private fun body(fn: String): String = bodyIn(text, fn)

    private fun bodyIn(source: String, fn: String): String {
        val head = Regex("\\n  [^\\n ][^\\n]* $fn\\([^\\n]*\\)\\n  \\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n  }\n", head.range.last)
        return source.substring(head.range.last, close)
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
    fun anImpureReceiverIsCopiedBeforeTheArgumentsAndAStructParameterIsNot() {
        val b = body("receiverFirst")
        assertTrue(b.contains("const Box t0_ = makeBox();\n          const std::int32_t t1_ = next();\n          const std::int32_t t2_ = next();\n          return t0_.pair(t1_, t2_);"), b)
        // A struct parameter is PRIVATE (50-round4 R-PURE, invariant I): every caller bound it to
        // storage nothing writes until this call returns (a temporary, a PRIVATE place of its
        // own, or a copy), so next() cannot change it and it is read in place.
        val local = body("localReceiver")
        assertTrue(local.contains("const std::int32_t t0_ = next();\n          const std::int32_t t1_ = next();\n          return b.pair(t0_, t1_);"), local)
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
    fun aPlaceAViewIsFormedOfIsNeverCopiedAndTheViewIsMadeWhereTheCallUsesIt() {
        // E1: a List parameter converted to a View is a place C++ holds by const&: it is never
        // copied, and the view tailAt() returns points at the caller's list. tailAt's call has
        // an effect of its own, so it is copied after its operands into a kira::View, in the
        // lambda total's call opens (E3): no lambda returns a view. Each impure sibling here
        // runs before the view is formed: decision 4b, read literally, refuses a view of a
        // shared place (a const& parameter, a mut global) with an impure call in its span, so
        // `total(tail(xs, nextSize()))` and `countOf(gl, nextSize())` are ViewPass's to refuse.
        val param = body("lentFromParameter")
        assertTrue(param.contains("const kira::Size t0_ = nextSize();\n          const kira::View<std::int32_t> t1_ = tailAt(t0_, xs);\n          return total(t1_);"), param)
        assertFalse(param.contains("= xs;"), param)
        assertFalse(param.contains("-> kira::View"), param)
        // A mut global Arr lends a MutView (the typer's MutView lending), of the global itself,
        // made after the index: `kira::mutView(garr)` in the call's object expression would run
        // before its argument.
        val global = body("lentFromGlobal")
        assertTrue(global.contains("const kira::Size t0_ = nextSize();\n          return total(kira::mutView(garr).from(t0_));"), global)
        // A List converted to a View is the list itself, whatever the callee does with it.
        val converted = body("viewKeptNowhere")
        assertTrue(converted.contains("const kira::Size t0_ = nextSize();\n          return countAfter(t0_, gl);"), converted)
        assertFalse(converted.contains("const kira::List<std::int32_t> t"), converted)
        // So is an Arr's explicit view after a sibling that grows another list.
        val arr = body("arrViewBeside")
        assertTrue(arr.contains("const std::int32_t t0_ = growGls();\n          return plusView(t0_, kira::mutView(garr));"), arr)
    }

    @Test
    fun aStructsThisReadAsAValueBesideAMutFxOfItsOwnIsCopiedFirst() {
        // `this` in a struct is the receiver C++ holds by reference, shared state bump() writes:
        // D33 reads it before the sibling's effect, as it reads a field (viaField in r6).
        assertTrue(text.contains("const Acc t0_ = *this;\n          const std::int32_t t1_ = bump();\n          return peek(t0_, t1_);"), text)
    }

    @Test
    fun thisAsAReceiverReadsLikeANamedStructReceiverAndBothAreRefusedUntilQ4IsLowered() {
        // The user's Q4 reads a method call's receiver when the call runs (210 for each), and no
        // lowering of that exists before W2.9.8; a snapshot would give 110, the answer Q4
        // rejected. So the checker refuses a struct receiver beside a write of its place,
        // however it is named: the global, `this`, and an implicit `this` (40-round3 3.2).
        val refused = receiverRefusals()
        listOf("return gacc.plus(bumpAcc())", "return this.plus(bump())", "return plus(bump())").forEach { call ->
            assertTrue(refused.any { (l, _) -> l == receiverLine(call) }, "$call is refused:\n$refused")
        }
    }

    @Test
    fun anOperandNoViewIsFormedOfIsCopiedWhateverTheResult() {
        // adder returns an Fx (its captures are its own: n and k, not s) and makes no view:
        // D33 reads gs before changeS() writes it (f(0) is 4 for "aaa", not 2 for "b").
        val adder = body("adderBeside")
        assertTrue(adder.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return adder(t0_, t1_);"), adder)
        // viaPair only hands s to pairS, which only interpolates it: still a copy.
        val via = body("viaPairBeside")
        assertTrue(via.contains("const kira::Str t0_ = gs;\n          const std::int32_t t1_ = changeS();\n          return viaPair(t0_, t1_);"), via)
    }

    @Test
    fun aStructReceiverIsCopiedUnlessAViewIsFormedOfIt() {
        // Bag holds a List: head returns a view of its receiver (never copied, read after the
        // sibling), firstPlus only reads an element (copied first).
        val lends = body("bagLends")
        assertTrue(lends.contains("const kira::Size t0_ = nextSize();\n          const kira::View<std::int32_t> t1_ = gbag.head(t0_);\n          return total(t1_);"), lends)
        assertFalse(lends.contains("const Bag"), lends)
        val reads = body("bagReads")
        assertTrue(reads.contains("const Bag t0_ = gbag;\n          const std::int32_t t1_ = next();\n          return t0_.firstPlus(t1_);"), reads)
    }

    @Test
    fun anFxParameterOnlyCalledIsATemplateParameterAndOnePassedOnIsNot() {
        // EscapePass (W2.5) decides whether an Fx parameter escapes, and its entry wins: handOn
        // only hands `each` to eachOf's non-escaping parameter, so both are template parameters
        // (design 5.6). Both are module-private, so they are declared in the source's own namespace.
        val template = "template<typename F_each>\n      requires kira::Callable<F_each, void, std::int32_t>\n    "
        assertTrue(text.contains(template + "void eachOf(const kira::List<std::int32_t>& xs, F_each&& each);"), text)
        assertTrue(text.contains(template + "[[maybe_unused]] void handOn(const kira::List<std::int32_t>& xs, F_each&& each);"), text)
        // Where EscapePass wrote no entry (here, the rule passes off), KI-2's fallback decides: a
        // parameter the body only calls is a template parameter, one handed on is a kira::Fn.
        val fallback = withoutRulePasses { CppExprTestSupport.emit("hoister-fallback", listOf(module)).source(module) }
        assertTrue(fallback.contains(template + "void eachOf(const kira::List<std::int32_t>& xs, F_each&& each);"), fallback)
        assertTrue(fallback.contains("void handOn(const kira::List<std::int32_t>& xs, const kira::Fn<void(std::int32_t)>& each);"), fallback)
    }

    @Test
    fun aMemberStyleAndAFreeFunctionBindingReadTheirReceiverTheSameWay() {
        // A container receiver beside a write of it is read when the call runs (Q4), whichever
        // binding spells the call: until W2.9.8 lowers that for a method call, both spellings
        // are refused, the member-style `gm.get(k)` and the free `kira::at` of `gl.get(i)`, and
        // the module is refused whole: five reports, one per receiver (40-round3 3.2).
        val refused = receiverRefusals()
        assertEquals(5, refused.size, "one rules.exclusivity.order per receiver:\n$refused")
        listOf("return gm.get(putKey()).unwrapOr(-1)", "return gl.get(pushed())").forEach { call ->
            assertTrue(refused.any { (l, _) -> l == receiverLine(call) }, "$call is refused:\n$refused")
        }
        // The index spelling reads its List when kira::at runs, after the index: Q4's answer.
        val index = body("listIndexBeside")
        assertTrue(index.contains("return kira::at(gl, pushed());"), index)
    }

    /** A method call's receiver beside a write of its place (40-round3 3.2's interim refusal), in a module of its own. */
    private val receivers = Module(
        "hoist:receivers",
        """
        mut gl: List<Int32> = List<Int32> { }
        mut gm: Map<Int32, Int32> = Map<Int32, Int32> { }

        fx pushed: () Size {
            gl.add(7)
            return gl.size() - 1
        }

        fx putKey: () Int32 {
            gm.put(1, 42)
            return 1
        }

        pub fx mapReadBeside: () Int32 {
            return gm.get(putKey()).unwrapOr(-1)
        }

        pub fx listReadBeside: () Int32 {
            return gl.get(pushed())
        }

        pub struct Acc {
            pub n: Int32 = 1

            pub mut fx bump: () Int32 {
                n = 2
                return 10
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

        mut gacc: Acc = Acc { }

        fx bumpAcc: () Int32 {
            gacc.n = 2
            return 10
        }

        pub fx namedBeside: () Int32 {
            return gacc.plus(bumpAcc())
        }
        """,
    )

    /** The line of [snippet] in [receivers]' file (its module line and a blank line come first). */
    private fun receiverLine(snippet: String): Int =
        receivers.text.lines().indexOfFirst { it.trim().startsWith(snippet) }.also { assertTrue(it >= 0, snippet) } + 1

    /** Each `rules.exclusivity.order` the backend reports on [receivers], as (line, message); it refuses the module. */
    private fun receiverRefusals(): List<Pair<Int, String>> {
        val result = CppExprTestSupport.emitRefused("hoister-receivers", listOf(receivers))
        val all = result.diagnostics.map { Triple(it.code, it.position?.lineNumber ?: -1, it.message) }
        assertTrue(all.all { (code, _, _) -> code == "rules.exclusivity.order" }, "nothing but the order rule:\n$all")
        return all.map { (_, l, m) -> l to m }
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
        // A MutView handed to a callee writes what it is lent from (the named write of design 30 3.2).
        val view = body("mutViewSibling")
        assertTrue(view.contains("const std::int32_t t0_ = kira::at(p, 0);\n          const std::int32_t t1_ = zero(kira::mutView(p).from(0));\n          return sub(t0_, t1_);"), view)
        // A local no sibling writes stays where it is.
        assertTrue(body("localBeside").contains("return sub(x, next());"), body("localBeside"))
    }

    @Test
    fun aGenericParameterInstantiatedWithAListIsCopiedLikeOne() {
        // keep<T> stores its T; instantiated with List<Int32>, the copy D33 reads is its own.
        val list = body("genericKeepsAList")
        assertTrue(list.contains("const kira::List<std::int32_t> t0_ = gl;\n          const kira::Size t1_ = nextSize();\n          keep<kira::List<std::int32_t>>(h, t0_, t1_);"), list)
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
        // The view is of gv3, which setG3() writes: get(0) through it is a read of shared
        // state, made before the write (gcc and MSVC printed 8, clang 0; Kira gives 0).
        val b = body("viewReadBeside")
        assertTrue(b.contains("const std::int32_t t0_ = kira::mutView(gv3)[0];\n          const std::int32_t t1_ = setG3();\n          return sub(t0_, t1_);"), b)
    }

    @Test
    fun aViewOfAStrLiteralIsAViewOfStaticStorage() {
        // kira::str::view("abc") viewed a temporary kira::Str, which died with the statement;
        // kira::lit is the literal's own storage, as the implicit conversion spells it.
        val b = body("literalView")
        assertTrue(b.contains("return sizeOfChars(kira::lit(\"abc\"));"), b)
    }

    // ---- views of temporaries: E2 and E3 (design 30, 3.4) ----------------------------------------

    private val views = Module(
        "hoist:views",
        """
        mut ticks: Int32 = 0
        mut idx: Size = 0
        mut gl: List<Int32> = List<Int32> { values = [1, 2, 3] }

        fx next: () Int32 {
            ticks += 1
            return ticks
        }

        fx nextSize: () Size {
            idx += 1
            return idx
        }

        fx makeList: () List<Int32> {
            ticks += 1
            return List<Int32> { values = [1000, 2000, 3000, 4000] }
        }

        // Impure (they count), so their result is an owner D33 must spill before the view of
        // it is formed; a pure one stays in the full expression that uses the view.
        fx makeRef: () Ref<List<Int32>> {
            ticks += 1
            return Ref<List<Int32>> { value = List<Int32> { values = [10, 20, 30, 40] } }
        }

        fx makeMaybe: () Maybe<List<Int32>> {
            ticks += 1
            m: Maybe<List<Int32>> = List<Int32> { values = [10, 20, 30, 40] }
            return m
        }

        fx total: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        fx minus: (v: View<Int32>, k: Int32) Int32 {
            return total(v) - k
        }

        fx plusSize: (k: Size, v: View<Int32>) Int32 {
            return total(v) + (k as Int32)
        }

        fx tail: (v: View<Int32>, at: Size) View<Int32> {
            return v.from(at)
        }

        fx pick: (v: View<Int32>, k: Size) View<Int32> {
            return v
        }

        pub fx ownerSpilled: () Int32 {
            ticks = 0
            return minus(makeList().view(), next())
        }

        pub fx chainAtItsRoot: () Int32 {
            ticks = 0
            idx = 0
            return minus(tail(makeList().view(), nextSize()), next())
        }

        pub fx receiverOwnerSpilled: () Int32 {
            idx = 0
            return total(makeList().from(nextSize()))
        }

        pub fx chainOfChains: () Int32 {
            idx = 0
            return total(pick(tail(makeList().view(), 1), nextSize()))
        }

        pub fx refOwnerSpilled: () Int32 {
            idx = 0
            return plusSize(nextSize(), makeRef().value.from(1))
        }

        pub fx maybeOwnerSpilled: () Int32 {
            idx = 0
            return plusSize(nextSize(), makeMaybe().value.from(1))
        }

        pub fx convertedRefStorage: () Int32 {
            idx = 0
            return plusSize(nextSize(), tail(makeRef().value, 1))
        }

        pub fx pureOwnerKept: () Int32 {
            idx = 0
            return total(tail(List<Int32> { values = [5, 6, 7] }.view(), nextSize()))
        }

        pub fx inOneStatement: () Int32 {
            return total(makeList().view())
        }

        fx setAt: (mut e: Int32) Int32 {
            e = 50
            return 0
        }

        pub fx elementWriteSeen: () Int32 {
            mut ls: List<Int32> = List<Int32> { values = [1, 2, 3] }
            return minus(ls, setAt(mut ls[0]))
        }

        fx churn: () Int32 {
            ticks += 1
            mut junk: List<Int32> = List<Int32> { values = [7, 7, 7, 7, 7, 7, 7, 7] }
            junk.add(7)
            return ticks
        }

        pub struct Wrap {
            pub r: Ref<List<Int32>>

            pub fx items: () View<Int32> {
                return r.value.view()
            }
        }

        fx sum2: (v: View<Int32>, k: Int32) Int32 {
            return total(v) + k
        }

        pub fx positionalOwner: () Int32 {
            ticks = 0
            return sum2(Wrap { Ref<List<Int32>> { [100, 200, 300, 400, 500, 600, 700, 800] } }.items(), churn())
        }

        pub fx namedOwner: () Int32 {
            ticks = 0
            return sum2(Wrap { r = Ref<List<Int32>> { value = List<Int32> { values = [100, 200, 300, 400, 500, 600, 700, 800] } } }.items(), churn())
        }

        pub struct Stamp {
            pub a: Int32 = 0
            pub k: Int32 = stamp()
        }

        pub fx stamp: () Int32 {
            ticks += 1
            return ticks
        }

        pub fx impureDefault: () Int32 {
            ticks = 10
            return ticks * 100 + Stamp { a = 1 }.k
        }
        """,
    )

    private val viewsTree by lazy { CppExprTestSupport.emit("hoister-views", listOf(views)) }

    @Test
    fun aViewOfATemporaryIsFormedInsideTheLambdaThatHoldsItsOwner() {
        // E2: the owner is spilled into a typed temporary and the view formed over it, never
        // `const kira::View<std::int32_t> t0_ = kira::view(makeList());` (gcc printed
        // 1204354817 where 10001 is right, MSVC's ASan a heap-use-after-free, design 30 R2).
        val source = viewsTree.source(views)
        val e2 = bodyIn(source, "ownerSpilled")
        assertTrue(e2.contains("const kira::List<std::int32_t> t0_ = makeList();\n          const std::int32_t t1_ = next();\n          return minus(kira::view(t0_), t1_);"), e2)
        // E3: the spill is opened at the chain's root, minus, and tail's view is a kira::View
        // temporary of that lambda, never the result of a lambda of its own (R1: gcc printed
        // -1950596383, MSVC's ASan a heap-use-after-free).
        val e3 = bodyIn(source, "chainAtItsRoot")
        assertTrue(
            e3.contains("const kira::List<std::int32_t> t0_ = makeList();\n          const kira::Size t1_ = nextSize();\n          const kira::View<std::int32_t> t2_ = tail(kira::view(t0_), t1_);\n          const std::int32_t t3_ = next();\n          return minus(t2_, t3_);"),
            e3,
        )
        val receiver = bodyIn(source, "receiverOwnerSpilled")
        assertTrue(receiver.contains("const kira::List<std::int32_t> t0_ = makeList();\n          const kira::Size t1_ = nextSize();\n          return total(kira::view(t0_).from(t1_));"), receiver)
        val chains = bodyIn(source, "chainOfChains")
        assertTrue(
            chains.contains("const kira::List<std::int32_t> t0_ = makeList();\n          const kira::View<std::int32_t> t1_ = tail(kira::view(t0_), 1);\n          const kira::Size t2_ = nextSize();\n          const kira::View<std::int32_t> t3_ = pick(t1_, t2_);\n          return total(t3_);"),
            chains,
        )
        // An owner with no effect of its own is still named once a view of it is (a temporary
        // of the view's declaration would die with it).
        val pure = bodyIn(source, "pureOwnerKept")
        assertTrue(
            pure.contains("const kira::List<std::int32_t> t0_ = kira::List<std::int32_t>{5, 6, 7};\n          const kira::Size t1_ = nextSize();\n          const kira::View<std::int32_t> t2_ = tail(kira::view(t0_), t1_);\n          return total(t2_);"),
            pure,
        )
        // A place a fresh Ref or Maybe owns: the handle is the owner, spilled whole.
        assertTrue(bodyIn(source, "refOwnerSpilled").contains("= makeRef();"), bodyIn(source, "refOwnerSpilled"))
        assertTrue(bodyIn(source, "maybeOwnerSpilled").contains("const kira::Maybe<kira::List<std::int32_t>> t1_ = makeMaybe();"), bodyIn(source, "maybeOwnerSpilled"))
        assertTrue(bodyIn(source, "convertedRefStorage").contains("= makeRef();"), bodyIn(source, "convertedRefStorage"))
        // With nothing to order, the view of a temporary lives to the end of the full expression.
        assertTrue(bodyIn(source, "inOneStatement").contains("return total(kira::view(makeList()));"), bodyIn(source, "inOneStatement"))
        // E1: a List converted to a View is never copied, so a sibling's write of an element is
        // seen. The list is a local (private, design 30 3.3): a view of a shared place with an
        // impure call in its span is refused by decision 4b read literally, as `minus(gl,
        // setFirst())` with gl a mut global now is.
        assertTrue(bodyIn(source, "elementWriteSeen").contains("const std::int32_t t0_ = setAt(kira::at(ls, 0));\n          return minus(ls, t0_);"), bodyIn(source, "elementWriteSeen"))
        // E2's owner is the fresh value itself, whatever it holds: a struct holding the only Ref
        // to the list its items() views is named before the view is, positional or named (round
        // 1's p2: `const kira::View<std::int32_t> t0_ = Wrap{...}.items();` printed 823559285 on
        // gcc where Kira gives 3601, MSVC's ASan a heap-use-after-free).
        val wrapOwner = "const kira::View<std::int32_t> t1_ = t0_.items();\n          const std::int32_t t2_ = churn();\n          return sum2(t1_, t2_);"
        listOf("positionalOwner", "namedOwner").forEach { fn ->
            val b = bodyIn(source, fn)
            assertTrue(b.contains("const Wrap t0_ = Wrap{.r = std::make_shared<kira::Box<kira::List<std::int32_t>>>(") && b.contains(wrapOwner), "$fn:\n$b")
        }
        // A construction whose left-out default has an effect is IMPURE: the read of ticks is
        // made before stamp() runs in Stamp's default member initializer.
        val stamped = bodyIn(source, "impureDefault")
        assertTrue(stamped.contains("const std::int32_t t0_ = ticks * 100;\n          const std::int32_t t1_ = Stamp{.a = 1}.k;"), stamped)
        // No lambda anywhere returns a view.
        assertFalse(source.contains("-> kira::View"), source)
        assertFalse(source.contains("-> kira::MutView"), source)
    }

    @TestFactory
    fun theViewsOfTemporariesRunLeftToRightOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("views [${tc.id}]") {
                val driver = buildString {
                    append("#include \"").append(views.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    listOf(
                        "views::ownerSpilled() == 9998" to "E2: the list is spilled, then next()",
                        "views::chainAtItsRoot() == 8998" to "E3: tail's view is a temporary of minus's lambda",
                        "views::receiverOwnerSpilled() == 9000" to "E2: a receiver the view is formed of",
                        "views::chainOfChains() == 9000" to "E3: a view of a view of a temporary",
                        "views::refOwnerSpilled() == 91" to "a place a fresh Ref owns",
                        "views::maybeOwnerSpilled() == 91" to "a place a fresh Maybe owns",
                        "views::convertedRefStorage() == 91" to "a converted place a fresh Ref owns",
                        "views::pureOwnerKept() == 13" to "E2: an owner with no effect is named before its view is",
                        "views::inOneStatement() == 10000" to "a view of a temporary in one full expression",
                        "views::elementWriteSeen() == 55" to "E1: the view is of ls, so setAt's write of ls[0] is seen",
                        "views::positionalOwner() == 3601" to "E2: a positional struct holding the only Ref is the owner",
                        "views::namedOwner() == 3601" to "E2: a named struct holding the only Ref is the owner",
                        "views::impureDefault() == 1011" to "a construction's impure default runs after the read before it",
                    ).forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(viewsTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n13 checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    // ---- what no effects table can make pure (decision 4b, read literally) ------------------------

    private val optimistic = Module(
        "hoist:optimistic",
        """
        mut ticks: Int32 = 0

        fx next: () Int32 {
            ticks += 1
            return ticks
        }

        fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }

        fx zero: (m: MutView<Int32>) Int32 {
            m[0] = 9
            return 0
        }

        pub fx trustedPure: () Int32 {
            return sub(ticks, next())
        }

        pub fx throughAnFx: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
            return sub(ticks, f(1))
        }

        pub fx handedAMutView: () Int32 {
            mut p: Arr<Int32, 4> = [1, 2, 3, 4]
            return sub(p[0], zero(p.from(0)))
        }
        """,
    )

    /**
     * The real typer, then an effects table that calls every call and every callee PURE, as an
     * EffectsPass that proved too much would: what the hoister ranks IMPURE anyway is what no
     * table may make pure.
     */
    private fun everythingPure(unit: CompilationUnit, options: CppOptions): CppModuleEmitter {
        val program = KiraTyper.run(unit, TyperMode.STRICT, TyperOptions(options.freestanding, options.headerOnly, options.namespaces))
        assertFalse(program.hasErrors, program.diagnostics.joinToString("\n"))
        val m = program.model
        (m.calls.values + m.opCalls.values).forEach { rc -> rc.fn?.let { m.fnEffects[it] = Effect.PURE } }
        m.calls.keys.forEach { m.effects[it] = Effect.PURE }
        m.opCalls.keys.forEach { m.effects[it] = Effect.PURE }
        return TypedCppModuleEmitter(program, options, program.diagnostics.map { CppModuleEmitterFactory.convert(it) }, CppEmitParts.standard())
    }

    @Test
    fun anFxCallAndACallHandedAMutViewAreImpureWhateverTheEffectsTableSays() {
        val tree = CppExprTestSupport.emit("hoister-optimistic", listOf(optimistic), emitterFactory = ::everythingPure)
        val source = tree.source(optimistic)
        // The control: a statically dispatched call with a body is taken at the table's word.
        assertTrue(bodyIn(source, "trustedPure").contains("return impl_::sub(impl_::ticks, next());"), bodyIn(source, "trustedPure"))
        // An Fx value may run any body: ticks is read before it runs (a template, in the header).
        val header = tree.header(optimistic)
        assertTrue(header.contains("const std::int32_t t0_ = impl_::ticks;\n          const std::int32_t t1_ = f(1);\n          return impl_::sub(t0_, t1_);"), header)
        // A MutView handed to a callee is a write to the place it is formed of: p[0] is read
        // before zero writes it through the view (left unordered, gcc and MSVC printed 8).
        val mv = bodyIn(source, "handedAMutView")
        assertTrue(mv.contains("const std::int32_t t0_ = kira::at(p, 0);\n          const std::int32_t t1_ = zero(kira::mutView(p).from(0));\n          return impl_::sub(t0_, t1_);"), mv)
    }

    // ---- what the emitter refuses: the rule's backstops, and one order D33 cannot give -------------

    private val refusing = """
        use "kira:sync"

        mut gl: List<Int32> = List<Int32> { values = [1, 2, 3] }
        mut sbs: List<StrBuf<8>> = List<StrBuf<8>> { }
        mut gv: Maybe<MutView<Int32>> = null
        mut ticks: Int32 = 0

        fx next: () Int32 {
            ticks += 1
            return ticks
        }

        fx growSbs: () Int32 {
            sbs.add(StrBuf<8> { })
            return 1
        }

        fx makeList: () List<Int32> {
            return List<Int32> { values = [10, 20, 30] }
        }

        fx tail: (v: View<Int32>, at: Size) View<Int32> {
            return v.from(at)
        }

        fx total: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        fx minus: (v: View<Int32>, k: Int32) Int32 {
            return total(v) - k
        }

        pub struct Win {
            pub v: View<Int32>
            pub k: Size = 0
        }

        fx keepField: (r: Ref<List<Int32>>) Void {
            gv = r.value.view()
        }

        fx keepM: (m: Maybe<Ref<List<Int32>>>) Void {
            gv = m.value.value.view()
        }

        fx mkKeeper<T>: (sink: Ref<List<MutView<T>>>) Fx<Tuple1<Ref<List<T>>>, Void> {
            return fx (r: Ref<List<T>>) Void {
                sink.value.add(r.value.view())
            }
        }

        fx lockAndKeep: (m: Mutex<Ref<List<Int32>>>) Void {
            m.lock(fx (mut l: Ref<List<Int32>>) Void {
                gv = l.value.view()
            })
        }

        pub fx keptByAnFxValue: () Void {
            k: Fx<Tuple1<Ref<List<Int32>>>, Void> = fx (r: Ref<List<Int32>>) Void {
                gv = r.value.view()
            }
        }

        pub fx keptByATemporaryMutex: () Void {
            Mutex<Ref<List<Int32>>> { value = Ref<List<Int32>> { value = List<Int32> { values = [1] } } }.lock(fx (mut l: Ref<List<Int32>>) Void {
                gv = l.value.view()
            })
        }

        pub fx viewInALocal: () Int32 {
            v: View<Int32> = makeList().view()
            return total(v)
        }

        pub fx viewsInALocalList: () Size {
            mut vs: List<View<Int32>> = List<View<Int32>> { }
            return 0
        }

        pub fx viewInAField: () Size {
            w: Size = Win { v = gl, k = 1 }.k
            return w
        }

        pub fx returnsATemporarysView: () View<Int32> {
            return tail(makeList().view(), 2)
        }

        fx tailOfAList: (xs: List<Int32>, at: Size) View<Int32> {
            return xs.from(at)
        }

        pub fx returnsALocalsView: () View<Int32> {
            ys: List<Int32> = makeList()
            return ys.view()
        }

        pub fx loopsOverATemporarysView: () Int32 {
            mut s: Int32 = 0
            for x: Int32 in makeList().view() {
                s += x
            }
            return s
        }

        pub fx branchesAView: (c: Bool, v: View<Int32>) View<Int32> {
            return if c {
                trace(1)
                v
            } else {
                v
            }
        }

        pub fx branchViewsATemporaryBesideAnEffect: (c: Bool) Int32 {
            return minus(if c { makeList().view() } else { gl.view() }, next())
        }

        pub fx strBufElementBesideAGrowth: () Void {
            sbs[0].set("a${'$'}{growSbs()}")
        }

        pub fx safeInOneStatement: () Int32 {
            return total(makeList().view())
        }

        pub fx safeBranchInOneStatement: (c: Bool) Int32 {
            return total(if c { makeList().view() } else { gl.view() })
        }

        pub fx safeLoopOverAParameter: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        pub fx safeReturnOfAParameter: (v: View<Int32>) View<Int32> {
            return v.from(1)
        }
    """.trimIndent()

    /** Runs [block] with the rule passes (W2.5) switched off: what the emitter's own backstops catch when a checker's bug lets a program through. */
    private fun <T> withoutRulePasses(block: () -> T): T {
        val rules = KiraTyper.rulePasses.toList()
        KiraTyper.rulePasses.clear()
        try {
            return block()
        } finally {
            KiraTyper.rulePasses.addAll(rules)
        }
    }

    @Test
    fun aViewWhereTheRuleAllowsNoneIsTheCheckersAndPastItAnInternalError() {
        // Every shape here is refused by ViewPass (W2.5) before the emitter runs (decision 4b),
        // with the checker's own code; the emitter lowers nothing and reports nothing. The keep*
        // functions, lockAndKeep and keptBy* (the converge verdict's h1, h7, h5, h6, h10 and h9,
        // each a heap-use-after-free under MSVC's ASan before) store a view in `gv`, a
        // Maybe<MutView<Int32>>, a type no program may declare: they are refused at `gv` itself.
        // A view of a temporary in an if-expression's branch (KI-13) is rules.view.position, as a
        // for range with a TEMP origin is (40-round3 F3), in both the ordered and the one-statement
        // form.
        val lines = "module \"vl:bad\"\n\n$refusing\n".lines()
        fun line(snippet: String): Int = lines.indexOfFirst { it.trim().startsWith(snippet) }.also { assertTrue(it >= 0, snippet) } + 1
        val checked = net.exoad.kira.cpp.decls.DeclTestSupport.emit(net.exoad.kira.cpp.decls.DeclTestSupport.module("vl:bad", refusing))
        // The typer's and the rules' diagnostics are the run's; the refused module emits nothing of its own.
        val found = (checked.runDiagnostics + checked.diagnostics("vl:bad")).filter { it.isError }.map { Triple(it.position?.lineNumber ?: -1, it.code, it.message) }
        val branch = line("return if c {")
        val expected = listOf(
            line("mut gv: Maybe<MutView<Int32>>") to "rules.view.type",
            line("pub v: View<Int32>") to "rules.view.type",
            line("fx mkKeeper<T>") to "rules.view.type",
            line("sink.value.add(r.value.view())") to "rules.view.write",
            line("v: View<Int32> = makeList().view()") to "rules.view.local",
            line("mut vs: List<View<Int32>>") to "rules.view.type",
            line("return tail(makeList().view(), 2)") to "rules.view.return",
            line("return xs.from(at)") to "rules.view.return",
            line("return ys.view()") to "rules.view.return",
            line("for x: Int32 in makeList().view()") to "rules.view.position",
            branch + 2 to "rules.view.position",
            branch + 4 to "rules.view.position",
            line("return minus(if c { makeList().view() } else { gl.view() }, next())") to "rules.view.position",
            line("return minus(if c { makeList().view() } else { gl.view() }, next())") to "rules.view.write",
            line("return total(if c { makeList().view() } else { gl.view() })") to "rules.view.position",
        )
        val shown = found.joinToString("\n") { (l, c, m) -> "$l $c $m" }
        assertEquals(expected.size, found.size, "one refusal per shape, and none for the safe ones:\n$shown")
        expected.forEach { (at, code) ->
            assertTrue(found.any { (l, c, _) -> l == at && c == code }, "line $at: $code in:\n$shown")
        }
        assertTrue(found.none { (_, c, _) -> c.startsWith("cpp.") }, "the checker refuses first; the emitter reports nothing:\n$shown")

        // Past the checker (the rule passes off, as a checker's bug would let a program through),
        // each is the emitter's backstop, cpp.internal naming the rule that refuses it, and never a
        // cpp.unsupported: no view the rule allows is left unlowered. The one-statement if-expression
        // is lowered safely there (one full expression holds the temporary).
        val past = withoutRulePasses { net.exoad.kira.cpp.decls.DeclTestSupport.emit(net.exoad.kira.cpp.decls.DeclTestSupport.module("vl:bad", refusing)) }
        val internal = CppModuleEmitterFactory.INTERNAL_CODE
        val unsupported = CppModuleEmitterFactory.UNSUPPORTED_CODE
        val backstops = past.diagnostics("vl:bad")
            .filter { it.code == internal || it.code == unsupported }
            .map { Triple(it.position?.lineNumber ?: -1, it.code, it.message) }
        // (The local and its initializer are two constructs on one line: two reports.)
        val caught = listOf(
            Triple(line("gv = r.value.view()"), internal, "rules.view.type"),
            Triple(line("gv = m.value.value.view()"), internal, "rules.view.type"),
            Triple(line("sink.value.add(r.value.view())"), internal, "the receiver of 'add' holds views"),
            Triple(line("gv = l.value.view()"), internal, "rules.view.type"),
            Triple(lines.indexOfLast { it.trim().startsWith("gv = r.value.view()") } + 1, internal, "rules.view.type"),
            Triple(lines.indexOfLast { it.trim().startsWith("gv = l.value.view()") } + 1, internal, "rules.view.type"),
            Triple(line("v: View<Int32> = makeList().view()"), internal, "rules.view.local"),
            Triple(line("mut vs: List<View<Int32>>"), internal, "the local 'vs' holds a view"),
            Triple(line("mut vs: List<View<Int32>>"), internal, "this construction of List<View<Int32>> holds a view"),
            Triple(line("w: Size = Win { v = gl, k = 1 }.k"), internal, "holds a view in 'v'"),
            Triple(line("return tail(makeList().view(), 2)"), internal, "a view into a temporary"),
            Triple(line("return xs.from(at)"), internal, "a view of a local or of a parameter that is no view"),
            Triple(line("return ys.view()"), internal, "a view of a local or of a parameter that is no view"),
            Triple(line("for x: Int32 in makeList().view()"), internal, "rules.view.position"),
            Triple(line("return if c {"), internal, "rules.view.position"),
            Triple(line("return minus(if c { makeList().view() } else { gl.view() }, next())"), internal, "rules.view.position"),
            Triple(line("sbs[0].set("), unsupported, "this hole's effect may move the container the StrBuf is an element of"),
        )
        val pastShown = backstops.joinToString("\n") { (l, c, m) -> "$l $c $m" }
        assertEquals(caught.size, backstops.size, "one report per shape, and none for the safe ones:\n$pastShown")
        caught.forEach { (at, code, text) ->
            assertTrue(backstops.any { (l, c, m) -> l == at && c == code && m.contains(text) }, "line $at: $code '$text' in:\n$pastShown")
        }
        assertEquals(1, backstops.count { (_, c, _) -> c == unsupported }, "the StrBuf hole is the one cpp.unsupported, an order and no view:\n$pastShown")
    }

    @Test
    fun anOrderNoLoweringGivesIsRefusedByTheEmitter() {
        // A StrBuf interpolation's pieces are one call each on the receiver, bound before the hole
        // runs: a hole that may move the List the StrBuf is an element of is an order D33 needs
        // and no lowering gives. The rules accept it (it forms no view); the emitter refuses it.
        val source = """
            mut sbs: List<StrBuf<8>> = List<StrBuf<8>> { }

            fx growSbs: () Int32 {
                sbs.add(StrBuf<8> { })
                return 1
            }

            pub fx strBufElementBesideAGrowth: () Void {
                sbs[0].set("a${'$'}{growSbs()}")
            }
        """.trimIndent()
        val emitted = net.exoad.kira.cpp.decls.DeclTestSupport.emit(net.exoad.kira.cpp.decls.DeclTestSupport.module("vl:order", source))
        val found = emitted.diagnostics("vl:order").filter { it.isError }
        assertEquals(listOf(CppModuleEmitterFactory.UNSUPPORTED_CODE), found.map { it.code }, found.joinToString("\n") { it.render() })
        assertTrue(found.single().message.contains("this hole's effect may move the container the StrBuf is an element of"), found.single().message)
    }

    private val ranges = Module(
        "hoist:ranges",
        """
        fx makeLists: () List<List<Int32>> {
            return List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
        }

        fx makeRef: () Ref<List<Int32>> {
            return Ref<List<Int32>> { value = List<Int32> { values = [1, 2, 3] } }
        }

        fx makeMaybe: () Maybe<List<Int32>> {
            m: Maybe<List<Int32>> = List<Int32> { values = [1, 2, 3] }
            return m
        }

        fx makeList: () List<Int32> {
            return List<Int32> { values = [1, 2, 3] }
        }

        pub fx rangesOfTemporaries: () Int32 {
            mut s: Int32 = 0
            for x: Int32 in makeLists()[0] {
                s += x
            }
            for y: Int32 in makeRef().value {
                s += y
            }
            for z: Int32 in makeMaybe().value {
                s += z
            }
            return s
        }

        pub fx rangesThatNeedNoCopy: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            l: List<Int32> = makeList()
            for x: Int32 in l {
                s += x
            }
            for y: Int32 in makeList() {
                s += y
            }
            for z: Int32 in v {
                s += z
            }
            return s
        }
        """,
    )

    private val rangesText by lazy { CppExprTestSupport.emit("hoister-ranges", listOf(ranges)).source(ranges) }

    @Test
    fun aForRangeThatMayBeAReferenceIntoATemporaryIsCopiedWhileItLives() {
        // `for(x : kira::at(makeLists(), 0))` and `for(y : makeRef()->value)` bound a reference
        // into a temporary that died before the first step (gcc -Werror=dangling-reference; MSVC's
        // ASan a heap-use-after-free). The copy is made in the range expression, and the loop's
        // reference keeps it alive. A variable, a fresh List and a view parameter are iterated
        // as they are.
        val copied = bodyIn(rangesText, "rangesOfTemporaries")
        assertTrue(copied.contains(": kira::List<std::int32_t>(kira::at(makeLists(), 0)))"), copied)
        assertTrue(copied.contains(": kira::List<std::int32_t>(makeRef()->value))"), copied)
        assertTrue(copied.contains(": kira::List<std::int32_t>(kira::unwrap(makeMaybe())))"), copied)
        val plain = bodyIn(rangesText, "rangesThatNeedNoCopy")
        assertTrue(plain.contains("x : l)") && plain.contains("y : makeList())") && plain.contains("z : v)"), plain)
        assertFalse(plain.contains("kira::List<std::int32_t>("), plain)
    }

    // ---- one place however it is spelled (40-round3 R-E and R-A) ----------------------------------

    /** Another module's `mut` globals and the calls that write them (w2-3 #0's lib:ctr, round 2's m1-m7). */
    private val ctr = Module(
        "hoist:ctr",
        """
        pub mut G: Int32 = 5
        pub mut GS: Str = "old"
        pub mut GL: List<Int32> = List<Int32> { values = [1, 2, 3] }
        pub mut GSL: List<Str> = List<Str> { }
        pub mut GLL: List<List<Int32>> = List<List<Int32>> { }
        pub mut GLS: List<Int32> = List<Int32> { }

        pub fx reset: () Void {
            G = 5
            GS = "old"
            GL = List<Int32> { values = [1, 2, 3] }
            GSL = List<Str> { values = ["firstfirstfirstfirstfirstfirstfirstfirstfirstfirstfirst"] }
            GLL = List<List<Int32>> { values = [List<Int32> { values = [1] }] }
            GLS = List<Int32> { values = [100] }
        }

        pub fx bumpG: () Int32 {
            G += 10
            return 0
        }

        pub fx changeGS: () Int32 {
            GS = "newnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnewnew"
            return 0
        }

        pub fx growGL: () Int32 {
            mut i: Int32 = 0
            while i < 64 {
                GL.add(1000)
                i += 1
            }
            return 0
        }

        pub fx growGSL: () Int32 {
            mut i: Int32 = 0
            while i < 64 {
                GSL.add("padpadpadpadpadpadpadpadpadpadpadpadpadpadpadpadpadpad")
                i += 1
            }
            return 0
        }

        pub fx growGLL: () Int32 {
            mut i: Int32 = 0
            while i < 64 {
                GLL.add(List<Int32> { values = [i] })
                i += 1
            }
            return 7
        }

        pub fx growGLS: () Int32 {
            mut i: Int32 = 0
            while i < 64 {
                GLS.add(i)
                i += 1
            }
            return 7
        }

        pub fx store: (mut into: Int32, v: Int32) Void {
            into = v
        }
        """,
    )

    /**
     * Each read of [ctr]'s globals twice: qualified (`ctr.G`, a `MemberRef.ModuleMember` the
     * hoister once ranked PURE) and unqualified (the control). Round 2 measured the qualified
     * forms wrong on the trial: 15 where Kira gives 5, the replacement string, 67 for 3, a
     * heap-use-after-free in kira::cat, a push into freed storage.
     */
    private val twins = Module(
        "hoist:twins",
        """
        use "hoist:ctr"

        fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }

        fx show: (s: Str, k: Int32) Str {
            return "${'$'}{s}${'$'}{k}"
        }

        fx count: (xs: List<Int32>, k: Int32) Size {
            return xs.size()
        }

        fx showK: (k: Int32, s: Str) Str {
            return "${'$'}{k}:${'$'}{s}"
        }

        pub fx m1q: () Int32 {
            reset()
            return sub(ctr.G, bumpG())
        }

        pub fx m1u: () Int32 {
            reset()
            return sub(G, bumpG())
        }

        pub fx m1oq: () Int32 {
            reset()
            return ctr.G + bumpG()
        }

        pub fx m1ou: () Int32 {
            reset()
            return G + bumpG()
        }

        pub fx m1hq: () Str {
            reset()
            return "${'$'}{ctr.G}/${'$'}{bumpG()}"
        }

        pub fx m1hu: () Str {
            reset()
            return "${'$'}{G}/${'$'}{bumpG()}"
        }

        pub fx m2q: () Str {
            reset()
            return show(ctr.GS, changeGS())
        }

        pub fx m2u: () Str {
            reset()
            return show(GS, changeGS())
        }

        pub fx m2lq: () Size {
            reset()
            return count(ctr.GL, growGL())
        }

        pub fx m2lu: () Size {
            reset()
            return count(GL, growGL())
        }

        pub fx m4q: () Str {
            reset()
            return showK(growGSL(), ctr.GSL[0])
        }

        pub fx m4u: () Str {
            reset()
            return showK(growGSL(), GSL[0])
        }

        pub fx m4rq: () Str {
            reset()
            return show(ctr.GSL[0], growGSL())
        }

        pub fx m4ru: () Str {
            reset()
            return show(GSL[0], growGSL())
        }

        pub fx m6q: () Size {
            reset()
            ctr.GLL[0].add(growGLL())
            return GLL[0].size()
        }

        pub fx m6u: () Size {
            reset()
            GLL[0].add(growGLL())
            return GLL[0].size()
        }

        pub fx m6sq: () Int32 {
            reset()
            store(mut ctr.GLS[0], growGLS())
            return GLS[0]
        }

        pub fx m6su: () Int32 {
            reset()
            store(mut GLS[0], growGLS())
            return GLS[0]
        }
        """,
    )

    private val twinsTree by lazy { CppExprTestSupport.emit("hoister-twins", listOf(ctr, twins)) }

    /** The pairs of [twins]: a qualified form and its unqualified control. */
    private val twinPairs = listOf("m1q" to "m1u", "m1oq" to "m1ou", "m1hq" to "m1hu", "m2q" to "m2u", "m2lq" to "m2lu", "m4q" to "m4u", "m4rq" to "m4ru", "m6q" to "m6u", "m6sq" to "m6su")

    @Test
    fun aModuleQualifiedGlobalIsEmittedAsItsUnqualifiedName() {
        // R-E: the hoister reads the place and the rank from the model, so `ctr.G` is the place
        // `G` is (Place.Global) and READS as it is: each qualified form is emitted exactly as its
        // control, the read copied before the call that writes it.
        val source = twinsTree.source(twins)
        twinPairs.forEach { (q, u) ->
            assertEquals(bodyIn(source, u), bodyIn(source, q), "$q is emitted as $u")
        }
        assertTrue(bodyIn(source, "m1q").contains("const std::int32_t t0_ = ::ctr::G;\n          const std::int32_t t1_ = ::ctr::bumpG();\n          return sub(t0_, t1_);"), bodyIn(source, "m1q"))
        assertTrue(bodyIn(source, "m4q").contains("const std::int32_t t0_ = ::ctr::growGSL();\n          const kira::Str t1_ = kira::at(::ctr::GSL, 0);"), bodyIn(source, "m4q"))
        assertTrue(bodyIn(source, "m6q").contains("const std::int32_t t0_ = ::ctr::growGLL();\n          kira::at(::ctr::GLL, 0).push_back(t0_);"), bodyIn(source, "m6q"))
    }

    @TestFactory
    fun aModuleQualifiedGlobalReadsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("twins [${tc.id}]") {
                val first = "firstfirstfirstfirstfirstfirstfirstfirstfirstfirstfirst"
                val driver = buildString {
                    append("#include \"").append(twins.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    listOf(
                        "twins::m1q() == 5 && twins::m1u() == 5" to "m1: a qualified Int32 is read before bumpG()",
                        "twins::m1oq() == 5 && twins::m1ou() == 5" to "m1: an operator's operand too",
                        "twins::m1hq() == \"5/0\" && twins::m1hu() == \"5/0\"" to "m1: an interpolation hole too",
                        "twins::m2q() == \"old0\" && twins::m2u() == \"old0\"" to "m2: a qualified Str is copied before changeGS()",
                        "twins::m2lq() == 3 && twins::m2lu() == 3" to "m2: a qualified List is copied before growGL()",
                        "twins::m4q() == \"0:$first\" && twins::m4u() == \"0:$first\"" to "m4: an element read after growGSL() is located after it",
                        "twins::m4rq() == \"${first}0\" && twins::m4ru() == \"${first}0\"" to "m4: an element read before growGSL() is copied first",
                        "twins::m6q() == 2 && twins::m6u() == 2" to "m6: a mut fx receiver element is located after growGLL()",
                        "twins::m6sq() == 7 && twins::m6su() == 7" to "m6: a mut argument element is located after growGLS()",
                    ).forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(twinsTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n9 checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    /** Each lent result (R-A) twice: the accessor call and the place spelling it is (`gl.get(0)` and `gl[0]`, `gms.unwrap()` and `gms.value`). */
    private val lent = Module(
        "hoist:lent",
        """
        mut ticks: Int32 = 0
        mut idx: Size = 0
        mut gl: List<Int32> = List<Int32> { values = [1, 2, 3] }
        mut gstrs: List<Str> = List<Str> { values = ["first"] }
        mut gll: List<List<Int32>> = List<List<Int32>> { values = [List<Int32> { values = [10, 20, 30] }] }
        mut gms: Maybe<Str> = "some"

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

        fx resetL: () Int32 {
            gl = List<Int32> { values = [8] }
            return 0
        }

        fx change: () Int32 {
            gstrs = List<Str> { values = ["replaced, and long enough to live on the heap, not in a small buffer"] }
            gms = "replaced too, and long enough to live on the heap, not in a small buffer"
            return 0
        }

        fx showL: (s: Str, k: Int32) Str {
            return "${'$'}{s}:${'$'}{k}"
        }

        fx total: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        fx minus: (v: View<Int32>, k: Int32) Int32 {
            return total(v) - k
        }

        fx setAt: (mut e: Int32) Int32 {
            e = 50
            return 0
        }

        pub fx lentScalar: () Int32 {
            gl = List<Int32> { values = [1, 2, 3] }
            return sub(gl.get(0), resetL())
        }

        pub fx indexScalar: () Int32 {
            gl = List<Int32> { values = [1, 2, 3] }
            return sub(gl[0], resetL())
        }

        pub fx lentStr: () Str {
            gstrs = List<Str> { values = ["first"] }
            return showL(gstrs.get(0), change())
        }

        pub fx indexStr: () Str {
            gstrs = List<Str> { values = ["first"] }
            return showL(gstrs[0], change())
        }

        pub fx lentMaybe: () Str {
            gms = "some"
            return showL(gms.unwrap(), change())
        }

        pub fx fieldMaybe: () Str {
            gms = "some"
            return showL(gms.value, change())
        }

        pub fx lentView: () Int32 {
            mut ll: List<List<Int32>> = List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
            return minus(ll.get(0), setAt(mut ll[0][0]))
        }

        pub fx indexView: () Int32 {
            mut ll: List<List<Int32>> = List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
            return minus(ll[0], setAt(mut ll[0][0]))
        }

        pub fx lentNested: () Int32 {
            idx = 0
            ticks = 0
            return sub(gll.get(0).get(nextSize()), next())
        }

        pub fx indexNested: () Int32 {
            idx = 0
            ticks = 0
            return sub(gll[0][nextSize()], next())
        }

        pub fx lentIndexArg: () Int32 {
            idx = 0
            return gll.get(0).get(nextSize())
        }

        pub fx indexIndexArg: () Int32 {
            idx = 0
            return gll[0][nextSize()]
        }
        """,
    )

    private val lentTree by lazy { CppExprTestSupport.emit("hoister-lent", listOf(lent)) }

    private val lentPairs = listOf(
        "lentScalar" to "indexScalar", "lentStr" to "indexStr", "lentMaybe" to "fieldMaybe",
        "lentView" to "indexView", "lentNested" to "indexNested", "lentIndexArg" to "indexIndexArg",
    )

    @Test
    fun aLentResultIsLoweredAsThePlaceItIs() {
        // R-A, the emitter's side: an accessor's result is the place of the other spelling, so
        // each pair is one C++ text. The element is read before a sibling replaces its List
        // (lentScalar, D33's copy of a by-value read), and a view formed of it is of the element
        // itself, never of a copy: on the merged branch before this, `minus(ll.get(0),
        // setAt(mut ll[0][0]))` copied `ll[0]` first and printed 6 on gcc, clang and msvc, where
        // Kira (and `minus(ll[0], ...)`) gives 55. The receiver of `gll.get(0).get(k)` is a step
        // of the path, as `gll[0][k]`'s is: located where the element is read, never copied.
        val source = lentTree.source(lent)
        lentPairs.forEach { (a, b) ->
            assertEquals(bodyIn(source, b), bodyIn(source, a), "$a is emitted as $b")
        }
        assertTrue(bodyIn(source, "lentScalar").contains("const std::int32_t t0_ = kira::at(gl, 0);\n          const std::int32_t t1_ = resetL();\n          return sub(t0_, t1_);"), bodyIn(source, "lentScalar"))
        assertTrue(bodyIn(source, "lentView").contains("const std::int32_t t0_ = setAt(kira::at(kira::at(ll, 0), 0));\n          return minus(kira::at(ll, 0), t0_);"), bodyIn(source, "lentView"))
        // P1 (50-round4 2.8): the inner element is located after the index runs, in both spellings.
        assertTrue(bodyIn(source, "lentIndexArg").contains("const kira::Size t0_ = nextSize();\n          return kira::at(kira::at(gll, 0), t0_);"), bodyIn(source, "lentIndexArg"))
        assertFalse(source.contains("const kira::List<std::int32_t> t"), "no List is copied:\n$source")
    }

    @TestFactory
    fun aLentResultReadsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("lent [${tc.id}]") {
                val driver = buildString {
                    append("#include \"").append(lent.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    listOf(
                        "lent::lentScalar() == 1 && lent::indexScalar() == 1" to "a lent element is read before resetL() replaces its List: 1 - 0",
                        "lent::lentStr() == \"first:0\" && lent::indexStr() == \"first:0\"" to "a lent Str element is copied before change()",
                        "lent::lentMaybe() == \"some:0\" && lent::fieldMaybe() == \"some:0\"" to "a lent Maybe payload is copied before change()",
                        "lent::lentView() == 55 && lent::indexView() == 55" to "a view of a lent element is of the element: setAt's 50 is seen",
                        "lent::lentNested() == 19 && lent::indexNested() == 19" to "a nested lent element's index runs first: 20 - 1",
                        "lent::lentIndexArg() == 20 && lent::indexIndexArg() == 20" to "a lent receiver is a step of the path",
                    ).forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(lentTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n6 checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    /** w2-6's round-2 c2, c2b, c2c, c4 and c4b: a view of, or a loop over, a lent result beside a write of its owner. */
    private val lentRefused = Module(
        "hoist:lentrefused",
        """
        mut gl2: List<List<Int32>> = List<List<Int32>> { values = [List<Int32> { values = [1, 2, 3] }] }
        mut gm: Maybe<List<Int32>> = List<Int32> { values = [1, 2, 3] }

        @_extern(cpp = "w::sumVF", header = "w.hxx")
        pub fx sumVF: (v: View<Int32>, f: Fx<Tuple0, Void>) Int32;

        fx sumV: (v: View<Int32>) Int32 {
            mut s: Int32 = 0
            for x: Int32 in v {
                s += x
            }
            return s
        }

        fx sumAndClear: (v: View<Int32>) Int32 {
            gl2 = List<List<Int32>> { values = [List<Int32> { values = [7] }] }
            return sumV(v)
        }

        fx sumAndClearM: (v: View<Int32>) Int32 {
            gm = List<Int32> { values = [7] }
            return sumV(v)
        }

        pub fx c2: () Int32 {
            return sumAndClear(gl2.get(0).view())
        }

        pub fx c2b: () Int32 {
            return sumAndClearM(gm.unwrap().view())
        }

        pub fx c2c: () Int32 {
            return sumVF(gl2.get(0).view(), fx () Void { gl2 = List<List<Int32>> { values = [List<Int32> { values = [7] }] } })
        }

        pub fx c4: () Int32 {
            mut s: Int32 = 0
            for x: Int32 in gl2.get(0) {
                gl2 = List<List<Int32>> { values = [List<Int32> { values = [7, 7, 7, 7, 7, 7, 7, 7] }] }
                s += x
            }
            return s
        }

        pub fx c4b: () Int32 {
            mut s: Int32 = 0
            for x: Int32 in gm.unwrap() {
                gm = List<Int32> { values = [7, 7, 7, 7, 7, 7, 7, 7] }
                s += x
            }
            return s
        }
        """,
    )

    @Test
    fun aViewOrALoopOverALentResultBesideAWriteOfItsOwnerIsRefused() {
        // w2-6's round-2 findings #2 and #3 (owner W2.3): `kira::view(kira::at(gl2, 0))` and a
        // range-for over `kira::at(gl2, 0)` point into gl2's storage, which the callee, the Fx or
        // the loop body replaces (g++ printed 197532125 and 1 509 1008271696, MSVC's ASan a
        // heap-use-after-free). The lent result is the place gl2[0] (R-A), so the view rule and
        // the loop rule see the write: refused, and nothing is emitted.
        val result = CppExprTestSupport.emitRefused("hoister-lentrefused", listOf(lentRefused))
        val lines = lentRefused.text.lines()
        fun line(snippet: String): Int = lines.indexOfFirst { it.trim().startsWith(snippet) }.also { assertTrue(it >= 0, snippet) } + 1
        val found = result.diagnostics.filter { it.isError }.map { (it.position?.lineNumber ?: -1) to it.code }
        val expected = listOf(
            line("return sumAndClear(gl2.get(0).view())") to "rules.view.write",
            line("return sumAndClearM(gm.unwrap().view())") to "rules.view.write",
            line("return sumVF(gl2.get(0).view()") to "rules.view.write",
            line("gl2 = List<List<Int32>> { values = [List<Int32> { values = [7, 7") to "rules.exclusivity.loop",
            line("gm = List<Int32> { values = [7, 7") to "rules.exclusivity.loop",
        )
        assertEquals(expected.toSet(), found.toSet(), result.diagnostics.joinToString("\n") { it.render() })
    }
}
