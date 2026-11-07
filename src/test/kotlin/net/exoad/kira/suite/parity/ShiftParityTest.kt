package net.exoad.kira.suite.parity

import net.exoad.kira.suite.parity.ParityRun.assertBothPrint
import net.exoad.kira.suite.parity.ParityRun.assertCPrints
import org.junit.jupiter.api.Test

/** Shift operators print the same text on the C and JS backends. */
class ShiftParityTest {
    @Test
    fun shiftOperatorsAreNotSwapped() {
        // `<<` and `>>` used to be lowered as each other: 1 << 4 printed 0.
        assertBothPrint("16\n64\n-4\n2\n") {
            """
            main: () Void {
                trace(1 << 4)
                trace(256 >> 2)
                trace(-16 >> 2)
                x: Mutable<Int32> { 1 }
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
            main: () Void {
                trace(-16 >>> 28)
                trace(-16 >>> 2)
                x: Mutable<Int32> { -16 }
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
            main: () Void {
                y: Int64 { -1 }
                trace(y >>> 60)
                trace(y >>> 4)
            }
            """
        }
    }
}
