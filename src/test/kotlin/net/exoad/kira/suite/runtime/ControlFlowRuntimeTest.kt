package net.exoad.kira.suite.runtime

import net.exoad.kira.suite.runtime.RuntimeRun.assertStdout
import org.junit.jupiter.api.Test

/** End to end on C: branches and loops. */
class ControlFlowRuntimeTest {
    @Test
    fun ifElseBranches() {
        assertStdout("two\n") {
            """
            main: () Void {
                x: Int32 = 2
                if x == 1 {
                    trace("one")
                } else {
                    trace("two")
                }
            }
            """
        }
    }

    @Test
    fun whileLoop() {
        assertStdout("0\n1\n2\n3\n4\n") {
            """
            main: () Void {
                i: Mutable<Int32> = 0
                while i < 5 {
                    trace(i)
                    i = i + 1
                }
            }
            """
        }
    }

    @Test
    fun forRangeLoop() {
        assertStdout("0\n1\n2\n3\n") {
            """
            main: () Void {
                for i: Int32 in 0.until(4) {
                    trace(i)
                }
            }
            """
        }
    }

    @Test
    fun breakAndContinue() {
        assertStdout("1\n2\n3\n") {
            """
            main: () Void {
                for i: Int32 in 0.until(11) {
                    if i == 0 {
                        continue
                    }
                    if i == 4 {
                        break
                    }
                    trace(i)
                }
            }
            """
        }
    }
}
