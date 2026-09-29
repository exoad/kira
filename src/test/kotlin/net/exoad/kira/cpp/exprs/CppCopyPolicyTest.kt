package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Copy by default (50-round4, the policy W2.3 owns: `CppCopyPolicy`). Every row is a Kira
 * program whose value is written from Kira's semantics (a value is what it was where Kira read
 * it; Q4 for a container read by a later access, OQ-1 for an immutable value read first), and
 * each is checked twice: the emitted text lends (W1-W3, W6) or copies (rule 6, `T(e)`) where
 * the design says, and the program prints Kira's value on gcc, clang and msvc. The writer is a
 * hook in a global `List` of `Fx` that a callee runs (round 4's p3 and p5): no per-shape
 * analysis sees it, and the policy does not need to.
 */
class CppCopyPolicyTest {
    private val policy = Module(
        "copy:policy",
        """
        mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
        mut gl: List<Int32> = [1, 2, 3]
        mut gl2: List<Int32> = [4, 5, 6]
        mut hooks: List<Fx<Tuple0, Void>> = []
        mut gfs: List<Fx<Tuple0, Int32>> = []

        pub struct Tag {
            pub name: Str
            pub n: Int32

            pub fx describe: () Str {
                runHooks()
                return "${'$'}{name}:${'$'}{n}"
            }

            pub fx plain: () Str {
                return "${'$'}{name}:${'$'}{n}"
            }

            pub fx viaThis: () Str {
                return describe()
            }
        }

        mut gt: Tag = Tag { name = "old-name-long-enough-for-the-heap-00000000", n = 1 }

        pub fx runHooks: () Void {
            items: List<Fx<Tuple0, Void>> = hooks
            for h: Fx<Tuple0, Void> in items {
                h()
            }
        }

        pub fx arm: () Void {
            gs = "old-text-long-enough-to-live-on-the-heap-000000"
            gl = [1, 2, 3]
            gt = Tag { name = "old-name-long-enough-for-the-heap-00000000", n = 1 }
            tag: Str = "held-by-the-closure-long-enough-for-the-heap-00"
            gfs = List<Fx<Tuple0, Int32>> { }
            gfs.add(fx () Int32 {
                runHooks()
                return tag.length() as Int32
            })
            hooks = List<Fx<Tuple0, Void>> { }
            hooks.add(fx () Void {
                gs = "new"
                gl = [100]
                gt = Tag { name = "new", n = 2 }
                gfs = List<Fx<Tuple0, Int32>> { }
            })
        }

        pub fx lenAfter: (s: Str) Int32 {
            runHooks()
            return s.length() as Int32
        }

        pub fx lenOf: (s: Str) Int32 {
            return s.length() as Int32
        }

        pub fx sumAfter: (xs: List<Int32>) Int32 {
            mut t: Int32 = 0
            for x: Int32 in xs {
                runHooks()
                t += x
            }
            return t
        }

        pub fx idAfter<T>: (v: T) T {
            runHooks()
            return v
        }

        pub fx copyStr: () Int32 {
            return lenAfter(gs)
        }

        pub fx copyList: () Int32 {
            return sumAfter(gl)
        }

        pub fx copyGeneric: () Int32 {
            return idAfter<Str>(gs).length() as Int32
        }

        pub fx copyReceiver: () Str {
            return gt.describe()
        }

        pub fx copyCallee: () Int32 {
            return gfs[0]()
        }

        pub fx copyTernary: (c: Bool) Int32 {
            return sumAfter(if c { gl } else { gl2 })
        }

        pub fx copyRange: () Int32 {
            mut t: Int32 = 0
            for x: Int32 in gl {
                runHooks()
                t += x
            }
            return t
        }

        pub fx lendLocal: () Int32 {
            s: Str = gs
            return lenAfter(s)
        }

        pub fx lendParam: (xs: List<Int32>) Int32 {
            return sumAfter(xs)
        }

        pub fx lendParamRow: () Int32 {
            return lendParam(gl)
        }

        pub fx lendThis: () Str {
            return gt.viaThis()
        }

        pub fx lendRangeLocal: () Int32 {
            xs: List<Int32> = gl
            mut t: Int32 = 0
            for x: Int32 in xs {
                runHooks()
                t += x
            }
            return t
        }

        pub fx lendConfined: () Int32 {
            return lenOf(gs)
        }

        pub fx lendReceiverConfined: () Str {
            return gt.plain()
        }

        pub fx lendHole: () Str {
            return "${'$'}{gs}!"
        }

        fx total: (v: View<Int32>) Int32 {
            mut t: Int32 = 0
            for x: Int32 in v {
                t += x
            }
            return t
        }

        pub fx ownerTernary: (c: Bool) Int32 {
            la: List<Int32> = [1, 2, 3]
            lb: List<Int32> = [4, 5]
            return total((if c { la } else { lb }).view())
        }

        pub fx lendRangeReads: () Int32 {
            mut t: Int32 = 0
            for x: Int32 in gl {
                t += x
            }
            return t
        }
        """,
    )

    private val tree by lazy { CppExprTestSupport.emit("copy-policy", listOf(policy)) }

    private fun body(fn: String): String {
        val source = tree.source(policy)
        val head = Regex("\\n  [^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n]*\\n  \\{\\n").find(source) ?: error("no definition of $fn in:\n$source")
        val close = source.indexOf("\n  }\n", head.range.last)
        return source.substring(head.range.last, close)
    }

    /** Each row, the text the policy must emit in it, and whether that text is a copy. */
    private val texts = listOf(
        // Rule 6: not PRIVATE, and the callee runs a hook (not CONFINED).
        Triple("copyStr", "return lenAfter(kira::Str(gs));", true),
        Triple("copyList", "return sumAfter(kira::List<std::int32_t>(gl));", true),
        Triple("copyGeneric", "idAfter<kira::Str>(kira::Str(gs))", true),
        Triple("copyReceiver", "return Tag(gt).describe();", true),
        Triple("copyCallee", "return kira::Fn<std::int32_t()>(kira::at(gfs, 0))();", true),
        Triple("copyTernary", "return sumAfter(kira::List<std::int32_t>(c ? gl : gl2));", true),
        Triple("copyRange", "for(const std::int32_t x : kira::List<std::int32_t>(gl))", true),
        Triple("lendParamRow", "return lendParam(kira::List<std::int32_t>(gl));", true),
        Triple("lendThis", "return Tag(gt).viaThis();", true),
        // O3: a view's owner that is no place is made a prvalue, so the view is of a copy.
        Triple("ownerTernary", "return total(kira::view(kira::List<std::int32_t>(c ? la : lb)));", true),
        // W2: PRIVATE (a local, a by-value parameter, a value `this` in a plain fx), whatever the callee runs.
        Triple("lendLocal", "return lenAfter(s);", false),
        Triple("lendParam", "return sumAfter(xs);", false),
        Triple("viaThis", "return describe();", false),
        Triple("lendRangeLocal", "for(const std::int32_t x : xs)", false),
        Triple("runHooks", "for(const kira::Fn<void()>& h : items)", false),
        Triple("describe", "return kira::cat(name, \":\", n);", false),
        // W3: the consumer is CONFINED and no operand is IMPURE.
        Triple("lendConfined", "return lenOf(gs);", false),
        Triple("lendReceiverConfined", "return gt.plain();", false),
        Triple("lendHole", "return kira::cat(gs, \"!\");", false),
        // W6: a body at most READS.
        Triple("lendRangeReads", "for(const std::int32_t x : gl)", false),
    )

    @Test
    fun eachUseIsLentOnTheWhitelistAndCopiedOffIt() {
        texts.forEach { (fn, text, _) ->
            assertTrue(body(fn).contains(text), "$fn:\n${body(fn)}")
        }
        texts.filter { !it.third }.forEach { (fn, _, _) ->
            val b = body(fn)
            listOf("kira::Str(", "kira::List<std::int32_t>(", "Tag(", "kira::Fn<void()>(", "kira::Fn<std::int32_t()>(").forEach { copy ->
                assertFalse(b.contains(copy), "$fn lends, and holds no copy $copy:\n$b")
            }
        }
    }

    @TestFactory
    fun eachRowPrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("policy [${tc.id}]") {
                val old = "old-name-long-enough-for-the-heap-00000000:1"
                val rows = listOf(
                    "policy::copyStr() == 47" to "a global Str is copied at the call: the hook's new text is not seen",
                    "policy::copyList() == 6" to "a global List is copied at the call: the hook replaces it at the first step (p3 b)",
                    "policy::copyGeneric() == 47" to "a const T& parameter at Str is copied at the call",
                    "policy::copyReceiver() == \"$old\"" to "a value receiver is copied: describe sees the old gt (p5)",
                    "policy::copyCallee() == 47" to "an Fx value's callee is copied: the hook replacing it frees nothing it runs",
                    "policy::copyTernary(true) == 6" to "a ternary of two places is copied",
                    "policy::copyRange() == 6" to "a range the body may replace is iterated as a copy",
                    "policy::lendLocal() == 47" to "a local is lent: no hook names it",
                    "policy::lendParamRow() == 6" to "a by-value parameter is lent inside, copied by its caller (I)",
                    "policy::lendThis() == \"$old\"" to "a value this is lent inside, copied by its caller (I)",
                    "policy::lendRangeLocal() == 6" to "a local range is lent",
                    "policy::lendConfined() == 47" to "a CONFINED callee is lent a global",
                    "policy::lendReceiverConfined() == \"$old\"" to "a CONFINED method is lent a global receiver",
                    "policy::lendHole() == \"old-text-long-enough-to-live-on-the-heap-000000!\"" to "kira::cat is lent a global",
                    "policy::lendRangeReads() == 6" to "a range whose body only reads is lent",
                    "policy::ownerTernary(false) == 9" to "a view of a ternary is a view of its copy (O3)",
                )
                val driver = buildString {
                    append("#include \"").append(policy.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    policy::arm();\n    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    /**
     * Round 3's significant w2-3 findings (sc-round3.json), each a row that now prints Kira's
     * value: #0 a lambda's captures are read where the lambda is written (R-PURE ranks a
     * capturing lambda READS); #1 a nested element whose index grows the outer List is located
     * after the index (P1); #2 a `Str` receiver of `[]` is read before its index runs (P2, OQ-1);
     * #3 a binding that names no `{self}` still evaluates its receiver.
     */
    private val round3 = Module(
        "copy:round3",
        """
        mut gs: Str = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
        mut gll: List<List<Int32>> = List<List<Int32>> { }
        mut gls: List<Str> = List<Str> { }
        mut junk: List<List<Int32>> = List<List<Int32>> { }
        mut keep: Maybe<Fx<Tuple0, Int32>> = null
        mut ticks: Int32 = 0

        fx apply: (f: Fx<Tuple0, Int32>, k: Int32) Int32 {
            return f() * 100 + k
        }

        fx applyKeep: (f: Fx<Tuple0, Int32>, k: Int32) Int32 {
            keep = f
            return f() * 100 + k
        }

        fx applyS: (f: Fx<Tuple0, Str>, k: Int32) Str {
            return "${'$'}{f()}:${'$'}{k}"
        }

        fx applyAfter: (k: Int32, f: Fx<Tuple0, Int32>) Int32 {
            return f() * 100 + k
        }

        fx inc: (mut v: Int32) Int32 {
            v += 10
            return 1
        }

        fx change: (mut s: Str) Int32 {
            s = "changedchangedchangedchangedchangedchangedchanged"
            return 2
        }

        pub struct Cell {
            pub v: Int32 = 5

            pub mut fx bump: () Int32 {
                v += 10
                return 3
            }

            pub mut fx probe: () Int32 {
                return apply(fx () Int32 { return v }, bump())
            }
        }

        pub fx captureRead: () Int32 {
            mut x: Int32 = 5
            return apply(fx () Int32 { return x }, inc(mut x))
        }

        pub fx captureKept: () Int32 {
            mut y: Int32 = 5
            return applyKeep(fx () Int32 { return y }, inc(mut y))
        }

        pub fx captureStr: () Str {
            mut s: Str = "old"
            return applyS(fx () Str { return s }, change(mut s))
        }

        pub fx captureThis: () Int32 {
            mut c: Cell = Cell { }
            return c.probe()
        }

        pub fx captureAfter: () Int32 {
            mut a: Int32 = 5
            return applyAfter(inc(mut a), fx () Int32 { return a })
        }

        fx churn: () Void {
            mut i: Int32 = 0
            while i < 200 {
                junk.add(List<Int32> { values = [-1, -2] })
                i += 1
            }
        }

        fx growGll: () Size {
            mut i: Int32 = 0
            while i < 64 {
                gll.add(List<Int32> { values = [i] })
                i += 1
            }
            gll.set(0, List<Int32> { values = [77, 88, 99, 111, 222, 333, 444, 555] })
            churn()
            return 1
        }

        fx growGls: () Size {
            mut i: Int32 = 0
            while i < 64 {
                gls.add("padpadpadpadpadpadpadpadpadpadpadpadpadpadpadpadpad")
                i += 1
            }
            gls.set(0, "ZYXWVUTSRQPONMLKJIHGFEDCBAZYXWVUTSRQPONMLKJIHGFEDCBAZYXWVUTSRQPONMLKJIHGFEDCBA")
            churn()
            return 1
        }

        fx chIdx: () Size {
            gs = "ZYXWVUTSRQPONMLKJIHGFEDCBAZYXWVUTSRQPONMLKJIHGFEDCBA"
            return 1
        }

        fx resetLists: () Void {
            gll = List<List<Int32>> { }
            gll.add(List<Int32> { values = [10, 20] })
            gls = List<Str> { }
            gls.add("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz")
        }

        pub fx nestedRead: () Int32 {
            resetLists()
            return gll[0][growGll()]
        }

        pub fx nestedWrite: () Int32 {
            resetLists()
            gll[0][growGll()] = 5
            return gll[0][0] * 10000 + gll[0][1] * 100 + gll[0][2]
        }

        pub fx nestedCompound: () Int32 {
            resetLists()
            gll[0][growGll()] += 1
            return gll[0][0] * 10000 + gll[0][1] * 100 + gll[0][2]
        }

        pub fx strIndex: () Char {
            gs = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            return gs[chIdx()]
        }

        pub fx strElementIndex: () Char {
            resetLists()
            return gls[0][growGls()]
        }

        fx mkT: () Tuple2<Int32, Int32> {
            ticks += 1
            return Tuple2<Int32, Int32> { ticks, ticks }
        }

        pub fx bindingDrop: () Int32 {
            ticks = 0
            n: Int32 = mkT().size()
            return n * 10 + ticks
        }

        pub fx bindingDropTwice: () Int32 {
            ticks = 0
            s: Int32 = mkT().size() + mkT().size()
            return s * 10 + ticks
        }
        """,
    )

    private val round3Tree by lazy { CppExprTestSupport.emit("copy-round3", listOf(round3)) }

    @Test
    fun roundThreesRowsAreOrderedWhereKiraReadsThem() {
        val source = round3Tree.source(round3)
        // #0: the lambda is copied where Kira writes it, before the sibling writes its capture.
        assertTrue(Regex("const kira::Fn<std::int32_t\\(\\)> t0_ = \\[x\\]\\(\\) -> std::int32_t\\n\\s*\\{\\n\\s*return x;\\n\\s*\\};\\n\\s*const std::int32_t t1_ = inc\\(x\\);").containsMatchIn(source), source)
        // #1: the index runs before the inner element is located (P1); `gll.get(0).get(growGll())`
        // is the order rule's (KI-17), and CppHoisterTest's lentIndexArg pins that spelling.
        assertTrue(source.contains("const kira::Size t0_ = growGll();\n          return kira::at(kira::at(gll, 0), t0_);"), source)
        // #2: the Str is copied before its index runs (P2, OQ-1).
        assertTrue(source.contains("const kira::Str t0_ = gs;\n          const kira::Size t1_ = chIdx();\n          return kira::str::at(t0_, t1_);"), source)
        // #3: the receiver a binding leaves out is still evaluated.
        assertTrue(source.contains("(static_cast<void>(mkT()), static_cast<std::int32_t>(2))"), source)
    }

    @TestFactory
    fun roundThreesRowsPrintKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("round3 [${tc.id}]") {
                val rows = listOf(
                    "round3::captureRead() == 501" to "#0 a lambda captures x before inc(mut x): 5 * 100 + 1",
                    "round3::captureKept() == 501" to "#0 the same through an escaping Fx",
                    "round3::captureStr() == \"old:2\"" to "#0 a Str capture",
                    "round3::captureThis() == 503" to "#0 a struct field captured in a mut fx before bump()",
                    "round3::captureAfter() == 1501" to "#0 the writer on the left runs first: 15 * 100 + 1",
                    "round3::nestedRead() == 88" to "#1 gll[0] is read when the element is, after the index (Q4)",
                    "round3::nestedWrite() == 770599" to "#1 the target is located after the index: 77 5 99",
                    "round3::nestedCompound() == 778999" to "#1 a compound target the same: 77 89 99",
                    "round3::strIndex() == 'b'" to "#2 a Str is read before its index runs (OQ-1)",
                    "round3::strElementIndex() == 'b'" to "#2 a Str element the same",
                    "round3::bindingDrop() == 21" to "#3 mkT() runs once: size 2, ticks 1",
                    "round3::bindingDropTwice() == 42" to "#3 twice: 2 + 2, ticks 2",
                )
                val driver = buildString {
                    append("#include \"").append(round3.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(round3Tree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }
}
