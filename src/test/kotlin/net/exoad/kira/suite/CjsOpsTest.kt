package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The member operators, the synthesized `==`, `copy` and the index forms on `--target c` and
 * `--target js` (w2-9-4-ops-cjs). `src/test/resources/cjs-ops/main.kira` uses every member operator
 * of 20-revision.md 1.3.1, `==` on an immutable class, a mutable one and a hierarchy compared both
 * ways, `copy` with an impure receiver, the List, Arr and Map index forms, and the free form; its
 * `expected.txt` was checked by hand against the rules, and both backends must print it. W2.8 runs
 * the same program through the C++ leg.
 */
class CjsOpsTest {

    companion object {
        private var compiler: String? = null
        private var node: String? = null

        @JvmStatic
        @BeforeAll
        fun locateToolchains() {
            compiler = TestCompileSupport.findCCompiler()
            node = TestCompileSupport.findNode()
        }
    }

    private val dir = File("src/test/resources/cjs-ops")

    private fun source(name: String): String = File(dir, name).readText()

    private fun logicalPath(): String = TestCompileSupport.logicalPathForModule("test:cjsops.main")

    private fun runC(source: String): TestCompileSupport.NativeExecutionResult {
        val cc = compiler
        assumeTrue(cc != null, "No C compiler on PATH (set CC)")
        val generated = TestCompileSupport.transpileSnippetToC(source, logicalPath(), runSemantic = true)
        val result = TestCompileSupport.compileAndRunC(generated, cc!!)
        assertEquals(0, result.compileResult.exitCode, "cc failed. stderr:\n${result.compileResult.stderr}")
        return result
    }

    private fun runJS(source: String): TestCompileSupport.ProcessResult {
        val n = node
        assumeTrue(n != null, "No node on PATH (set NODE)")
        val generated = TestCompileSupport.transpileSnippetToJS(source, logicalPath(), runSemantic = true)
        return TestCompileSupport.runJS(generated, n!!)
    }

    @Test
    fun theProgramPrintsExpectedTxtOnC() {
        val result = runC(source("main.kira"))
        val run = assertNotNull(result.runResult, "binary did not run")
        assertEquals(0, run.exitCode, "binary failed. stderr:\n${run.stderr}")
        assertEquals(source("expected.txt"), run.stdout, "C backend stdout")
    }

    @Test
    fun theProgramPrintsExpectedTxtOnJS() {
        val run = runJS(source("main.kira"))
        assertEquals(0, run.exitCode, "node failed. stderr:\n${run.stderr}")
        assertEquals(source("expected.txt"), run.stdout, "JS backend stdout")
    }

    @Test
    fun aMapCompoundAssignmentOnAMissingKeyPanicsOnC() {
        val result = runC(source("missing-key.kira"))
        val run = assertNotNull(result.runResult, "binary did not run")
        assertNotEquals(0, run.exitCode, "a missing key must end the program abnormally")
        assertEquals(source("missing-key.expected.txt"), run.stdout, "what ran before the panic")
        assertTrue(run.stderr.contains("no such key in the Map"), "stderr: ${run.stderr}")
    }

    @Test
    fun aMapCompoundAssignmentOnAMissingKeyPanicsOnJS() {
        val run = runJS(source("missing-key.kira"))
        assertNotEquals(0, run.exitCode, "a missing key must end the program abnormally")
        assertEquals(source("missing-key.expected.txt"), run.stdout, "what ran before the panic")
        assertTrue(run.stderr.contains("no such key in the Map"), "stderr: ${run.stderr}")
    }

    @Test
    fun aComputedIndexOrOriginCompoundAssignmentRunsOnC() {
        val run = assertNotNull(runC(source("computed-index.kira")).runResult)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(source("computed-index.expected.txt"), run.stdout)
    }

    @Test
    fun aComputedIndexOrOriginCompoundAssignmentRunsOnJS() {
        val run = runJS(source("computed-index.kira"))
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(source("computed-index.expected.txt"), run.stdout)
    }

    @Test
    fun copyEvaluatesItsReceiverFirstOnC() {
        val run = assertNotNull(runC(source("copy-order.kira")).runResult)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(source("copy-order.expected.txt"), run.stdout)
    }

    @Test
    fun copyEvaluatesItsReceiverFirstOnJS() {
        val run = runJS(source("copy-order.kira"))
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(source("copy-order.expected.txt"), run.stdout)
    }

    @Test
    fun theSameMessageOnBothBackendsAsTheCppRuntime() {
        // 20-revision.md 1.3.3 (Q12): "no such key in the Map" is the C++ runtime's text
        // (kira/cpp/kira/rt.hxx); C and JS say it with the same words.
        val cpp = File("kira/cpp/kira/rt.hxx").readText()
        assertTrue(cpp.contains("no such key in the Map"))
        val c = File("kira/c/c_generator.c").readText()
        val js = File("kira/js/js_generator.js").readText()
        assertTrue(c.contains("kira: no such key in the Map"))
        assertTrue(js.contains("kira: no such key in the Map"))
    }
}
