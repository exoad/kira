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
        assertEquals(expectedLines, run.stdout.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }, run.stderr)
    }

    @Test
    fun jsProgramRunsWithTheUsersDeclarations() {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val generated = TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(moduleUri))
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(expectedLines, run.stdout.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }, run.stderr)
    }
}
