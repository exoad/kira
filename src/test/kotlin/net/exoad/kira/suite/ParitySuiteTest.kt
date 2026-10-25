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

    /** C only: for what JS cannot represent (64-bit integers). */
    private fun assertCPrints(expected: String, uri: String = "test:parity.conly", body: () -> String) {
        assertEquals(expected, runC(body(), uri), "C backend stdout")
    }

    // --- shifts ----------------------------------------------------------------

    @Test
    fun shiftOperatorsAreNotSwapped() {
        // `<<` and `>>` used to be lowered as each other: 1 << 4 printed 0.
        assertBothPrint("16\n64\n-4\n2\n") {
            """
            main: () Void {
                trace(1 << 4)
                trace(256 >> 2)
                trace(-16 >> 2)
                x: Mutable<Int32> = 1
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
                x: Mutable<Int32> = -16
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

    // --- strings ---------------------------------------------------------------

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

    // --- identifiers -----------------------------------------------------------

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

    // --- element reads ---------------------------------------------------------

    @Test
    fun indexReadsUseTheElementType() {
        // `a[i]` always read as Int32, so an Arr<Str> element was a truncated pointer.
        assertBothPrint("bob\n3\n1\n6\n") {
            """
            main: () Void {
                names: Arr<Str> = @arrOf("ada", "bob")
                trace(names[1])
                trace(names[0].length())
                flags: Arr<Bool> = @arrOf(true, false)
                trace(flags[0])
                nums: List<Int32> = List<Int32> { }
                nums.add(5)
                nums.add(6)
                trace(nums[1])
            }
            """
        }
    }

    @Test
    fun indexReadsOfInt64Elements() {
        assertCPrints("4294967296\n") {
            """
            main: () Void {
                wide: Arr<Int64> = @arrOf(4294967296)
                trace(wide[0])
            }
            """
        }
    }

    // --- for over containers ---------------------------------------------------

    @Test
    fun forInIteratesArrListAndSet() {
        // The body used to run exactly once whatever the container held.
        assertBothPrint("6\nada\ngrace\n7\n3\n") {
            """
            main: () Void {
                xs: Arr<Int32> = @arrOf(1, 2, 3)
                sum: Mutable<Int32> = 0
                for x: Int32 in xs {
                    sum = sum + x
                }
                trace(sum)

                names: List<Str> = List<Str> { }
                names.add("ada")
                names.add("grace")
                for n: Str in names {
                    trace(n)
                }

                seen: Set<Int32> = Set<Int32> { }
                seen.add(3)
                seen.add(3)
                seen.add(4)
                total: Mutable<Int32> = 0
                for v: Int32 in seen {
                    total = total + v
                }
                trace(total)

                empty: List<Int32> = List<Int32> { }
                ran: Mutable<Int32> = 0
                for e: Int32 in empty {
                    ran = ran + 1
                }
                trace(3 - ran)
            }
            """
        }
    }

    // --- named arguments -------------------------------------------------------

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

    // --- bare return -----------------------------------------------------------

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
