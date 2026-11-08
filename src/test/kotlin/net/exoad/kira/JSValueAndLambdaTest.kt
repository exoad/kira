package net.exoad.kira

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** If-expressions (block and ternary) and lambdas as values. */
class JSValueAndLambdaTest {
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
    fun ifExpressionsProduceTheirBranchValueInLocals() {
        assertEquals(
            "15\n27\n",
            run(
                """
                pick: (c: Bool) Int32 {
                    a: Int32 = c ? 10 : 20
                    b: Int32 = if c {
                        x: Int32 = 5
                        x
                    } else {
                        y: Int32 = 7
                        y
                    }
                    a + b
                }
                main: () Void {
                    trace(pick(true))
                    trace(pick(false))
                }
                """,
                "test:js.ifvalue"
            ) ?: return
        )
    }

    @Test
    fun lambdasAreValuesAndReturnTheirLastExpression() {
        assertEquals(
            "42\n40\n",
            run(
                """
                twice: (f: Fx<T1<Int32>, Int32>, v: Int32) Int32 { f(f(v)) }
                main: () Void {
                    inc: Fx<T1<Int32>, Int32> = (x: Int32) Int32 { x + 1 }
                    trace(inc(41))
                    trace(twice((x: Int32) Int32 { x * 2 }, 10))
                }
                """,
                "test:js.lambda"
            ) ?: return
        )
    }
}
