package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for miscompiles that were silent: every program here
 * runs on the C backend (native toolchain) and the JS backend (node) and
 * must print exactly the same text. A program a backend cannot run is not a
 * green test; it fails.
 *
 * Only a missing toolchain may skip a test (JUnit assumption), and then the
 * message says which one.
 */
class ParitySuiteTest {

    companion object {
        private var compiler: String? = null
        private var node: String? = null

        @JvmStatic
        @BeforeAll
        fun locateToolchains() {
            compiler = TestCompileSupport.findCCompiler()
            node = TestCompileSupport.findNode()
        }
    }

    private fun runC(body: String, uri: String): String {
        val cc = compiler
        assumeTrue(cc != null, "No C compiler on PATH (set CC)")
        val generated = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc!!)
        assertEquals(
            0,
            result.compileResult.exitCode,
            "cc failed. stderr:\n${result.compileResult.stderr}\nC:\n$generated"
        )
        val exec = assertNotNull(result.runResult, "binary did not run")
        assertEquals(0, exec.exitCode, "binary failed. stderr:\n${exec.stderr}\nstdout:\n${exec.stdout}\nC:\n$generated")
        return exec.stdout
    }

    private fun runJS(body: String, uri: String): String {
        val n = node
        assumeTrue(n != null, "No node on PATH (set NODE)")
        val generated = TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
        val result = TestCompileSupport.runJS(generated, n!!)
        assertEquals(0, result.exitCode, "node failed. stderr:\n${result.stderr}\nJS:\n$generated")
        return result.stdout
    }

    /** Both backends must print [expected], byte for byte. */
    private fun assertBothPrint(expected: String, uri: String = "test:parity.basic", body: () -> String) {
        val program = body()
        assertEquals(expected, runC(program, uri), "C backend stdout")
        assertEquals(expected, runJS(program, uri), "JS backend stdout")
    }

    /** C only -- for what JS cannot represent (64-bit integers). */
    private fun assertCPrints(expected: String, uri: String = "test:parity.conly", body: () -> String) {
        assertEquals(expected, runC(body(), uri), "C backend stdout")
    }

    // --- shifts ----------------------------------------------------------------

    @Test
    fun shiftOperatorsAreNotSwapped() {
        // `<<` and `>>` used to be lowered as each other: 1 << 4 printed 0.
        assertBothPrint("16\n64\n-4\n2\n") {
            """
            fx main: () Void {
                trace(1 << 4)
                trace(256 >> 2)
                trace(-16 >> 2)
                mut x: Int32 = 1
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
            fx main: () Void {
                trace(-16 >>> 28)
                trace(-16 >>> 2)
                mut x: Int32 = -16
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
            fx main: () Void {
                y: Int64 = -1
                trace(y >>> 60)
                trace(y >>> 4)
            }
            """
        }
    }

    // --- literals --------------------------------------------------------------

    @Test
    fun hexAndBinaryIntegerLiterals() {
        assertBothPrint("255\n31\n10\n3\n4294967295\n") {
            """
            fx main: () Void {
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
            fx main: () Void {
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
            fx main: () Void {
                trace(1e-3)
                trace(1.5e2)
                trace(2.5E6 / 1e6)
            }
            """
        }
    }

    // --- strings ---------------------------------------------------------------

    @Test
    fun stringEscapesLowerOnBothBackends() {
        // `\"` used to end the string on the lexer side, and the JS backend
        // re-escaped the backslash so `\n` printed as a backslash and an n.
        assertBothPrint("say \"hi\"\ntab\there\nback\\slash\ndollar \$ sign\ntwo\nlines\n") {
            """
            fx main: () Void {
                trace("say \"hi\"")
                trace("tab\there")
                trace("back\\slash")
                trace("dollar \${'$'} sign")
                trace("two\nlines")
            }
            """
        }
    }

    // --- identifiers -----------------------------------------------------------

    @Test
    fun upperSnakeCaseNamesDoNotCollideWithGeneratedOnes() {
        // Any `_` used to panic the lexer. Constants, fields and enum members
        // may carry them now, and the C backend keeps them clear of the names
        // it generates (MODE_DRIVE is what `enum Mode { DRIVE }` becomes).
        assertBothPrint("99\n0\n5\n4\n") {
            """
            pub enum Mode {
                DRIVE
            }

            pub enum Gear {
                LOW_RANGE,
                HIGH_RANGE = 4
            }

            MODE_DRIVE: Int32 = 99

            class Limits {
                require pub MAX_X: Int32
            }

            fx main: () Void {
                trace(MODE_DRIVE)
                trace(Mode.DRIVE)
                l: Limits = Limits { 5 }
                trace(l.MAX_X)
                trace(Gear.HIGH_RANGE)
            }
            """
        }
    }

    // --- enums -----------------------------------------------------------------

    @Test
    fun enumExplicitValuesAreHonoured() {
        // `DRIVE = 7` used to print 1: values were dropped and members renumbered.
        assertBothPrint("0\n7\n8\n-2\nbrake\n") {
            """
            pub enum Mode {
                IDLE,
                DRIVE = 7,
                BRAKE,
                REVERSE = -2
            }

            fx main: () Void {
                trace(Mode.IDLE)
                trace(Mode.DRIVE)
                m: Mode = Mode.BRAKE
                trace(m)
                trace(Mode.REVERSE)
                if m == Mode.BRAKE {
                    trace("brake")
                }
            }
            """
        }
    }

    @Test
    fun enumsWithBaseTypes() {
        assertBothPrint("5\nhigh\n100\nyes\n") {
            """
            pub enum Status: Int32 {
                PENDING = 0,
                ACTIVE = 5
            }

            pub enum Priority: Str {
                LOW = "low",
                HIGH = "high"
            }

            pub enum Threshold: Float64 {
                MIN = 0.5,
                MAX = 100.0
            }

            fx main: () Void {
                trace(Status.ACTIVE)
                p: Priority = Priority.HIGH
                trace(p)
                trace(Threshold.MAX)
                if p == Priority.HIGH {
                    trace("yes")
                }
            }
            """
        }
    }

    // --- bare return -----------------------------------------------------------

    @Test
    fun bareReturnLeavesAVoidFunction() {
        assertBothPrint("before\nafter\n") {
            """
            fx early: (n: Int32) Void {
                if n > 0 {
                    return
                }
                trace("never")
            }

            fx main: () Void {
                trace("before")
                early(1)
                trace("after")
                return
            }
            """
        }
    }
}
