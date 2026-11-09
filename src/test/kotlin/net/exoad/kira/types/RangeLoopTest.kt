package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Loops over a range: `a..b` holds b, on py, C++, C and JS. */
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
}
