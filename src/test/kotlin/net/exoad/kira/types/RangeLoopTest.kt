package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.fix.RangeLoopFix
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Loops after the owner's 2026-11-01 decision: `a..b` holds b, and `a.until(b)` / `xs.indices()` leave the end out. */
class RangeLoopTest {
    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(body)

    private fun py(body: String): String = PyTestSupport.emit(body).python()

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", body)).source("app:main") ?: ""

    private fun only(p: TypedProgram, code: String) {
        expectDiagnostic(p, code)
        assertEquals(listOf(code), p.diagnostics.filter { it.isError }.map { it.code }.distinct(), TyperTestSupport.render(p))
    }

    // ---- the probe: fails on main, where a loop's range left its end out (D37) ------------------

    @Test
    fun aLoopOverARangeHoldsItsEnd() {
        val out = py("pub f: () Int32 {\n    t: Mutable<Int32> = 0\n    for i: Int32 in 1..4 {\n        t += i\n    }\n    t\n}\n")
        assertTrue(out.contains("for i in range(1, 5):"), out)
    }

    // ---- inclusive loops on py and C++ ------------------------------------------------------------

    @Test
    fun anInclusiveLoopStopsAtItsEndOnEveryTypeAndEveryEnd() {
        val body = """
            pub f: (n: Int32) Int32 {
                t: Mutable<Int32> = 0
                for i: Int32 in 0..n {
                    t += i
                }
                for j: Int32 in 0..3 {
                    t += j
                }
                for k: UInt8 in 250..255 {
                    t += 1
                }
                t
            }
            """
        val p = py(body)
        assertTrue(p.contains("for i in range(0, n + 1):"), p)
        assertTrue(p.contains("for j in range(0, 4):"), p)
        assertTrue(p.contains("for k in range(250, 256):"), p)
        val c = cpp(body)
        // A constant end below the type's largest is `<=` it; any other end is read once and stops the step.
        assertTrue(c.contains("for(std::int32_t j = 0; j <= 3; ++j)"), c)
        assertTrue(c.contains("for(std::int32_t i = 0, i_last = n; i_more && i <= i_last; i_more = i != i_last && (++i, true))"), c)
        assertTrue(c.contains("for(std::uint8_t k = 250, k_last = 255; k_more && k <= k_last; k_more = k != k_last && (++k, true))"), c)
    }

    // ---- until and indices ------------------------------------------------------------------------

    @Test
    fun untilAndIndicesCountAsTheOldHalfOpenLoopDid() {
        val body = """
            pub f: (n: Size, xs: List<Int32>, s: Str) Int32 {
                t: Mutable<Int32> = 0
                for i: Size in 0.until(n) {
                    t += 1
                }
                for j: Size in xs.indices() {
                    t += xs[j]
                }
                for k: Size in s.indices() {
                    t += 1
                }
                for m: Int32 in 0.until(3) {
                    t += m
                }
                t
            }
            """
        expectNoErrors(typed(body))
        val p = py(body)
        listOf("for i in range(0, n):", "for j in range(0, len(xs)):", "for k in range(0, len(s)):", "for m in range(0, 3):").forEach { assertTrue(p.contains(it), p) }
        assertFalse(p.contains("_k_Until("), "a loop's until builds no Until:\n$p")
        val c = cpp(body)
        listOf(
            "for(kira::Size i = 0, i_end = n; i < i_end; ++i)",
            "for(kira::Size j = 0, j_end = xs.size(); j < j_end; ++j)",
            "for(kira::Size k = 0, k_end = kira::str::length(s); k < k_end; ++k)",
            "for(std::int32_t m = 0; m < 3; ++m)",
        ).forEach { assertTrue(c.contains(it), c) }
        assertFalse(c.contains("Until"), "a loop's until builds no Until:\n$c")
    }

    @Test
    fun anUntilValueIsTheStdlibsUntilAndLeavesItsEndOut() {
        val body = """
            pub f: (x: Int32, xs: List<Int32>) Int32 {
                u: Until<Int32> = 2.until(5)
                w: Until<Size> = xs.indices()
                t: Mutable<Int32> = 0
                for i: Int32 in u {
                    t += i
                }
                x in u && u.contains(x) && w.end == xs.size() ? t : 0
            }
            """
        expectNoErrors(typed(body))
        val p = py(body)
        assertTrue(p.contains("u = _k_Until(2, 5)"), p)
        assertTrue(p.contains("w = _k_Until(0, len(xs))"), p)
        assertTrue(p.contains("for i in u._k_steps():"), p)
        val c = cpp(body)
        assertTrue(c.contains("::kira::range::Until<std::int32_t>{.begin = 2, .end = 5}"), c)
        assertTrue(c.contains("for(std::int32_t i = i_r.begin; i < i_r.end; ++i)"), c)
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
        assertTrue(p.model.halfOpens.isEmpty())
    }

    @Test
    fun untilCountsIntegersOfOneType() {
        only(typed("pub f: (a: Int32, b: Int64) Int32 {\n    t: Mutable<Int32> = 0\n    for i: Int32 in a.until(b) {\n        t += 1\n    }\n    t\n}"), "types.until.mismatch")
        only(typed("pub f: (n: Float64) Bool {\n    u: Until<Int32> = 0.until(n)\n    true\n}"), "types.until.element")
        expectDiagnostic(typed("pub f: (u: Until<Float64>) Bool {\n    true\n}"), "rules.generics.bound")
    }

    @Test
    fun aLoopToNMinusOneGetsAHint() {
        val p = typed("pub f: (n: Size) Size {\n    t: Mutable<Size> = 0\n    for i: Size in 0..(n - 1) {\n        t += i\n    }\n    t\n}")
        expectNoErrors(p)
        val hint = p.diagnostics.single { it.code == "types.for.minus-one" }
        assertEquals(Severity.WARNING, hint.severity)
        assertTrue(hint.message.contains("0.until(n)") && hint.message.contains("indices()"), hint.message)
        assertFalse(typed("pub f: (n: Size) Size {\n    t: Mutable<Size> = 0\n    for i: Size in 0..n {\n        t += i\n    }\n    t\n}").diagnostics.any { it.code == "types.for.minus-one" })
    }

    // ---- C and JS ---------------------------------------------------------------------------------

    @Test
    fun cAndJsCountInclusiveRangesAndUntilAndRefuseIndices() {
        val loops = "f: () Int32 {\n    t: Mutable<Int32> = 0\n    for i: Int32 in 0..3 {\n        t += i\n    }\n    for j: Int32 in 0.until(3) {\n        t += j\n    }\n    t\n}\n"
        val c = TestCompileSupport.transpileSnippetToC(loops, "test/main.kira")
        assertTrue(c.contains("for(Int32 i = 0, i_last = 3; i_more && i <= i_last; i_more = i != i_last && (++i, 1))"), c)
        assertTrue(c.contains("for(Int32 j = 0; j < 3; ++j)"), c)
        val js = TestCompileSupport.transpileSnippetToJS(loops, "test/main.kira")
        assertTrue(js.contains("for (let i = 0, i_last = 3; i <= i_last; ++i)"), js)
        assertTrue(js.contains("for (let j = 0; j < 3; ++j)"), js)
        val indices = "f: (xs: List<Int32>) Int32 {\n    t: Mutable<Int32> = 0\n    for i: Size in xs.indices() {\n        t += 1\n    }\n    t\n}\n"
        val held = "f: () Int32 {\n    u: Until<Int32> = 0.until(3)\n    0\n}\n"
        for ((source, what) in listOf(indices to "xs.indices()", held to "`until` or `indices` outside a for loop's head")) {
            listOf(
                assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToC(source, "test/main.kira") },
                assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToJS(source, "test/main.kira") },
            ).forEach { assertTrue(it.message.orEmpty().contains(what), it.message) }
        }
    }

    // ---- the migration fixer ----------------------------------------------------------------------

    @Test
    fun theFixerRewritesEachOldLoopToTheSameSteps() {
        val src = """
            f: (xs: List<Int32>, s: Str, n: Size, lo: Int32) Int32 {
                t: Mutable<Int32> = 0
                for i: Size in 0..xs.size() {
                    t += xs[i]
                }
                for i: Size in 0..xs.size() {
                    t += 1
                }
                for c: Size in 0..s.length() {
                    t += s[c] as Int32
                }
                for k: Size in 1..xs.size() {
                    t += 1
                }
                for m: Int32 in (lo + 1)..(lo + 9) {
                    t += m
                }
                for q: Int32 in -2..start(3) {
                    t += q
                }
                for r: Int32 in start(1)..4 {
                    t += r
                }
                t
            }
            """.trimIndent() + "\n"
        val r = RangeLoopFix.rewrite(src)
        assertEquals(2, r.indices, r.text)
        assertEquals(5, r.until, r.text)
        assertEquals(emptyList(), r.skipped)
        listOf(
            "for i: Size in xs.indices() {",
            // indices() only where the body indexes what the end measures
            "for i: Size in 0.until(xs.size()) {",
            "for c: Size in s.indices() {",
            "for k: Size in 1.until(xs.size()) {",
            "for m: Int32 in (lo + 1).until(lo + 9) {",
            "for q: Int32 in (-2).until(start(3)) {",
            "for r: Int32 in start(1).until(4) {",
        ).forEach { assertTrue(r.text.contains(it), r.text) }
        assertEquals(src.lines().size, r.text.lines().size)
        // A rewritten file has no old loop left, so a second run changes nothing.
        assertEquals(r.text, RangeLoopFix.rewrite(r.text).text)
    }

    @Test
    fun theFixedLoopsCountWhatTheOldOnesDid() {
        val old = "pub f: (xs: List<Int32>, n: Int32) Int32 {\n    t: Mutable<Int32> = 0\n    for i: Size in 0..xs.size() {\n        t += xs[i]\n    }\n    for j: Int32 in 0..n {\n        t += j\n    }\n    t\n}\n"
        val fixed = RangeLoopFix.rewrite(old).text
        val p = py(fixed)
        assertTrue(p.contains("for i in range(0, len(xs)):") && p.contains("for j in range(0, n):"), p)
    }
}
