package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.backend.codegen.cpp.TypedCppModuleEmitter
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
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
            // Rule M (round 5): a global handed an Fx that is not CONFINED is refused as a
            // mut receiver, so each list is built in a local and stored back.
            mut fs: List<Fx<Tuple0, Int32>> = List<Fx<Tuple0, Int32>> { }
            fs.add(fx () Int32 {
                runHooks()
                return tag.length() as Int32
            })
            gfs = fs
            mut hs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { }
            hs.add(fx () Void {
                gs = "new"
                gl = [100]
                gt = Tag { name = "new", n = 2 }
                gfs = List<Fx<Tuple0, Int32>> { }
            })
            hooks = hs
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
        Triple("copyCallee", "return (kira::Fn<std::int32_t()>(kira::at(gfs, 0)))();", true),
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
     * Round 4's significant w2-3 findings (sc-round4.json): an assignment's value is a use.
     * `operator=(const T&)` binds a reference to the value and writes the target while it reads
     * it, so a value inside the target is freed or overwritten as it is read (#0: `t =
     * t.kids[0]`, gcc exited 3 and MSVC ASan reported a container-overflow; #1: `t.kids[0] =
     * t`, every compiler printed `1 one 1 1 2` for Kira's `1 one 1 2 2`). The rows are the
     * verifier's probes, a3b, c2, c5, c6, c8, c12, c14, c15, c19, c20, a3c1, a3c3 and a3c6:
     * each value is copied unless it is a prvalue (W1) or a PRIVATE place under another root
     * (W2). `shape` prints a tree as `v name-length kids` and then each kid's `v/kids`.
     */
    private val assign = Module(
        "copy:assign",
        """
        pub struct Tree {
            pub v: Int32
            pub name: Str
            pub kids: List<Tree>
        }

        fx leaf: (v: Int32, n: Str) Tree {
            return Tree { v = v, name = n, kids = [] }
        }

        fx two: () Tree {
            return Tree { v = 2, name = "two-long-name-that-lives-on-the-heap-000000", kids = [leaf(7, "seven-long-name-that-lives-on-the-heap-0000"), leaf(8, "eight-long-name-that-lives-on-the-heap-0000")] }
        }

        // One kid, two(), with room for more, so a vector assign takes the no-reallocation path.
        fx roomy: () Tree {
            mut t: Tree = Tree { v = 1, name = "one", kids = [] }
            t.kids.add(two())
            t.kids.add(leaf(9, "nine"))
            t.kids.add(leaf(10, "ten"))
            x: Tree = t.kids.removeAt(2)
            y: Tree = t.kids.removeAt(1)
            return t
        }

        fx shape: (t: Tree) Str {
            mut out: Str = "${'$'}{t.v} ${'$'}{t.name.length()} ${'$'}{t.kids.size()}"
            for k: Tree in t.kids {
                out += " ${'$'}{k.v}/${'$'}{k.kids.size()}"
            }
            return out
        }

        fx flag: () Bool {
            return true
        }

        mut gt: Tree = Tree { v = 0, name = "g", kids = [] }

        pub struct Holder {
            pub t: Tree

            pub mut fx descend: () Void {
                t = t.kids[0]
            }
        }

        pub fx rebindLocal: () Str {
            mut t: Tree = roomy()
            t = t.kids[0]
            return shape(t)
        }

        pub fx selfIntoElement: () Str {
            mut t: Tree = roomy()
            t.kids[0] = t
            return "${'$'}{shape(t)}|${'$'}{shape(t.kids[0])}|${'$'}{shape(t.kids[0].kids[0])}"
        }

        pub fx rebindTernary: () Str {
            mut t: Tree = roomy()
            t = if flag() { t.kids[0] } else { t }
            return shape(t)
        }

        fx descend: (mut t: Tree) Void {
            t = t.kids[0]
        }

        pub fx rebindMutParam: () Str {
            mut t: Tree = roomy()
            descend(mut t)
            return shape(t)
        }

        pub fx fieldFromElement: () Str {
            mut t: Tree = roomy()
            t.kids = t.kids[0].kids
            return shape(t)
        }

        pub fx elementIntoItsElement: () Str {
            mut t: Tree = roomy()
            t.kids[0].kids[0] = t.kids[0]
            return "${'$'}{shape(t.kids[0])}|${'$'}{shape(t.kids[0].kids[0])}"
        }

        fx descendGlobal: () Void {
            gt = gt.kids[0]
        }

        pub fx rebindGlobal: () Str {
            gt = roomy()
            descendGlobal()
            return shape(gt)
        }

        pub fx elementFromSibling: () Str {
            mut xs: List<Str> = ["a-long-string-that-lives-on-the-heap-0000000", "b"]
            xs[0] = xs[1]
            xs[1] = xs[0] + xs[0]
            return "${'$'}{xs[0]} ${'$'}{xs[1]}"
        }

        pub fx twoNamesOfOneRef: () Str {
            r1: Ref<Tree> = Ref<Tree> { value = roomy() }
            r2: Ref<Tree> = r1
            r1.value = r2.value.kids[0]
            return shape(r2.value)
        }

        pub fx listElements: () Str {
            mut ts: List<Tree> = [roomy(), leaf(5, "five")]
            ts[1] = ts[0].kids[0]
            ts[0] = ts[0].kids[0]
            return "${'$'}{shape(ts[0])}|${'$'}{shape(ts[1])}"
        }

        pub fx maybePayload: () Str {
            mut m: Maybe<Tree> = roomy()
            m = m.unwrap().kids[0]
            return shape(m.unwrap())
        }

        pub fx valueThisInMutFx: () Str {
            mut h: Holder = Holder { t = roomy() }
            h.descend()
            return shape(h.t)
        }

        pub fx nestedElement: () Str {
            mut t: Tree = leaf(0, "zero")
            t.kids.add(roomy())
            t.kids[0] = t.kids[0].kids[0]
            return shape(t.kids[0])
        }

        pub fx lendLocal: () Str {
            u: Tree = roomy()
            mut t: Tree = leaf(0, "zero")
            t = u
            return shape(t)
        }

        pub fx lendParam: (u: Tree) Str {
            mut t: Tree = leaf(0, "zero")
            t = u
            return shape(t)
        }

        pub fx lendParamRow: () Str {
            return lendParam(roomy())
        }
        """,
    )

    private val assignTree by lazy { CppExprTestSupport.emit("copy-assign", listOf(assign)) }

    @Test
    fun anAssignmentsValueIsCopiedUnlessItIsATemporaryOrAPrivatePlaceUnderAnotherRoot() {
        val source = assignTree.source(assign)
        listOf(
            // #0: the value lies under the root the assignment writes, or is no PRIVATE place.
            "t = Tree(kira::at(t.kids, 0));",
            "t = Tree(flag() ? kira::at(t.kids, 0) : t);",
            "t.kids = kira::List<Tree>(kira::at(t.kids, 0).kids);",
            "gt = Tree(kira::at(gt.kids, 0));",
            "r1->value = Tree(kira::at(r2->value.kids, 0));",
            // A WrapSome is no temporary for std::optional's operator=(U&&).
            "m = Tree(kira::at(kira::unwrap(m).kids, 0));",
            // #1: an element written from its own container's root.
            "kira::at(t.kids, 0) = Tree(t);",
            "kira::at(kira::at(t.kids, 0).kids, 0) = Tree(kira::at(t.kids, 0));",
            "kira::at(xs, 0) = kira::Str(kira::at(xs, 1));",
            "kira::at(ts, 0) = Tree(kira::at(kira::at(ts, 0).kids, 0));",
            "kira::at(t.kids, 0) = Tree(kira::at(kira::at(t.kids, 0).kids, 0));",
            // W1: a prvalue is as is.
            "kira::at(xs, 1) = kira::at(xs, 0) + kira::at(xs, 0);",
            "gt = roomy();",
        ).forEach { assertTrue(source.contains(it), "$it:\n$source") }
        // W2: a PRIVATE place under another root is lent.
        listOf("lendLocal", "lendParam").forEach { fn ->
            val b = Regex("\\n  [^\\n ][^\\n]*[ :]$fn\\([^\\n]*\\)[^\\n]*\\n  \\{\\n").find(source)?.let { source.substring(it.range.last, source.indexOf("\n  }\n", it.range.last)) }
            assertTrue(b != null && b.contains("t = u;") && !b.contains("Tree(u)"), "$fn lends u:\n$b")
        }
    }

    @TestFactory
    fun anAssignmentsValuePrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("assign [${tc.id}]") {
                val kid = "2 43 2 7/0 8/0"
                val rows = listOf(
                    "assign::rebindLocal() == \"$kid\"" to "a3b: t = t.kids[0] reads the kid first",
                    "assign::selfIntoElement() == \"1 3 1 1/1|1 3 1 2/2|$kid\"" to "c2: t.kids[0] = t stores the old t",
                    "assign::rebindTernary() == \"$kid\"" to "c5: a ternary of the kid",
                    "assign::rebindMutParam() == \"$kid\"" to "c6: through a mut parameter",
                    "assign::fieldFromElement() == \"1 3 2 7/0 8/0\"" to "c8: t.kids = t.kids[0].kids",
                    "assign::elementIntoItsElement() == \"2 43 2 2/2 8/0|$kid\"" to "c12: t.kids[0].kids[0] = t.kids[0]",
                    "assign::rebindGlobal() == \"$kid\"" to "c14: a global rebound in a function",
                    "assign::elementFromSibling() == \"b bb\"" to "c15: xs[0] = xs[1]",
                    "assign::twoNamesOfOneRef() == \"$kid\"" to "c19: two names of one Ref",
                    "assign::listElements() == \"$kid|$kid\"" to "c20: ts[0] = ts[0].kids[0]",
                    "assign::maybePayload() == \"$kid\"" to "c20: m = m.unwrap().kids[0]",
                    "assign::valueThisInMutFx() == \"$kid\"" to "a3c3: the value this of a mut fx",
                    "assign::nestedElement() == \"$kid\"" to "a3c6: t.kids[0] = t.kids[0].kids[0]",
                    "assign::lendLocal() == \"1 3 1 2/2\"" to "a PRIVATE local under another root is lent",
                    "assign::lendParamRow() == \"1 3 1 2/2\"" to "a by-value parameter is lent",
                )
                val driver = buildString {
                    append("#include \"").append(assign.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(assignTree, driver, tc) ?: return@dynamicTest
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

    /**
     * Round 5b's `[owner w2-3-emit-exprs]` finding (sc-round5.json, the verifier's x/t5): a copied
     * `Fx` callee used as a statement, `kira::Fn<void()>(f)();`, is a C++ declaration of a function
     * `f`, so the call never ran (gcc and clang printed 29, 29 for Kira's 30, 31; MSVC refused the
     * build with C4930). With an argument, `kira::Fn<void(std::int32_t)>(g)(3);` declares a
     * variable `g`. The copy is parenthesized wherever it is the callee. The rows are x/t5's forms
     * this branch lowers: a struct `mut fx` calling its own `Fx` field (implicit `this`, the
     * caller's `S&`, so the field is copied) and a hook of a global `List` called as a statement.
     * x/t5's class `initially` and class method are W2.4's classes, checked on the trial.
     */
    private val fnCall = Module(
        "copy:fncall",
        """
        mut gi: Int32 = 29
        mut hooks: List<Fx<Tuple0, Void>> = []
        mut adds: List<Fx<Tuple1<Int32>, Void>> = []

        pub struct Runner {
            pub f: Fx<Tuple0, Void>
            pub g: Fx<Tuple1<Int32>, Void>

            pub mut fx run: () Void {
                f()
            }

            pub mut fx runWith: () Void {
                g(3)
            }

            pub fx runPlain: () Void {
                f()
            }
        }

        fx runner: () Runner {
            return Runner { f = fx () Void {
                gi = gi + 1
            }, g = fx (n: Int32) Void {
                gi = gi + n * 100
            } }
        }

        pub fx implicitThis: () Int32 {
            gi = 29
            mut r: Runner = runner()
            r.run()
            r.run()
            return gi
        }

        pub fx implicitThisArg: () Int32 {
            gi = 29
            mut r: Runner = runner()
            r.runWith()
            return gi
        }

        pub fx lentThis: () Int32 {
            gi = 29
            r: Runner = runner()
            r.runPlain()
            return gi
        }

        pub fx statementHook: () Int32 {
            gi = 29
            mut hs: List<Fx<Tuple0, Void>> = []
            hs.add(fx () Void {
                gi = gi + 100
            })
            hooks = hs
            hooks[0]()
            return gi
        }

        pub fx statementHookArg: () Int32 {
            gi = 29
            mut xs: List<Fx<Tuple1<Int32>, Void>> = []
            xs.add(fx (n: Int32) Void {
                gi = gi + n
            })
            adds = xs
            adds[0](7)
            return gi
        }
        """,
    )

    private val fnCallTree by lazy { CppExprTestSupport.emit("copy-fncall", listOf(fnCall)) }

    @Test
    fun aCopiedFxCalleeIsParenthesizedSoAStatementIsNeverADeclaration() {
        val source = fnCallTree.source(fnCall)
        listOf(
            "(kira::Fn<void()>(f))();",
            "(kira::Fn<void(std::int32_t)>(g))(3);",
            "(kira::Fn<void()>(kira::at(hooks, 0)))();",
            "(kira::Fn<void(std::int32_t)>(kira::at(adds, 0)))(7);",
        ).forEach { assertTrue(source.contains(it), "$it:\n$source") }
        // The declaration spellings are gone; a PRIVATE callee (a value this outside a mut fx) is lent.
        assertFalse(Regex("\\n\\s*kira::Fn<[^\\n]*>\\((f|g|kira::at\\(\\w+, 0\\))\\)\\(").containsMatchIn(source), source)
        assertTrue(Regex("void Runner::runPlain\\(\\) const\\n\\s*\\{\\n\\s*f\\(\\);").containsMatchIn(source), source)
    }

    @TestFactory
    fun aCopiedFxCalleeUsedAsAStatementRunsOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("fncall [${tc.id}]") {
                val rows = listOf(
                    "fncall::implicitThis() == 31" to "x/t5: a mut fx calls its own Fx field twice (29 + 1 + 1)",
                    "fncall::implicitThisArg() == 329" to "the same with an argument (29 + 3 * 100)",
                    "fncall::lentThis() == 30" to "a lent callee (29 + 1)",
                    "fncall::statementHook() == 129" to "x/t5: hooks[0]() as a statement (29 + 100)",
                    "fncall::statementHookArg() == 36" to "adds[0](7) as a statement (29 + 7)",
                )
                val driver = buildString {
                    append("#include \"").append(fnCall.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(fnCallTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    /**
     * Round 5b's w2-3 finding (sc-round5.json, the verifier's f2, f3, f5, f6): C++'s `operator=`
     * drops the old value partway through the write, so an IMPURE `finally` that drop runs freed
     * the target's storage (f2 `gl[0] = v`, f5 `gll[0] = v`: MSVC ASan heap-use-after-free) or
     * rewrote it half-written (f3: `v 99` with the new name). Where the old value's drop may run
     * one (`CppCopyPolicy.dropsOnWrite`), the write is `kira::replace(place) = value`: stored
     * whole, then the old value dropped. A `finally` is a class's, and this branch lowers no
     * class, so the test stands in for W2.5's Drops: every type that holds `Rec` (or a type
     * parameter) is taken to drop one. The runs show that each spelling stores Kira's value;
     * kira/cpp/tests/rt_test.cxx's testReplace runs f2 and f3 with a real destructor as the
     * `finally` and checks it sees the whole new value, and the trial runs the probes themselves.
     */
    private val replace = Module(
        "copy:replace",
        """
        pub struct Rec {
            pub v: Int32
            pub name: Str
            pub kids: List<Int32>
        }

        fx rec: (v: Int32, n: Str) Rec {
            return Rec { v = v, name = n, kids = [v] }
        }

        mut gl: List<Rec> = []
        mut gt: Rec = Rec { v = 0, name = "zero", kids = [] }
        mut gll: List<List<Rec>> = []
        mut gm: Maybe<Rec> = null
        mut mm: Map<Int32, Rec> = Map<Int32, Rec> { }
        mut gi: Int32 = 0
        mut gn: List<Int32> = [1, 2]

        pub fx element: () Str {
            gl = [rec(1, "one")]
            gl[0] = rec(2, "two")
            return "${'$'}{gl.size()} ${'$'}{gl[0].v} ${'$'}{gl[0].name}"
        }

        pub fx whole: () Str {
            gt = rec(2, "two")
            return "${'$'}{gt.v} ${'$'}{gt.name} ${'$'}{gt.kids.size()}"
        }

        pub fx nested: () Str {
            gll = [[rec(1, "a")], [rec(2, "b")]]
            gll[0] = [rec(3, "c"), rec(4, "d")]
            return "${'$'}{gll.size()} ${'$'}{gll[0].size()} ${'$'}{gll[0][1].v}"
        }

        pub fx maybe: () Str {
            gm = rec(1, "one")
            gm = rec(2, "two")
            s: Str = "${'$'}{gm.unwrap().v}"
            gm = null
            return "${'$'}{s} ${'$'}{gm.isSome()}"
        }

        pub fx mapValue: () Str {
            mm[1] = rec(1, "one")
            mm[1] = rec(3, "three")
            return "${'$'}{mm.size()} ${'$'}{mm.get(1).unwrap().v}"
        }

        pub fx listSet: () Str {
            mut xs: List<Rec> = [rec(1, "one")]
            xs.set(0, rec(2, "two"))
            return "${'$'}{xs[0].v} ${'$'}{xs[0].name}"
        }

        pub fx arrSet: () Str {
            mut a: Arr<Rec, 2> = [rec(1, "a"), rec(2, "b")]
            a.set(1, rec(5, "e"))
            return "${'$'}{a[1].v} ${'$'}{a[1].name}"
        }

        fx setView: (m: MutView<Rec>) Int32 {
            m.set(0, rec(7, "g"))
            m[1] = rec(8, "h")
            return 0
        }

        pub fx viewSet: () Str {
            mut xs: List<Rec> = [rec(1, "one"), rec(2, "two")]
            z: Int32 = setView(xs.from(0))
            return "${'$'}{xs[0].v} ${'$'}{xs[1].name}"
        }

        fx setFirst<T>: (mut xs: List<T>, v: T) Void {
            xs[0] = v
        }

        pub fx bools: () Str {
            mut bs: List<Bool> = [false, false]
            setFirst<Bool>(mut bs, true)
            return "${'$'}{bs[0]} ${'$'}{bs[1]}"
        }

        pub fx plain: () Str {
            gi = 3
            gn[0] = 5
            return "${'$'}{gi} ${'$'}{gn[0]}"
        }
        """,
    )

    /** W2.5's Drops, as this test takes it: a type that holds `Rec` or a type parameter may drop an IMPURE `finally`. */
    private fun holdsRec(t: KType?): Boolean = when (t) {
        null, is KType.Param -> true
        is KType.Nominal -> t.sym.name == "Rec" || t.typeArgs().any { holdsRec(it) }
        else -> false
    }

    private val replaceTree by lazy {
        CppExprTestSupport.emit("copy-replace", listOf(replace), emitterFactory = { unit, options ->
            CppModuleEmitterFactory.create(unit, options).also { e ->
                (e as? TypedCppModuleEmitter)?.program?.model?.dropsImpureFinally = ::holdsRec
            }
        })
    }

    @Test
    fun aWriteOverAValueWhoseDropMayRunAFinallyStoresFirstAndDropsAfter() {
        val source = replaceTree.source(replace)
        listOf(
            // f2, f3, f5, f6: an element, a global, a nested element, a Maybe (and its null).
            "kira::replace(kira::at(gl, 0)) = rec(2, \"two\");",
            "kira::replace(gt) = rec(2, \"two\");",
            "kira::replace(kira::at(gll, 0)) = kira::List<Rec>{rec(3, \"c\"), rec(4, \"d\")};",
            "kira::replace(gm) = rec(2, \"two\");",
            "kira::replace(gm) = kira::none;",
            // m[k] = v, and the bindings PLACE = {n}: List.set, Arr.set, MutView.set; m[i] = v through a MutView.
            "kira::replace(mm[1]) = rec(3, \"three\");",
            "kira::replace(kira::at(xs, 0)) = rec(2, \"two\");",
            "kira::replace(kira::at(a, 1)) = rec(5, \"e\");",
            "kira::replace(m[0]) = rec(7, \"g\");",
            "kira::replace(kira::at(m, 1)) = rec(8, \"h\");",
            // A type parameter may be anything; at Bool, kira::at gives a std::vector<bool> proxy.
            "kira::replace(kira::at(xs, 0)) = v;",
            // A value whose drop runs no finally is stored in place.
            "gi = 3;",
            "kira::at(gn, 0) = 5;",
        ).forEach { assertTrue(source.contains(it), "$it:\n$source") }
    }

    @TestFactory
    fun aWriteThatStoresFirstPrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("replace [${tc.id}]") {
                val rows = listOf(
                    "replace::element() == \"1 2 two\"" to "f2: gl[0] = v",
                    "replace::whole() == \"2 two 1\"" to "f3: gt = v",
                    "replace::nested() == \"2 2 4\"" to "f5: gll[0] = v",
                    "replace::maybe() == \"2 false\"" to "f6: a Maybe, then null",
                    "replace::mapValue() == \"1 3\"" to "m[k] = v over a key",
                    "replace::listSet() == \"2 two\"" to "List.set",
                    "replace::arrSet() == \"5 e\"" to "Arr.set",
                    "replace::viewSet() == \"7 h\"" to "MutView.set and m[i] = v",
                    "replace::bools() == \"true false\"" to "a type parameter at Bool (a std::vector<bool> proxy)",
                    "replace::plain() == \"3 5\"" to "a store in place",
                )
                val driver = buildString {
                    append("#include \"").append(replace.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(replaceTree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    /**
     * Round 6's w2-3 finding (sc-round6.json, the verifier's v23r6x/atk hx1-hx8 and k_c1): a write
     * over a class or trait handle, or a `Maybe` of one, was left to `std::shared_ptr`'s own
     * assignment, and libstdc++'s copy-assignment releases the old count before it stores the new
     * one. The old object's IMPURE `finally` met the new pointer beside its own dying count: on g++
     * hx1's Res 1 was destroyed twice (`count 2`), hx2 stored a control block into a freed buffer,
     * and hx4's Res 3 never ran its `finally`. Every such write is now `kira::replace(place) =
     * value` (`CppCopyPolicy.dropsOnWrite`) on every compiler: stored whole, then the old object
     * dropped. The probes are the verifier's, less the module line and `fx main`, with `run`
     * public; hx1c (a prvalue) is the verifier's control. This branch lowers no class, so here
     * the text test emits them through [ClassBodies] and the runs are skipped;
     * kira/cpp/tests/rt_test.cxx's testReplaceHandle runs hx1, hx2 and k_c1 with a real
     * destructor as the `finally`. Where W2.4's classes part is merged, the real part emits the
     * probes and both tests check them, the runs on gcc, clang and msvc.
     */
    private val handleProbes: List<Module> = listOf(
        Module(
            "handle:hx1",
            """
            // hx1: gm = b, with b a local handle (PRIVATE, so the value is lent and C++ copy-assigns the
            // std::shared_ptr; dropsOnWrite leaves a Maybe of a class to shared_ptr's own assignment).
            // The old object's IMPURE finally copies the handle it finds in gm into a local and reads it.
            // Kira: the write stores b, then Res 1 drops once: its finally sees Res 2.
            // Kira prints: fin1 sees 2, count 1, after 2, end, fin2

            mut gm: Maybe<Res> = null
            mut gc: Int32 = 0

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gc = gc + 1
                        if gc == 1 {
                            x: Res = gm.unwrap()
                            trace("fin1 sees ${'$'}{x.n}")
                        }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            pub fx run: () Void {
                gm = Res { n = 1 }
                b: Res = Res { n = 2 }
                gm = b
                trace("count ${'$'}{gc}")
                trace("after ${'$'}{gm.unwrap().n}")
                gm = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx1c",
            """
            // hx1c (control for hx1): gm = Res { n = 2 }, a prvalue (C++ move-assigns, which swaps). Was: gm = b, with b a local handle (PRIVATE, so the value is lent and C++ copy-assigns the
            // std::shared_ptr; dropsOnWrite leaves a Maybe of a class to shared_ptr's own assignment).
            // The old object's IMPURE finally copies the handle it finds in gm into a local and reads it.
            // Kira: the write stores b, then Res 1 drops once: its finally sees Res 2.
            // Kira prints: fin1 sees 2, count 1, after 2, fin2, end

            mut gm: Maybe<Res> = null
            mut gc: Int32 = 0

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gc = gc + 1
                        if gc == 1 {
                            x: Res = gm.unwrap()
                            trace("fin1 sees ${'$'}{x.n}")
                        }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            pub fx run: () Void {
                gm = Res { n = 1 }
                gm = Res { n = 2 }
                trace("count ${'$'}{gc}")
                trace("after ${'$'}{gm.unwrap().n}")
                gm = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx2",
            """
            // hx2: xs[0] = b over a global List of class handles, b a by-value parameter (PRIVATE, lent).
            // The old element's IMPURE finally replaces the global List (freeing its buffer) and then
            // allocates a List<Int64> of the same byte size.
            // Kira: the element takes b, then the old element drops: the finally's writes come last.
            // Kira prints: fin1, size 0, gk 11 22, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gl = []
                        gk = [11, 22]
                        trace("fin1")
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            mut gl: List<Res> = []
            mut gk: List<Int64> = []

            fx put: (b: Res) Void {
                gl[0] = b
            }

            pub fx run: () Void {
                gl = [Res { n = 1 }]
                put(Res { n = 2 })
                trace("size ${'$'}{gl.size()}")
                trace("gk ${'$'}{gk[0]} ${'$'}{gk[1]}")
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx3",
            """
            // hx3: gmap[1] = b over a global Map of class handles, b a local handle (lent). The old value's
            // IMPURE finally replaces the global Map (freeing its storage) and then allocates Lists of the
            // sizes the freed blocks had.
            // Kira: the entry takes b, then the old value drops: the finally's writes come last.
            // Kira prints: fin1, size 0, gk 11 22 33, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gmap = Map<Int32, Res> { }
                        gk = [11, 22, 33]
                        gk2 = [44, 55]
                        trace("fin1")
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            mut gmap: Map<Int32, Res> = Map<Int32, Res> { }
            mut gk: List<Int64> = []
            mut gk2: List<Int64> = []

            pub fx run: () Void {
                gmap[1] = Res { n = 1 }
                b: Res = Res { n = 2 }
                gmap[1] = b
                trace("size ${'$'}{gmap.size()}")
                trace("gk ${'$'}{gk[0]} ${'$'}{gk[1]} ${'$'}{gk[2]}")
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx4",
            """
            // hx4: h.child = b, a class field through a local handle (the object is held by h), with b a
            // local handle (lent). The old child's IMPURE finally rewrites the same field through a global
            // alias of h.
            // Kira: the field takes b, then the old child drops; its finally sees b (2) and its own write
            // (Res 3) is last. Res 3 drops when the field is cleared, Res 2 when b goes.
            // Kira prints: fin1 sees 2, child 3, fin3, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        trace("fin1 sees ${'$'}{gh.unwrap().child.unwrap().n}")
                        gh.unwrap().child = Res { n = 3 }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                    if n == 3 {
                        trace("fin3")
                    }
                }
            }

            pub class Holder {
                pub mut child: Maybe<Res> = null
            }

            mut gh: Maybe<Holder> = null

            pub fx run: () Void {
                h: Holder = Holder { }
                gh = h
                h.child = Res { n = 1 }
                b: Res = Res { n = 2 }
                h.child = b
                trace("child ${'$'}{h.child.unwrap().n}")
                h.child = null
                gh = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx5",
            """
            // hx5: a trait-typed global assigned a local class handle (an Upcast, C++'s converting
            // shared_ptr assignment). The old object's IMPURE finally copies what the global holds into a
            // local and asks it for its id.
            // Kira: the global takes b, then Res 1 drops once: its finally sees Res 2.
            // Kira prints: fin1 sees 2, count 1, after 2, end, fin2

            pub trait Tagged {
                pub fx id: () Int32
            }

            pub class Res: Tagged {
                pub n: Int32 = 0

                pub fx id: () Int32 {
                    return n
                }

                finally {
                    if n == 1 {
                        gc = gc + 1
                        if gc == 1 {
                            x: Tagged = gt.unwrap()
                            trace("fin1 sees ${'$'}{x.id()}")
                        }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            mut gt: Maybe<Tagged> = null
            mut gc: Int32 = 0

            pub fx run: () Void {
                gt = Res { n = 1 }
                b: Res = Res { n = 2 }
                gt = b
                trace("count ${'$'}{gc}")
                trace("after ${'$'}{gt.unwrap().id()}")
                gt = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx7",
            """
            // hx7: the binding List.set(i, v) on a STABLE receiver (this.items in a class mut fx) with a
            // class-handle element, v a by-value parameter (lent). The old element's IMPURE finally copies
            // the element it finds at items[0] through a global alias.
            // Kira: the element takes v, then Res 1 drops once: its finally sees Res 2.
            // Kira prints: fin1 sees 2, count 1, after 2, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gc = gc + 1
                        if gc == 1 {
                            x: Res = gh.unwrap().items[0]
                            trace("fin1 sees ${'$'}{x.n}")
                        }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            pub class Holder {
                pub mut items: List<Res> = []

                pub mut fx swapIn: (v: Res) Void {
                    items.set(0, v)
                }
            }

            mut gh: Maybe<Holder> = null
            mut gc: Int32 = 0

            pub fx run: () Void {
                h: Holder = Holder { }
                gh = h
                h.items = [Res { n = 1 }]
                b: Res = Res { n = 2 }
                h.swapIn(b)
                trace("count ${'$'}{gc}")
                trace("after ${'$'}{h.items[0].n}")
                h.items = []
                gh = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:hx8",
            """
            // hx8: a write through a mut parameter, `slot = v`, where the caller passes the STABLE field of
            // an object held by a local handle (mut h.child) and v a by-value parameter (lent). The old
            // child's IMPURE finally copies the child it finds in the same field through a global alias.
            // Kira: the field takes v, then Res 1 drops once: its finally sees Res 2.
            // Kira prints: fin1 sees 2, count 1, after 2, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        gc = gc + 1
                        if gc == 1 {
                            x: Res = gh.unwrap().child.unwrap()
                            trace("fin1 sees ${'$'}{x.n}")
                        }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                }
            }

            pub class Holder {
                pub mut child: Maybe<Res> = null
            }

            mut gh: Maybe<Holder> = null
            mut gc: Int32 = 0

            fx setVia: (mut slot: Maybe<Res>, v: Res) Void {
                slot = v
            }

            pub fx run: () Void {
                h: Holder = Holder { }
                gh = h
                h.child = Res { n = 1 }
                b: Res = Res { n = 2 }
                setVia(mut h.child, b)
                trace("count ${'$'}{gc}")
                trace("after ${'$'}{h.child.unwrap().n}")
                h.child = null
                gh = null
                trace("end")
            }
            """,
        ),
        Module(
            "handle:k_c1",
            """
            // c1: the handle writes dropsOnWrite leaves to std::shared_ptr's own assignment, with a lent
            // (PRIVATE local) handle as the value, so C++ copy-assigns. The old object's IMPURE finally
            // rewrites the same handle. Kira stores, then drops: the finally sees the new object (2), its
            // own write (Res 3) is last, and Res 3's finally runs when gm lets it go; Res 2's runs when b does.
            // Kira prints: fin1 sees 2, after 3, fin3, end, fin2

            pub class Res {
                pub n: Int32 = 0

                finally {
                    if n == 1 {
                        trace("fin1 sees ${'$'}{gm.unwrap().n}")
                        gm = Res { n = 3 }
                    }
                    if n == 2 {
                        trace("fin2")
                    }
                    if n == 3 {
                        trace("fin3")
                    }
                }
            }

            mut gm: Maybe<Res> = null

            pub fx run: () Void {
                gm = Res { n = 1 }
                b: Res = Res { n = 2 }
                gm = b
                trace("after ${'$'}{gm.unwrap().n}")
                gm = null
                trace("end")
            }
            """,
        ),
    )

    /**
     * A stand-in for W2.4's classes part, which this branch does not have: each class or trait a
     * bare `class C {};`, and each method and `finally` a block whose statements this branch's
     * own statement emitter writes, so every write the probes make is spelled as the trial
     * spells it. The text is checked, never compiled. Once W2.4's part is merged
     * ([classesLower]), the real part emits the probes and they run.
     */
    private object ClassBodies : CppClassesPart {
        override fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter) {
            w.line("class ${sym.name} {};")
        }

        override fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean) {
            val c = sym as? ClassSymbol ?: return
            ctx.inScopeOf(c) {
                c.methods.forEach { fn -> w.block("void ${c.name}::${fn.name}()") { ctx.body(fn, fn.body ?: emptyList(), this) } }
                c.finally?.let { statements -> w.block("${c.name}::~${c.name}()") { ctx.body(null, statements, this) } }
            }
        }
    }

    /** Whether the compiler's own parts lower a class: false on this branch, true once W2.4's classes part is merged. */
    private val classesLower: Boolean = CppEmitParts.standard().classes != CppClassesPart.Unsupported

    private val handleTree by lazy {
        CppExprTestSupport.emit("copy-handles", handleProbes, emitterFactory = { unit, options ->
            val parts = CppEmitParts.standard()
            CppModuleEmitterFactory.create(unit, options, if (classesLower) parts else parts.copy(classes = ClassBodies))
        })
    }

    /** What Kira prints for each probe's `run()`, the verifier's values (hx2's `fin2` before `size 0`: put's by-value parameter drops at its return). */
    private val handleKira: Map<String, String> = mapOf(
        "hx1" to "fin1 sees 2|count 1|after 2|end|fin2",
        "hx1c" to "fin1 sees 2|count 1|after 2|fin2|end",
        "hx2" to "fin1|fin2|size 0|gk 11 22|end",
        "hx3" to "fin1|size 0|gk 11 22 33|end|fin2",
        "hx4" to "fin1 sees 2|child 3|fin3|end|fin2",
        "hx5" to "fin1 sees 2|count 1|after 2|end|fin2",
        "hx7" to "fin1 sees 2|count 1|after 2|end|fin2",
        "hx8" to "fin1 sees 2|count 1|after 2|end|fin2",
        "k_c1" to "fin1 sees 2|after 3|fin3|end|fin2",
    )

    @Test
    fun aWriteOverAHandleWhoseOldObjectMayRunAFinallyStoresFirst() {
        val probe = handleProbes.associateBy { it.uri.substringAfter(':') }
        listOf(
            // A lent value (a PRIVATE local or by-value parameter) is a C++ lvalue: shared_ptr copy-assignment.
            "hx1" to "kira::replace(gm) = b;",
            "hx2" to "kira::replace(kira::at(gl, 0)) = b;",
            "hx3" to "kira::replace(gmap[1]) = b;",
            "hx4" to "kira::replace(h->child) = b;",
            // The Upcast: the converting assignment.
            "hx5" to "kira::replace(gt) = b;",
            // The binding List.set, and a mut parameter's slot.
            "hx7" to "kira::replace(kira::at(items, 0)) = v;",
            "hx8" to "kira::replace(slot) = v;",
            "k_c1" to "kira::replace(gm) = b;",
            // The finally's own write over the same handle.
            "k_c1" to "kira::replace(gm) = std::make_shared<Res>(3);",
            // A prvalue stores first the same way: the order is never the library's.
            "hx1c" to "kira::replace(gm) = std::make_shared<Res>(2);",
        ).forEach { (name, text) ->
            val source = handleTree.source(probe.getValue(name))
            assertTrue(source.contains(text), "$name: $text:\n$source")
        }
    }

    @TestFactory
    fun aWriteOverAHandlePrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("handles [${tc.id}]") {
                Assumptions.assumeTrue(classesLower, "no class lowers on this branch (KI-18): the trial runs the probes")
                val names = handleProbes.map { it.uri.substringAfter(':') }
                val driver = buildString {
                    handleProbes.forEach { append("#include \"").append(it.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n") }
                    append("#include <cstdio>\n\nint main()\n{\n")
                    names.forEach { append("    std::printf(\"[$it]\\n\");\n    $it::run();\n") }
                    append("    return 0;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(handleTree, driver, tc) ?: return@dynamicTest
                val expected = names.joinToString("") { "[$it]\n" + handleKira.getValue(it).replace('|', '\n') + "\n" }
                assertEquals(expected, stdout.replace("\r\n", "\n"), tc.id)
            }
        }
}
