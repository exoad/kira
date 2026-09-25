package net.exoad.kira

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A module's own declaration shadows an ambient `kira:*` magic name (the
 * typer's scope order puts module members before the ambient stdlib), so a
 * user `class Ref` or `fx parseInt64` must be emitted and called.
 *
 * Both backends used to skip any declaration whose *name* was in the magic
 * set, whatever source it came from: the program then referenced a `Ref`
 * and a `parseInt64` that nothing defined, the CLI exited 0, and the C
 * compiler or node failed later. Every magic name the stdlib adds widens
 * that hole, so this pins the fix in both backends.
 *
 * The call side has the same rule: a name the unit declares is called as
 * itself, never lowered through the magic table (`ceil` is the user's, not
 * `Math.ceil`). The C program for that case is emitted correctly but is not
 * run here: the C backend emits user functions under their Kira names, so a
 * user `ceil` collides with libc's, which gcc and clang treat as a builtin
 * (undefined behaviour: gcc 13.2 printed 2 and clang printed 0e+00 from a
 * `ceil` that returns 101.5). That is the C backend's unmangled symbols, a
 * separate defect a user `fx strlen` has too.
 */
class UserDeclarationShadowsMagicNameTest {
    private val moduleUri = "test:shadow.magic"
    private val source = TestCompileSupport.wrapModule(
        moduleUri,
        """
        class Ref {
            require pub id: Int32
        }

        fx parseInt64: (x: Int32) Int32 {
            return x + 1
        }

        fx main: () Void {
            r: Ref = Ref { 4 }
            trace(r.id)
            trace(parseInt64(3))
            trace(parseInt64(9))
        }
        """
    )
    private val expectedLines = listOf("4", "4", "10")

    private val mathNameUri = "test:shadow.mathname"
    private val mathNameSource = TestCompileSupport.wrapModule(
        mathNameUri,
        """
        fx ceil: (x: Float64) Float64 {
            return x + 100.0
        }

        fx main: () Void {
            trace(ceil(1.5))
        }
        """
    )

    @Test
    fun cEmitsTheUsersDeclarations() {
        val generated = TestCompileSupport.transpileSnippetToC(source, TestCompileSupport.logicalPathForModule(moduleUri))
        assertTrue(Regex("struct Ref\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(Regex("Int32 parseInt64\\(Int32 x\\)\\s*\\{").containsMatchIn(generated), generated)
    }

    @Test
    fun jsEmitsTheUsersDeclarations() {
        val generated = TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(moduleUri))
        assertTrue(Regex("class Ref\\b").containsMatchIn(generated), generated)
        assertTrue(Regex("function parseInt64\\(x\\)").containsMatchIn(generated), generated)
    }

    @Test
    fun cProgramRunsWithTheUsersDeclarations() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")
        val generated = TestCompileSupport.transpileSnippetToC(source, TestCompileSupport.logicalPathForModule(moduleUri))
        val result = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assertEquals(0, result.compileResult.exitCode, result.compileResult.stderr)
        val run = assertNotNull(result.runResult)
        assertEquals(expectedLines, outputLines(run.stdout), run.stderr)
    }

    @Test
    fun jsProgramRunsWithTheUsersDeclarations() {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val generated = TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(moduleUri))
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(expectedLines, outputLines(run.stdout), run.stderr)
    }

    @Test
    fun cCallsTheUsersFunctionOverTheMagicTable() {
        val generated = TestCompileSupport.transpileSnippetToC(mathNameSource, TestCompileSupport.logicalPathForModule(mathNameUri))
        assertTrue(Regex("Float64 ceil\\(Float64 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("ceil(1.5)"), generated)
    }

    @Test
    fun jsCallsTheUsersFunctionOverTheMagicTable() {
        val generated = TestCompileSupport.transpileSnippetToJS(mathNameSource, TestCompileSupport.logicalPathForModule(mathNameUri))
        assertTrue(Regex("function ceil\\(x\\)").containsMatchIn(generated), generated)
        assertTrue(generated.contains("ceil(1.5)") && !generated.contains("Math.ceil"), generated)

        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(listOf("101.5"), outputLines(run.stdout), run.stderr)
    }

    private fun outputLines(stdout: String): List<String> {
        return stdout.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }
}
