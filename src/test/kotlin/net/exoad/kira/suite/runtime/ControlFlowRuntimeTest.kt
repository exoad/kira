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
                x: Int32 { 2 }
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
                i: Mutable<Int32> { 0 }
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
                while i: Int32 in 0..3 {
                    trace(i)
                }
            }
            """
        }
    }

    @Test
    fun aLeftOutRangeLoopStopsBelowItsEndAndTakesNoStepAtAnUnsignedZero() {
        assertStdout("0\n1\n2\n0\n0\n3\n") {
            """
            main: () Void {
                while i: Int32 in 0..<3 {
                    trace(i)
                }
                n: Mutable<Int32> { 0 }
                z8: UInt8 { 0 }
                while a: UInt8 in 0..<z8 {
                    n += 1
                }
                trace(n)
                z32: UInt32 { 0 }
                while b: UInt32 in 0..<z32 {
                    n += 1
                }
                trace(n)
                while c: Int32 in 0..<10 {
                    if c == 1 {
                        continue
                    }
                    if c == 3 {
                        break
                    }
                    n += 1
                }
                trace(n + 1)
            }
            """
        }
    }

    @Test
    fun breakAndContinue() {
        assertStdout("1\n2\n3\n") {
            """
            main: () Void {
                while i: Int32 in 0..10 {
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
