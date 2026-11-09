package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loops over a range: `a..b` holds b and `a..<b` leaves it out, on py, C++, C and JS. */
class RangeLoopTest {
    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(body)

    private fun py(body: String): String = PyTestSupport.emit(body).python()

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", body)).source("app:main") ?: ""

    // ---- the probe: fails on main, where a loop's range left its end out (D37) ------------------

    @Test
    fun aLoopOverARangeHoldsItsEnd() {
        val out = py("pub f: () Int32 {\n    t: Mutable<Int32> { 0 }\n    while i: Int32 in 1..4 {\n        t += i\n    }\n    t\n}\n")
        assertTrue(out.contains("for i in range(1, 5):"), out)
    }

    // ---- inclusive loops on py and C++ ------------------------------------------------------------

    @Test
    fun anInclusiveLoopStopsAtItsEndOnEveryTypeAndEveryEnd() {
        val body = """
            pub f: (n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                while i: Int32 in 0..n {
                    t += i
                }
                while j: Int32 in 0..3 {
                    t += j
                }
                while k: UInt8 in 250..255 {
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

    // ---- C and JS ---------------------------------------------------------------------------------

    @Test
    fun cAndJsCountInclusiveRanges() {
        val loops = "f: () Int32 {\n    t: Mutable<Int32> { 0 }\n    while i: Int32 in 0..3 {\n        t += i\n    }\n    t\n}\n"
        val c = TestCompileSupport.transpileSnippetToC(loops, "test/main.kira")
        assertTrue(c.contains("for(Int32 i = 0, i_last = 3; i_more && i <= i_last; i_more = i != i_last && (++i, 1))"), c)
        val js = TestCompileSupport.transpileSnippetToJS(loops, "test/main.kira")
        assertTrue(js.contains("for (let i = 0, i_last = 3; i <= i_last; ++i)"), js)
    }

    @Test
    fun aLoopOverARangeValueWalksFromItsBeginToItsEnd() {
        val body = "pub f: (r: Range<Int32>) Int32 {\n    t: Mutable<Int32> { 0 }\n    while i: Int32 in r {\n        t += i\n    }\n    t\n}\n"
        expectNoErrors(typed(body))
        assertTrue(py(body).contains("for i in r._k_steps():"), py(body))
    }

    // ---- a..<b leaves b out -------------------------------------------------------------------------

    private fun only(p: TypedProgram, code: String) {
        expectDiagnostic(p, code)
        assertEquals(listOf(code), p.diagnostics.filter { it.isError }.map { it.code }.distinct(), TyperTestSupport.render(p))
    }

    @Test
    fun aLoopOverALeftOutRangeTypesLikeTheInclusiveOne() {
        val p = typed(
            "pub f: (n: Size, m: Int32) Size {\n    t: Mutable<Size> { 0 }\n    while i: Size in 0..<n {\n        t += i\n    }\n" +
                "    while j: Int32 in m..<4 {\n        t += 1\n    }\n    t\n}\n",
        )
        expectNoErrors(p)
        assertEquals(2, p.model.loops.size)
        only(typed("pub f: (a: Int32, b: Int64) Void {\n    while i: Int32 in a..<b {\n        trace(i)\n    }\n}"), "types.assign.mismatch")
        only(typed("pub f: (a: Int32, b: Int64) Void {\n    r: Range<Int32> { a..<b }\n    trace(r.begin)\n}"), "types.range.mismatch")
        only(typed("pub f: (n: Float64) Void {\n    while i: Float64 in 0.0..<n {\n        trace(i)\n    }\n}"), "types.loop.range-type")
        only(typed("pub f: (x: Int64) Bool {\n    x in 1..<4 as Int32\n}"), "types.in.mismatch")
    }

    @Test
    fun aLeftOutRangeIsARangeValueAndMembership() {
        expectNoErrors(typed("pub f: (k: Int32, n: Int32) Bool {\n    r: Range<Int32> { 1..<n }\n    k in r && k in 0..<n\n}\n"))
    }

    @Test
    fun pyLowersALeftOutRangeToRangeAndAComparison() {
        val body = "pub f: (n: Size, k: Int32) Size {\n    t: Mutable<Size> { 0 }\n    while i: Size in 0..<n {\n        t += i\n    }\n" +
            "    r: Range<Int32> { 1..<4 }\n    while j: Int32 in r {\n        t += 1\n    }\n    k in 1..<4 && k in r ? t : 0\n}\n"
        val p = py(body)
        assertTrue(p.contains("for i in range(0, n):"), p)
        assertTrue(p.contains("_k_Range(1, 4, True)") && p.contains("for j in r._k_steps():"), p)
        assertTrue(p.contains("1 <= k < 4"), p)
    }

    @Test
    fun cppLowersALeftOutRangeBelowItsEndAndNeverWraps() {
        val body = """
            pub f: (n: Size, k: Int32, z: UInt8) Size {
                t: Mutable<Size> { 0 }
                while i: Size in 0..<n {
                    t += i
                }
                while j: Int32 in 2..<5 {
                    t += 1
                }
                while u: UInt8 in 0..<0 {
                    t += 1
                }
                r: Range<Int32> { 1..<4 }
                while m: Int32 in r {
                    t += 1
                }
                a: Bool { k in 1..<4 }
                b: Bool { z in 0..<0 }
                a && !b ? t : 0
            }
            """
        val c = cpp(body)
        assertTrue(c.contains("for(kira::Size i = 0, i_end = n; i < i_end; ++i)"), c)
        assertTrue(c.contains("for(std::int32_t j = 2; j < 5; ++j)"), c)
        // The least value of an unsigned type as the end is read into a local, as `i < 0u` is a warning.
        assertTrue(c.contains("for(std::uint8_t u = 0, u_end = 0; u < u_end; ++u)"), c)
        assertTrue(c.contains(".exclusive = true"), c)
        assertTrue(c.contains("m_r.exclusive ? ++m < m_r.end : m != m_r.end && (++m, true)"), c)
        assertTrue(c.contains("1 <= k && k < 4"), c)
        assertTrue(c.contains("static_cast<void>(z), false"), c)
    }

    @Test
    fun cAndJsLowerALeftOutRangeBelowItsEnd() {
        val loops = "f: () Int32 {\n    t: Mutable<Int32> { 0 }\n    while i: Int32 in 0..<3 {\n        t += i\n    }\n    t\n}\n"
        val c = TestCompileSupport.transpileSnippetToC(loops, "test/main.kira")
        assertTrue(c.contains("for(Int32 i = 0, i_end = 3; i < i_end; ++i)"), c)
        val js = TestCompileSupport.transpileSnippetToJS(loops, "test/main.kira")
        assertTrue(js.contains("for (let i = 0, i_end = 3; i < i_end; ++i)"), js)
    }
}
