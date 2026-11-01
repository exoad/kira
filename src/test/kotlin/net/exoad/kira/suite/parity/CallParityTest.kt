package net.exoad.kira.suite.parity

import net.exoad.kira.suite.parity.ParityRun.assertBothPrint
import org.junit.jupiter.api.Test

/** Names, named arguments and bare return print the same text on the C and JS backends. */
class CallParityTest {
    @Test
    fun upperSnakeCaseNamesDoNotCollideWithGeneratedOnes() {
        // Any `_` used to panic the lexer. Constants and fields may carry them now.
        assertBothPrint("99\n5\n") {
            """
            MODE_DRIVE: Int32 = 99

            Limits: Any {
                pub MAX_X: Int32
            }

            main: () Void {
                trace(MODE_DRIVE)
                l: Limits = Limits { 5 }
                trace(l.MAX_X)
            }
            """
        }
    }

    @Test
    fun namedArgumentsBindByNameNotByPosition() {
        // `sub(b = 3, a = 10)` used to lower as sub(3, 10): names were ignored.
        assertBothPrint("7\n7\n7\n2\n10\n") {
            """
            sub: (a: Int32, b: Int32) Int32 {
                a - b
            }

            Esc: Mutable<Any> {
                pub pulse: Mutable<Int32>
                pub set: (v: Int32, scale: Int32) Void {
                    pulse = v * scale
                }
                pub get: () Int32 {
                    pulse
                }
            }

            main: () Void {
                trace(sub(b = 3, a = 10))
                trace(sub(10, b = 3))
                trace(sub(a = 10, b = 3))
                e: Esc = Esc { 0 }
                e.set(scale = 2, v = 1)
                trace(e.get())
                trace(clamp(hi = 10.0, lo = 0.0, value = 15.0))
            }
            """
        }
    }

    @Test
    fun bareReturnLeavesAVoidFunction() {
        assertBothPrint("before\nafter\n") {
            """
            early: (n: Int32) Void {
                if !(n > 0) {
                    trace("never")
                }
            }

            main: () Void {
                trace("before")
                early(1)
                trace("after")
            }
            """
        }
    }
}
