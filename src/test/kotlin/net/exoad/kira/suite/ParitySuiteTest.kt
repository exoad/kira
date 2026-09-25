package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for miscompiles that were silent: every program here
 * runs on the C backend (native toolchain) and the JS backend (node) and
 * must print exactly the same text. A program a backend cannot run is not a
 * green test; it fails.
 *
 * Only a missing toolchain may skip a test (JUnit assumption), and then the
 * message says which one.
 */
class ParitySuiteTest {

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
    private fun assertBothPrint(expected: String, uri: String = "test:parity.basic", body: () -> String) {
        val program = body()
        assertEquals(expected, runC(program, uri), "C backend stdout")
        assertEquals(expected, runJS(program, uri), "JS backend stdout")
    }

    /** C only -- for what JS cannot represent (64-bit integers). */
    private fun assertCPrints(expected: String, uri: String = "test:parity.conly", body: () -> String) {
        assertEquals(expected, runC(body(), uri), "C backend stdout")
    }

    // --- shifts ----------------------------------------------------------------

    @Test
    fun shiftOperatorsAreNotSwapped() {
        // `<<` and `>>` used to be lowered as each other: 1 << 4 printed 0.
        assertBothPrint("16\n64\n-4\n2\n") {
            """
            fx main: () Void {
                trace(1 << 4)
                trace(256 >> 2)
                trace(-16 >> 2)
                mut x: Int32 = 1
                x <<= 1
                trace(x)
            }
            """
        }
    }

    @Test
    fun unsignedShiftRightIsLogical() {
        // `>>>` used to emit `>>>` into C, which does not compile. It is a
        // logical shift over the operand's width on both backends.
        assertBothPrint("15\n1073741820\n15\n") {
            """
            fx main: () Void {
                trace(-16 >>> 28)
                trace(-16 >>> 2)
                mut x: Int32 = -16
                x >>>= 28
                trace(x)
            }
            """
        }
    }

    @Test
    fun unsignedShiftRightOnInt64UsesTheFullWidth() {
        assertCPrints("15\n1152921504606846975\n") {
            """
            fx main: () Void {
                y: Int64 = -1
                trace(y >>> 60)
                trace(y >>> 4)
            }
            """
        }
    }

    // --- bare return -----------------------------------------------------------

    @Test
    fun bareReturnLeavesAVoidFunction() {
        assertBothPrint("before\nafter\n") {
            """
            fx early: (n: Int32) Void {
                if n > 0 {
                    return
                }
                trace("never")
            }

            fx main: () Void {
                trace("before")
                early(1)
                trace("after")
                return
            }
            """
        }
    }
}
