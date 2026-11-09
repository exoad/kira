package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.fix.ForFix
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `for` is removed and `while NAME: TYPE in EXPR { }` iterates: the refusal, what `kira --fix-for` rewrites, and the two while forms side by side. */
class ForRemovedTest {
    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(body)

    private fun py(body: String): String = PyTestSupport.emit(body).python()

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", body)).source("app:main") ?: ""

    private fun fix(src: String): ForFix.Result = ForFix.rewrite(src.trimIndent() + "\n")

    // ---- the refusal ------------------------------------------------------------------------------

    @Test
    fun aForIsRefusedWhereverItIsWrittenAndTheMessageShowsTheWhileItBecomes() {
        val e = assertThrows<net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException> {
            typed("pub f: () Void {\n    for i: Int32 in 0..3 {\n        trace(i)\n    }\n}")
        }
        assertEquals("parse.for.removed", e.tag, e.message)
        assertTrue(e.message.contains("`for` is removed: for 'i: Int32 in 0..3' write 'while i: Int32 in 0..3 { }'."), e.message)
        assertTrue(e.message.contains("`kira --fix-for FILE...` rewrites a file."), e.message)
        // A head that is not NAME: TYPE in EXPR is told the two forms.
        val h = assertThrows<net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException> {
            typed("pub f: () Void {\n    for x in xs {\n        trace(x)\n    }\n}")
        }
        assertEquals("parse.for.removed", h.tag, h.message)
        assertTrue(h.message.contains("`while NAME: TYPE in EXPR { }`") && h.message.contains("`while <condition> { }`"), h.message)
    }

    // ---- the two while forms ----------------------------------------------------------------------

    @Test
    fun aTypedHeadIteratesAndAnyOtherHeadIsACondition() {
        val iterating = typed(
            """
            pub f: (r: Range<Int32>, xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                while i: Int32 in 0..3 {
                    t += i
                }
                while j: Int32 in r {
                    t += j
                }
                while x: Int32 in xs {
                    t += x
                }
                t
            }
            """
        )
        expectNoErrors(iterating)
        assertEquals(3, iterating.model.loops.size)
        val conditions = typed(
            """
            pub f: (ok: Bool) Int32 {
                tries: Mutable<Int32> { 0 }
                while ok && tries < 5 {
                    tries += 1
                }
                tries
            }
            """
        )
        expectNoErrors(conditions)
        assertTrue(conditions.model.loops.isEmpty())
    }

    @Test
    fun anUntypedInIsStillAMembershipConditionNotAnIteration() {
        val p = typed(
            """
            pub f: (r: Range<Int32>) Int32 {
                x: Mutable<Int32> { 0 }
                while x in r {
                    x += 1
                }
                x
            }
            """
        )
        expectNoErrors(p)
        assertTrue(p.model.loops.isEmpty(), "while x in r { } has no NAME: TYPE, so it is a condition")
    }

    @Test
    fun breakAndContinueWorkInBothFormsOnEveryBackend() {
        val body = """
            pub f: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                while x: Int32 in xs {
                    if x == 0 {
                        continue
                    }
                    if x < 0 {
                        break
                    }
                    t += x
                }
                i: Mutable<Int32> { 0 }
                while i < 10 {
                    i += 1
                    if i == 3 {
                        continue
                    }
                    if i == 8 {
                        break
                    }
                    t += i
                }
                t
            }
            """
        expectNoErrors(typed(body))
        val p = py(body)
        assertTrue(p.contains("for x in xs") && p.contains("while i < 10:") && p.contains("continue") && p.contains("break"), p)
        val c = cpp(body)
        assertTrue(c.contains("for(const std::int32_t x : xs)") && c.contains("while(i < 10)") && c.contains("continue;") && c.contains("break;"), c)
    }

    @Test
    fun cAndJsLowerBothForms() {
        val loops = "f: () Int32 {\n    t: Mutable<Int32> { 0 }\n    while i: Int32 in 0..3 {\n        t += i\n    }\n    k: Mutable<Int32> { 0 }\n    while k < 3 {\n        k += 1\n        t += k\n    }\n    t\n}\n"
        val c = TestCompileSupport.transpileSnippetToC(loops, "test/main.kira")
        assertTrue(c.contains("for(Int32 i = 0, i_last = 3;") && c.contains("while((k < 3))"), c)
        val js = TestCompileSupport.transpileSnippetToJS(loops, "test/main.kira")
        assertTrue(js.contains("for (let i = 0, i_last = 3; i <= i_last; ++i)") && js.contains("while ((k < 3))"), js)
    }

    // ---- the fixer ---------------------------------------------------------------------------------

    @Test
    fun theFixerSwapsTheKeywordAndNothingElse() {
        val r = fix(
            """
            f: (n: Int32, xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                for i: Int32 in 0..n {
                    for  x:  Int32   in xs {
                        t += x * i
                    }
                }
                for s: Str in names() { // for k: Int32 in 0..2 {
                    t += s.length() as Int32
                }
                t
            }
            """
        )
        assertEquals(3, r.fixed)
        assertEquals(emptyList(), r.skipped)
        assertEquals(
            """
            f: (n: Int32, xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                while i: Int32 in 0..n {
                    while  x:  Int32   in xs {
                        t += x * i
                    }
                }
                while s: Str in names() { // for k: Int32 in 0..2 {
                    t += s.length() as Int32
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        // A second run changes nothing.
        assertEquals(r.text, ForFix.rewrite(r.text).text)
        assertEquals(0, ForFix.rewrite(r.text).fixed)
    }

    @Test
    fun aForInAStringOrACommentIsNeverALoop() {
        val src = "f: () Str {\n    // for i: Int32 in 0..3 {\n    \"for i: Int32 in 0..3 {\"\n}\n"
        val r = ForFix.rewrite(src)
        assertEquals(0, r.fixed)
        assertEquals(src, r.text)
    }

    @Test
    fun crlfAndTabsAreKept() {
        val src = "f: (n: Int32) Void {\r\n\tfor i: Int32 in 0..n {\r\n\t\ttrace(i)\r\n\t}\r\n}\r\n"
        val r = ForFix.rewrite(src)
        assertEquals(1, r.fixed)
        assertEquals("f: (n: Int32) Void {\r\n\twhile i: Int32 in 0..n {\r\n\t\ttrace(i)\r\n\t}\r\n}\r\n", r.text)
        assertEquals(src.length + 2, r.text.length)
    }

    @Test
    fun aHeadThatIsNotNameTypeInExprIsLeftAndNamedWithWhy() {
        val src = "f: (xs: List<Int32>) Void {\n    for (x: Int32 in xs) {\n        trace(x)\n    }\n    for x in xs {\n        trace(x)\n    }\n    for x: Int32 xs {\n        trace(x)\n    }\n}\n"
        val r = ForFix.rewrite(src)
        assertEquals(0, r.fixed)
        assertEquals(src, r.text)
        assertEquals(3, r.skipped.size, r.skipped.toString())
        assertTrue(r.skipped[0].startsWith("line 2:") && r.skipped[0].contains("parenthesised"), r.skipped.toString())
        assertTrue(r.skipped[1].startsWith("line 5:") && r.skipped[1].contains("NAME: TYPE in EXPR"), r.skipped.toString())
        assertTrue(r.skipped[2].startsWith("line 8:") && r.skipped[2].contains("no 'in'"), r.skipped.toString())
    }

    @Test
    fun aFixedLoopCountsWhatTheForDid() {
        val old = "pub f: (xs: List<Int32>, n: Int32) Int32 {\n    t: Mutable<Int32> { 0 }\n    for x: Int32 in xs {\n        t += x\n    }\n    for j: Int32 in 0..n {\n        t += j\n    }\n    t\n}\n"
        val fixed = ForFix.rewrite(old).text
        expectNoErrors(typed(fixed))
        val p = py(fixed)
        assertTrue(p.contains("for x in xs") && p.contains("for j in range(0, n + 1):"), p)
    }

    @Test
    fun theFixerRewritesAnUntilHeadAndAnIndicesHeadToTheLeftOutRange() {
        val r = fix(
            """
            f: (xs: List<Int32>, s: Str, v: View<UInt8>, n: Size, lo: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                for i: Size in 0.until(n) { // a counter
                    t += 1
                }
                for  j:  Size   in xs.indices() {
                    t += 1
                }
                for c: Size in s.indices() {
                    t += 1
                }
                for b: Size in v.indices() {
                    t += 1
                }
                for k: Int32 in (lo + 1).until(lo + 9) {
                    t += k
                }
                for m: Int32 in lo.until(n as Int32 - 1) {
                    t += m
                }
                while q: Int32 in startAt(1).until(endAt(4)) {
                    t += q
                }
                for x: Size in "abc".indices() {
                    t += 1
                }
                t
            }
            """
        )
        assertEquals(8, r.fixed)
        assertEquals(emptyList(), r.skipped)
        assertEquals(
            """
            f: (xs: List<Int32>, s: Str, v: View<UInt8>, n: Size, lo: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                while i: Size in 0..<n { // a counter
                    t += 1
                }
                while  j:  Size   in 0..<xs.size() {
                    t += 1
                }
                while c: Size in 0..<s.length() {
                    t += 1
                }
                while b: Size in 0..<v.size() {
                    t += 1
                }
                while k: Int32 in (lo + 1)..<(lo + 9) {
                    t += k
                }
                while m: Int32 in lo..<(n as Int32 - 1) {
                    t += m
                }
                while q: Int32 in startAt(1)..<endAt(4) {
                    t += q
                }
                while x: Size in 0..<"abc".length() {
                    t += 1
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        assertEquals(r.text, ForFix.rewrite(r.text).text)
        // The rewritten file types.
        expectNoErrors(typed(r.text.replace("startAt(1)", "1").replace("endAt(4)", "4").replace("f: (xs", "pub f: (xs")))
    }

    @Test
    fun anIndicesHeadWhoseKindTheFileDoesNotWriteIsLeftAndListed() {
        val src = "f: (xs: List<Int32>, s: Str) Void {\n    for i: Size in g.items.indices() {\n        trace(i)\n    }\n    for j: Size in ys.indices() {\n        trace(j)\n    }\n    for k: Size in xs.indices() {\n        trace(k)\n    }\n}\n"
        val r = ForFix.rewrite(src)
        // The keyword is swapped, the head of a receiver with no written type stays.
        assertEquals(3, r.fixed)
        assertEquals(2, r.skipped.size, r.skipped.toString())
        assertTrue(r.skipped[0].startsWith("line 2:") && r.skipped[0].contains("g.items.indices()") && r.skipped[0].contains("kind"), r.skipped.toString())
        assertTrue(r.skipped[1].startsWith("line 5:") && r.skipped[1].contains("ys.indices()"), r.skipped.toString())
        assertTrue(r.text.contains("while i: Size in g.items.indices() {") && r.text.contains("while k: Size in 0..<xs.size() {"), r.text)
        // A name written as a Str in one place and a List in another is no written kind.
        val mixed = "f: (a: Str) Void {\n    for i: Size in a.indices() {\n    }\n}\ng: (a: List<Int32>) Void {\n}\n"
        assertEquals(1, ForFix.rewrite(mixed).skipped.size)
    }

    @Test
    fun aMultiLineUntilHeadIsLeftAndCrlfAndTabsAreKeptOnARewrittenHead() {
        val multi = "f: (n: Size) Void {\n    for i: Size in 0\n        .until(n) {\n    }\n}\n"
        val r = ForFix.rewrite(multi)
        assertEquals(1, r.skipped.size, r.skipped.toString())
        val crlf = "f: (n: Size) Void {\r\n\tfor i: Size in 0.until(n) {\r\n\t\ttrace(i)\r\n\t}\r\n}\r\n"
        assertEquals("f: (n: Size) Void {\r\n\twhile i: Size in 0..<n {\r\n\t\ttrace(i)\r\n\t}\r\n}\r\n", ForFix.rewrite(crlf).text)
    }

    // ---- until and indices are gone ------------------------------------------------------------------

    @Test
    fun untilAndIndicesAreRemovedWithTheRangeFormInTheMessage() {
        val u = typed("pub f: (n: Int32) Bool {\n    u: Range<Int32> { 0.until(n) }\n    true\n}")
        expectDiagnostic(u, "types.until.removed")
        val um = u.diagnostics.first { it.code == "types.until.removed" }.message
        assertTrue(um.contains("`until` is removed") && um.contains("0..<n"), um)
        val i = typed("pub f: (xs: List<Int32>, s: Str) Bool {\n    a: Range<Size> { xs.indices() }\n    b: Range<Size> { s.indices() }\n    true\n}")
        val ims = i.diagnostics.filter { it.code == "types.indices.removed" }.map { it.message }
        assertEquals(2, ims.size, ims.toString())
        assertTrue(ims[0].contains("0..<xs.size()") && ims[1].contains("0..<s.length()"), ims.toString())
        // A loop's head takes no half-open form either.
        expectDiagnostic(typed("pub f: (n: Int32) Void {\n    while i: Int32 in 0.until(n) {\n        trace(i)\n    }\n}"), "types.until.removed")
    }

    @Test
    fun theUntilTypeIsRemovedAndNamesRange() {
        val p = typed("pub f: (u: Until<Int32>) Bool {\n    true\n}")
        expectDiagnostic(p, "types.type.unknown")
        val m = p.diagnostics.first { it.code == "types.type.unknown" }.message
        assertTrue(m.contains("`Until` is removed") && m.contains("Range<T>") && m.contains("a..b"), m)
    }

    @Test
    fun aUserTypesOwnUntilAndIndicesStayItsOwn() {
        val p = typed(
            """
            pub Grid: Struct {
                pub n: Int32

                pub indices: () List<Int32> {
                    @listOf(n)
                }
            }

            pub f: (g: Grid) Int32 {
                g.indices()[0]
            }
            """
        )
        expectNoErrors(p)
    }

    @Test
    fun cAndJsRefuseTheRemovedUntilAndIndices() {
        val held = "f: () Int32 {\n    u: Range<Int32> { 0.until(3) }\n    0\n}\n"
        listOf(
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToC(held, "test/main.kira") },
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToJS(held, "test/main.kira") },
        ).forEach { assertTrue(it.message.orEmpty().contains("`until` or `indices` (removed"), it.message) }
    }
}
