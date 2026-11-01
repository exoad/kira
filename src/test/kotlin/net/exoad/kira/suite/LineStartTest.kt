package net.exoad.kira.suite

import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

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
