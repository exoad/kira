package net.exoad.kira.suite.parser

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineStartTest {
    private fun parse(text: String): RootASTNode {
        val unit = TyperTestSupport.unitOf(module("test:main", text))
        return unit.allSources().first { runCatching { it.getModuleUri() }.getOrNull() == "test:main" }.ast
    }

    private val bump = "bump: (k: Int32) Int32 {\n    trace(\"bump\")\n    k + 10\n}\n\n"

    @Test
    fun aLineAtTheStatementColumnStartsItsOwnStatement() {
        parse("${bump}f: (a: Int32) Int32 {\n    bump(a)\n    -1\n}\n")
        parse("f: (a: Int32) Int32 {\n    t: Int32 = a * 3\n    (t + 1) * 2\n}\n")
        parse("f: (xs: List<Int32>) Int32 {\n    ys: List<Int32> = xs\n    ys.size()\n}\n")
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val main = "\n\nmain: () Void {\n    trace(f(5))\n}\n"
        assertEquals("bump\n-1\n", run("${bump}f: (a: Int32) Int32 {\n    bump(a)\n    -1\n}$main"))
        assertEquals("16\n", run("f: (a: Int32) Int32 {\n    t: Int32 = a * 3\n    (t + 1)\n}$main"))
    }

    @Test
    fun anIndentedLineGoesOn() {
        parse("f: (a: Str) Str {\n    want: Str = \"[\" + a\n        + \"]\"\n    want\n}\n")
        parse("f: (n: Int32) Float64 {\n    if n < 0 {\n        trace(n)\n    }\n    (n as Float64) / 60.0\n}\n")
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val main = "\n\nmain: () Void {\n    trace(f(5))\n}\n"
        assertEquals("bump\n14\n", run("${bump}f: (a: Int32) Int32 {\n    bump(a)\n        - 1\n}$main"))
    }

    @Test
    fun aParenthesisThatOpensTheLineAfterANameStartsTheNextStatementNotACall() {
        // A name, then a '(' at the statement column: two statements, never `b(...)`, in a body, an if arm and an else-if arm.
        val flat = """
            f: (a: Int64, b: Int64) Float64 {
                d: Int64 = a - b
                (b as Float64) + (d as Float64)
            }
        """.trimIndent() + "\n"
        val arm = """
            f: (a: Int64, b: Int64) Float64 {
                if a > 0 {
                    d: Int64 = a - b
                    (b as Float64) + (d as Float64)
                } else {
                    0.0
                }
            }
        """.trimIndent() + "\n"
        // bibo's webdrive.kira escUs, whose two declarations needed a ';'.
        val esc = """
            ESC_NEUTRAL_US: Int64 = 1500

            f: (escHiUs: Int64, escLoUs: Int64, throttleMilli: Int64) Float64 {
                if throttleMilli > 0 {
                    forwardUs: Int64 = escHiUs - escLoUs
                    (escLoUs as Float64) + ((forwardUs * throttleMilli) as Float64) / 1000.0
                } else if throttleMilli < 0 {
                    backUs: Int64 = ESC_NEUTRAL_US - escLoUs
                    (ESC_NEUTRAL_US as Float64) - ((backUs * -throttleMilli) as Float64) / 1000.0
                } else {
                    ESC_NEUTRAL_US as Float64
                }
            }
        """.trimIndent() + "\n"
        for (text in listOf(flat, arm, esc)) {
            val program = TyperTestSupport.snippet(text)
            assertTrue(program.diagnostics.none { it.isError }, program.diagnostics.joinToString(" | ") { it.message })
        }
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val twice = "\nmain: () Void {\n    trace(f(5, 2))\n}\n"
        assertEquals("5\n", run(flat + twice))
        assertEquals("5\n", run(arm + twice))
        val three = "\nmain: () Void {\n    trace(f(2000, 1600, 500))\n    trace(f(2000, 1100, -500))\n    trace(f(2000, 1600, 0))\n}\n"
        assertEquals("1800\n1300\n1500\n", run(esc + three))
    }

    @Test
    fun aStatementThatOnlyComputesAValueIsRefused() {
        // `a` then `- b` read as `a - b` before (main refused it as parse.line.continues); now `a` alone would be dropped.
        val split = TyperTestSupport.snippet("f: (a: Int32, b: Int32) Int32 {\n    a\n    - b\n}\n")
        assertEquals(listOf("types.stmt.unused"), split.diagnostics.filter { it.isError }.map { it.code }, split.diagnostics.joinToString(" | ") { it.message })
        val dropped = TyperTestSupport.snippet("f: (a: Int32, b: Int32) Int32 {\n    t: Int32 = a * 10\n    - b\n    t\n}\n")
        assertEquals(listOf("types.stmt.unused"), dropped.diagnostics.filter { it.isError }.map { it.code }, dropped.diagnostics.joinToString(" | ") { it.message })
        // A call keeps what it does, and a block's last line is its value: neither is refused.
        val kept = TyperTestSupport.snippet("${bump}f: (a: Int32) Int32 {\n    bump(a)\n    -a\n}\n")
        assertTrue(kept.diagnostics.none { it.isError }, kept.diagnostics.joinToString(" | ") { it.message })
    }

    @Test
    fun aSemicolonIsRefusedWithTheRemovedWordHint() {
        val e = assertThrows<DiagnosticsException> { parse("f: () Int32 {\n    x: Int32 = 1;\n    x\n}\n") }
        assertEquals("parse.removed", e.tag, e.message)
        assertTrue(e.message.contains("a statement ends at its newline"), e.message)
    }

    private fun run(text: String): String {
        val file = Files.createTempFile("linestart", ".py").toFile()
        try {
            file.writeText(PyTestSupport.emit(text).python())
            return PyTestSupport.run(listOf(PyTestSupport.python!!, file.absolutePath), file.parentFile).also { assertEquals(0, it.exitCode, it.all) }.stdout
        } finally {
            file.delete()
        }
    }
}
