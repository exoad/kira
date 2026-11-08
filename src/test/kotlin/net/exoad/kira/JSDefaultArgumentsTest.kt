package net.exoad.kira

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Default-valued parameters on free functions and methods. */
class JSDefaultArgumentsTest {
    companion object {
        private var node: String? = null

        @JvmStatic
        @BeforeAll
        fun locateNode() {
            node = TestCompileSupport.findNode()
        }
    }

    private fun emit(body: String, uri: String): String =
        TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false
        )

    private fun run(body: String, uri: String): String? {
        val generated = emit(body, uri)
        val n = node ?: return null
        val result = TestCompileSupport.runJS(generated, n)
        assertEquals(0, result.exitCode, "node failed:\n${result.stderr}\n$generated")
        return result.stdout
    }

    @Test
    fun freeFunctionDefaultsFillTrailingAndSkippedMiddleParameters() {
        assertEquals(
            "6\n8\n6\n",
            run(
                """
                g: (a: Int32, b: Int32 = 2, c: Int32 = 3) Int32 { a + b + c }
                main: () Void {
                    trace(g(1))
                    trace(g(1, c = 5))
                    trace(g(1, 2))
                }
                """,
                "test:js.defaults"
            ) ?: return
        )
    }

    @Test
    fun methodDefaultsUseTheReceiverAndTheCallSlot() {
        assertEquals(
            "16\n22\n",
            run(
                """
                P: Mutable<Any> {
                    x: Int32 = 10
                    pub scaled: (v: Int32, factor: Int32 = 2) Int32 { this.x + v * factor }
                }
                main: () Void {
                    p: P = P { }
                    trace(p.scaled(3))
                    trace(p.scaled(3, 4))
                }
                """,
                "test:js.methoddefaults"
            ) ?: return
        )
    }
}
