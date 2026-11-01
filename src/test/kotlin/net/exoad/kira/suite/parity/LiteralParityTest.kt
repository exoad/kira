package net.exoad.kira.suite.parity

import net.exoad.kira.suite.parity.ParityRun.assertBothPrint
import net.exoad.kira.suite.parity.ParityRun.assertCPrints
import org.junit.jupiter.api.Test

/** Number and string literals print the same text on the C and JS backends. */
class LiteralParityTest {
    @Test
    fun hexAndBinaryIntegerLiterals() {
        assertBothPrint("255\n31\n10\n3\n4294967295\n") {
            """
            main: () Void {
                trace(0xFF)
                trace(0X1f)
                trace(0b1010)
                trace(0B11)
                trace(0xFFFFFFFF)
            }
            """
        }
    }

    @Test
    fun hexLiteralsFillInt64() {
        // JS numbers stop at 2^53, so the 64-bit edge is C only.
        assertCPrints("9223372036854775807\n-1\n-9223372036854775808\n") {
            """
            main: () Void {
                big: Int64 = 0x7FFFFFFFFFFFFFFF
                trace(big)
                neg: Int64 = 0xFFFFFFFFFFFFFFFF
                trace(neg)
                least: Int64 = 0x8000000000000000
                trace(least)
            }
            """
        }
    }

    @Test
    fun floatLiteralsWithExponents() {
        assertBothPrint("0.001\n150\n2.5\n") {
            """
            main: () Void {
                trace(1e-3)
                trace(1.5e2)
                trace(2.5E6 / 1e6)
            }
            """
        }
    }

    @Test
    fun stringEscapesLowerOnBothBackends() {
        // `\"` used to end the string on the lexer side, and the JS backend
        // re-escaped the backslash so `\n` printed as a backslash and an n.
        assertBothPrint("say \"hi\"\ntab\there\nback\\slash\ndollar \$ sign\ntwo\nlines\n") {
            """
            main: () Void {
                trace("say \"hi\"")
                trace("tab\there")
                trace("back\\slash")
                trace("dollar \${'$'} sign")
                trace("two\nlines")
            }
            """
        }
    }
}
