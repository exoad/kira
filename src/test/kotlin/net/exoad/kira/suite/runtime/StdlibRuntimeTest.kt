package net.exoad.kira.suite.runtime

import net.exoad.kira.suite.runtime.RuntimeRun.assertStdout
import org.junit.jupiter.api.Test

/** End to end on C: Str and Num helpers and assert. */
class StdlibRuntimeTest {
    @Test
    fun stringMethods() {
        assertStdout("4\nKIRA\n1\n") {
            """
            main: () Void {
                s: Str = "  Kira  "
                t: Str = s.trim()
                trace(t.length())
                trace(t.toUpper())
                trace(t.startsWith("Ki"))
            }
            """
        }
    }

    @Test
    fun numConversion() {
        assertStdout("7\n") {
            """
            main: () Void {
                n: Int32 = 7
                trace(n.toInt64())
            }
            """
        }
    }

    @Test
    fun assertHelper() {
        assertStdout("ok\n") {
            """
            main: () Void {
                assert(true, "fine")
                trace("ok")
            }
            """
        }
    }
}
