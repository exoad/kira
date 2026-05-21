package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ParserBackend
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendCompilationPipelineTest {
    @Test
    fun transpilesExistingSampleFileDirectlyToC() {
        val generated = TestCompileSupport.transpileFileToC(
            filePath = "test_kira/sub/test.kira",
            parserBackend = ParserBackend.LEGACY,
            runSemantic = true
        )

        assertTrue(generated.contains("typedef enum TestEnum"))
        assertTrue(generated.contains("A"))
        assertTrue(generated.contains("B"))
        assertTrue(generated.contains("C"))
    }

    @Test
    fun transpilesInlineSampleToCAndValidatesFragments() {
        val moduleUri = "test:backend.sample"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx add(a: Int32, b: Int32): Int32 {
                return a + b
            }

            fx main(): Void {
                trace("OK\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )

        assertTrue(generated.contains("typedef int32_t KInt;"))
        assertTrue(generated.contains("#include <stdio.h>"))
        assertTrue(generated.contains("KInt add(KInt a, KInt b)"))
        assertTrue(generated.contains("int main()"))
        assertTrue(generated.contains("printf(\"OK\\\\n\")"))
    }

    @Test
    fun generatedCPassesCompilerSyntaxCheckForSampleProgram() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.syntax"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx sum(a: Int32, b: Int32): Int32 {
                return a + b
            }

            fx main(): Void {
                mut v: Int32 = sum(3, 4)
                trace("syntax-ok\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )

        val syntaxResult = TestCompileSupport.syntaxCheckC(generated, compiler!!)
        assertEquals(0, syntaxResult.exitCode, "stdout:\n${syntaxResult.stdout}\nstderr:\n${syntaxResult.stderr}")
    }

    @Test
    fun generatedCFromSampleFilePassesSyntaxCheckWhenCompilerExists() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val generated = TestCompileSupport.transpileFileToC(
            filePath = "test_kira/sub/test.kira",
            parserBackend = ParserBackend.LEGACY,
            runSemantic = true
        )

        val syntaxResult = TestCompileSupport.syntaxCheckC(generated, compiler!!)
        assertEquals(0, syntaxResult.exitCode, "stdout:\n${syntaxResult.stdout}\nstderr:\n${syntaxResult.stderr}")
    }

    @Test
    fun generatedCCompilesAndRunsForDeterministicOutput() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.run"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx main(): Void {
                trace("runtime-ok\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )

        val runResult = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assumeTrue(
            runResult.compileResult.exitCode == 0,
            "Compile step failed. stdout:\n${runResult.compileResult.stdout}\nstderr:\n${runResult.compileResult.stderr}"
        )

        val exec = runResult.runResult
        assertNotNull(exec)
        assertEquals(0, exec.exitCode, "stdout:\n${exec.stdout}\nstderr:\n${exec.stderr}")
        assertTrue(exec.stdout.contains("runtime-ok"))
    }
}
