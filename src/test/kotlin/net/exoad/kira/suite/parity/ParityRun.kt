package net.exoad.kira.suite.parity

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs a snippet on the C backend and the JS backend (a missing toolchain skips by assumption). */
object ParityRun {
    private val compiler: String? by lazy { TestCompileSupport.findCCompiler() }
    private val node: String? by lazy { TestCompileSupport.findNode() }

    private fun runC(body: String, uri: String): String {
        val cc = compiler
        assumeTrue(cc != null, "No C compiler on PATH (set CC)")
        val generated = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc!!)
        assertEquals(
            0,
            result.compileResult.exitCode,
            "cc failed. stderr:\n${result.compileResult.stderr}\nC:\n$generated"
        )
        val exec = assertNotNull(result.runResult, "binary did not run")
        assertEquals(0, exec.exitCode, "binary failed. stderr:\n${exec.stderr}\nstdout:\n${exec.stdout}\nC:\n$generated")
        return exec.stdout
    }

    private fun runJS(body: String, uri: String): String {
        val n = node
        assumeTrue(n != null, "No node on PATH (set NODE)")
        val generated = TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
        val result = TestCompileSupport.runJS(generated, n!!)
        assertEquals(0, result.exitCode, "node failed. stderr:\n${result.stderr}\nJS:\n$generated")
        return result.stdout
    }

    /** Both backends must print [expected], byte for byte. */
    fun assertBothPrint(expected: String, uri: String = "test:parity.basic", body: () -> String) {
        val program = body()
        assertEquals(expected, runC(program, uri), "C backend stdout")
        assertEquals(expected, runJS(program, uri), "JS backend stdout")
    }

    /** C only: for what JS cannot represent (64-bit integers). */
    fun assertCPrints(expected: String, uri: String = "test:parity.conly", body: () -> String) {
        assertEquals(expected, runC(body(), uri), "C backend stdout")
    }
}
