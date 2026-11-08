package net.exoad.kira

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Objects on the JS backend: `this`, assignment to a member place, named
 * construction and the `initially` block, all in one run.
 */
class JSClassSemanticsTest {
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
    fun thisMemberAssignmentNamedConstructionAndInitially() {
        assertEquals(
            "16\n8\n",
            run(
                """
                P: Mutable<Any> {
                    x: Mutable<Int32> = 1
                    y: Mutable<Int32> = 2
                    initially { x = x + 10 }
                    pub sum: () Int32 { this.x + this.y }
                }
                main: () Void {
                    p: P = P { y = 5 }
                    trace(p.sum())
                    p.x = 3
                    trace(p.sum())
                }
                """,
                "test:js.class"
            ) ?: return
        )
    }

    @Test
    fun aNamedArgumentFillsARequiredConstructorField() {
        assertEquals(
            "7\n",
            run(
                """
                P: Mutable<Any> {
                    x: Int32
                    y: Int32
                    pub sum: () Int32 { this.x + this.y }
                }
                main: () Void {
                    p: P = P { x = 3, y = 4 }
                    trace(p.sum())
                }
                """,
                "test:js.classrequired"
            ) ?: return
        )
    }
}
