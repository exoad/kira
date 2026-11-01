package net.exoad.kira.suite.runtime

import net.exoad.kira.suite.runtime.RuntimeRun.assertStdout
import org.junit.jupiter.api.Test

/** End to end on C: hello world, arithmetic, functions. */
class FunctionRuntimeTest {
    @Test
    fun helloWorld() {
        assertStdout("hello, kira\n") {
            """
            main: () Void {
                trace("hello, kira")
            }
            """
        }
    }

    @Test
    fun arithmeticAndOrderOfOperations() {
        assertStdout("5\n") {
            """
            main: () Void {
                a: Int32 = 1 + 2 * 3 - 4 / 2
                trace(a)
            }
            """
        }
    }

    @Test
    fun functionCallAndReturn() {
        assertStdout("8\n") {
            """
            add: (a: Int32, b: Int32) Int32 {
                a + b
            }

            main: () Void {
                trace(add(5, 3))
            }
            """
        }
    }
}
