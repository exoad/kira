package net.exoad.kira.suite

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

/** A line that opens with `-`, `+`, `(` or `[` where its statement starts, yet would go on with the line before. */
class LineStartTest {
    private fun parse(text: String): RootASTNode {
        val unit = TyperTestSupport.unitOf(module("test:main", text))
        return unit.allSources().first { runCatching { it.getModuleUri() }.getOrNull() == "test:main" }.ast
    }

    private fun refusal(text: String): DiagnosticsException = assertThrows { parse(text) }

    private val bump = "bump: (k: Int32) Int32 {\n    trace(\"bump\")\n    k + 10\n}\n\n"

    @Test
    fun aValueLineAfterAStatementIsRefused() {
        // `-1` at the statement's column read as `bump(a) - 1`: f(5) gave 14, not -1.
        val minus = refusal("${bump}f: (a: Int32) Int32 {\n    bump(a)\n    -1\n}\n")
        assertEquals("parse.line.continues", minus.tag)
        assertTrue(minus.message.contains("as a subtraction"), minus.message)
        // `(t + 1) * 2` would call the line before's value, `[0]` index it, `+a` add to it.
        assertEquals("parse.line.continues", refusal("f: (a: Int32) Int32 {\n    t: Int32 = a * 3\n    (t + 1) * 2\n}\n").tag)
        assertEquals("parse.line.continues", refusal("f: (xs: List<Int32>) Int32 {\n    ys: List<Int32> = xs\n    [0]\n}\n").tag)
        assertEquals("parse.line.continues", refusal("${bump}f: (a: Int32) Int32 {\n    bump(a)\n    +a\n}\n").tag)
        // A lambda's statements have their own column; so do a type's members.
        assertEquals("parse.line.continues", refusal("${bump}f: () Int32 {\n    g: () Int32 = () Int32 {\n        bump(1)\n        -1\n    }\n    g()\n}\n").tag)
        assertEquals("parse.line.continues", refusal("P: Struct {\n    a: Int32 = 1\n    b: Int32 = 2\n    (3)\n}\n").tag)
    }

    @Test
    fun anIndentedLineGoesOnAndALineAfterASemicolonStandsAlone() {
        // bibo writes a long sum with each `+` line indented: it goes on, as before.
        parse("f: (a: Str) Str {\n    want: Str = \"[\" + a\n        + \"]\"\n    want\n}\n")
        // After a statement that cannot go on (an if, a loop) a `(` line is a value of its own.
        parse("f: (n: Int32) Float64 {\n    if n < 0 {\n        trace(n)\n    }\n    (n as Float64) / 60.0\n}\n")
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val main = "\n\nmain: () Void {\n    trace(f(5))\n}\n"
        assertEquals("bump\n-1\n", run("${bump}f: (a: Int32) Int32 {\n    bump(a);\n    -1\n}$main"))
        assertEquals("bump\n14\n", run("${bump}f: (a: Int32) Int32 {\n    bump(a)\n        - 1\n}$main"))
        assertEquals("16\n", run("f: (a: Int32) Int32 {\n    t: Int32 = a * 3;\n    (t + 1)\n}$main"))
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
