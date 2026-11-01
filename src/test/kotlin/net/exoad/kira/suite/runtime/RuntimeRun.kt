package net.exoad.kira.suite.runtime

import net.exoad.kira.TestCompileSupport
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Transpiles a snippet to C, compiles and runs it, and asserts exact stdout. */
object RuntimeRun {
    val compiler: String? by lazy { TestCompileSupport.findCCompiler() }

    fun run(body: String, uri: String = "test:runtime.basic"): Pair<String, String> {
        val cc = compiler ?: return "" to ""
        val generated = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc)
        // A compile failure is a compiler bug, not a missing toolchain: it
        // must FAIL, never skip. (It used to be an assumption, which hid
        // every non-compiling lowering behind a green run.)
        assertEquals(
            0,
            result.compileResult.exitCode,
            "cc failed. stderr:\n${result.compileResult.stderr}\nC:\n$generated"
        )
        val exec = assertNotNull(result.runResult, "binary did not run")
        return exec.stdout to exec.stderr
    }

    fun assertStdout(expected: String, uri: String = "test:runtime.basic", body: () -> String) {
        val (stdout, stderr) = run(body(), uri)
        assertEquals(expected, stdout, "stderr:\n$stderr")
    }
}
