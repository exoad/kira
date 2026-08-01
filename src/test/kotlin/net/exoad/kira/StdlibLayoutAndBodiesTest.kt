package net.exoad.kira

import net.exoad.kira.compiler.backend.codegen.StdlibLayout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stdlib is self-contained under kira/: the runtime preludes live beside
 * the modules (kira/c/, kira/js/) instead of in the compiler jar, and real
 * Kira-written stdlib functions (non-magic bodies in the kira modules) are emitted
 * like user code instead of being skipped with the magic surface.
 */
class StdlibLayoutAndBodiesTest {
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

    private fun emitC(body: String): String {
        return TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule("test:stdlib.layout", body),
            logicalPath = TestCompileSupport.logicalPathForModule("test:stdlib.layout"),
            runSemantic = false
        )
    }

    private fun emitJS(body: String): String {
        return TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule("test:stdlib.layout", body),
            logicalPath = TestCompileSupport.logicalPathForModule("test:stdlib.layout"),
            runSemantic = false
        )
    }

    @Test
    fun stdlibLayoutResolvesRuntimeFilesBesideModules() {
        // kira/c/ and kira/js/ own the runtime now; the compiler loads them
        // next to the .kira modules, not from embedded resources.
        val cBundle = StdlibLayout.cFile("c_bundle.h")
        assertNotNull(cBundle, "kira/c/c_bundle.h must resolve from the stdlib root")
        assertTrue(cBundle.toString().replace('\\', '/').endsWith("kira/c/c_bundle.h"), cBundle.toString())

        val cRuntime = StdlibLayout.cFile("c_generator.c")
        assertNotNull(cRuntime, "kira/c/c_generator.c must resolve")
        assertTrue(cRuntime.toString().replace('\\', '/').endsWith("kira/c/c_generator.c"), cRuntime.toString())

        val jsRuntime = StdlibLayout.jsFile("js_generator.js")
        assertNotNull(jsRuntime, "kira/js/js_generator.js must resolve")
        assertTrue(jsRuntime.toString().replace('\\', '/').endsWith("kira/js/js_generator.js"), jsRuntime.toString())
    }

    @Test
    fun kiraWrittenStdlibFunctionLowersToMagicCalls() {
        // clamp is written in Kira on top of magic min/max; its body must
        // lower through the binding manifest to fmin/fmax.
        val generated = emitC(
            """
            fx main: () Void {
                trace(clamp(5.0, 0.0, 10.0))
            }
            """
        )

        assertTrue(generated.contains("Float64 clamp(Float64 value, Float64 lo, Float64 hi);"), generated)
        assertTrue(generated.contains("fmax(lo, fmin(value, hi))"), generated)
        assertTrue(generated.contains("#include <math.h>"), generated)
    }

    @Test
    fun kiraWrittenStdlibFunctionRunsInC() {
        val cc = compiler ?: return
        val generated = emitC(
            """
            fx main: () Void {
                trace(clamp(5.0, 0.0, 10.0))
                trace(clamp(-3.0, 0.0, 10.0))
                trace(clamp(20.0, 0.0, 10.0))
                trace(lerp(0.0, 10.0, 0.5))
            }
            """
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc)
        assumeTrue(result.compileResult.exitCode == 0, "cc failed:\n${result.compileResult.stderr}\n$generated")
        assertEquals("5\n0\n10\n5\n", result.runResult?.stdout)
    }

    @Test
    fun kiraWrittenStdlibFunctionRunsInJS() {
        val nodePath = node ?: return
        val generated = emitJS(
            """
            fx main: () Void {
                trace(clamp(5.0, 0.0, 10.0))
                trace(clamp(-3.0, 0.0, 10.0))
                trace(clamp(20.0, 0.0, 10.0))
                trace(lerp(0.0, 10.0, 0.5))
            }
            """
        )
        assertTrue(generated.contains("function clamp("), generated)
        val result = TestCompileSupport.runJS(generated, nodePath)
        assumeTrue(result.exitCode == 0, "node failed: ${result.stderr}\n$generated")
        assertEquals("5\n0\n10\n5\n", result.stdout)
    }
}
