package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypeDiagnostic
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `.value` of a Maybe read with no `isNull()` check on the same Maybe earlier in the same block or an enclosing
 * block of the function: a warning that names the variable. The runtime panic stays; the warning is not an error.
 */
class MaybeValueWarningTest {
    private val code = "types.maybe.unchecked-value"

    /** The program's unchecked-value warnings for [body], after checking that it has no error. */
    private fun unchecked(body: String): List<TypeDiagnostic> {
        val program = TyperTestSupport.snippet(body.trimIndent())
        assertTrue(program.diagnostics.none { it.isError }, program.diagnostics.map { it.message }.toString())
        return program.diagnostics.filter { it.code == code }
    }

    @Test
    fun aValueReadWithNoCheckWarnsAndNamesTheVariable() {
        val found = unchecked(
            """
            f: (m: Maybe<Int32>) Int32 {
                m.value
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
        assertEquals(Severity.WARNING, found[0].severity)
        assertTrue(found[0].message.contains("'m'"), found[0].message)
    }

    @Test
    fun aCheckEarlierInTheSameBlockCounts() {
        assertEquals(
            emptyList(),
            unchecked(
                """
                f: (m: Maybe<Int32>) Int32 {
                    if m.isNull() {
                        trace(0)
                    }
                    m.value
                }
                """
            ),
        )
    }

    @Test
    fun aCheckEarlierInAnEnclosingBlockCounts() {
        assertEquals(
            emptyList(),
            unchecked(
                """
                f: (m: Maybe<Int32>, flag: Bool) Int32 {
                    if m.isNull() {
                        trace(0)
                    }
                    if flag {
                        trace(m.value)
                    }
                    0
                }
                """
            ),
        )
    }

    @Test
    fun aCheckInASiblingBlockDoesNotCount() {
        val found = unchecked(
            """
            f: (m: Maybe<Int32>, flag: Bool) Int32 {
                if flag {
                    if m.isNull() {
                        trace(0)
                    }
                }
                m.value
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
        assertTrue(found[0].message.contains("'m'"), found[0].message)
    }

    @Test
    fun aCheckAfterTheReadDoesNotCount() {
        val found = unchecked(
            """
            f: (m: Maybe<Int32>) Int32 {
                x: Int32 = m.value
                if m.isNull() {
                    trace(0)
                }
                x
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
    }

    @Test
    fun aReadAfterACheckInTheSameLoopBodyIsNotWarned() {
        assertEquals(
            emptyList(),
            unchecked(
                """
                f: (m: Maybe<Int32>, flag: Bool) Void {
                    while flag {
                        if m.isNull() {
                            trace(0)
                        }
                        trace(m.value)
                    }
                }
                """
            ),
        )
    }

    @Test
    fun aReadBeforeACheckInTheLoopBodyIsWarned() {
        val found = unchecked(
            """
            f: (m: Maybe<Int32>, flag: Bool) Void {
                while flag {
                    trace(m.value)
                    if m.isNull() {
                        trace(0)
                    }
                }
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
    }

    @Test
    fun aFieldPathIsMatchedByItsText() {
        val holder = """
            Holder: Any {
                pub kid: Maybe<Int32> = null
            }

        """
        val found = unchecked(
            holder + """
            f: (h: Holder) Int32 {
                trace(h.kid.value)
                0
            }

            g: (h: Holder) Int32 {
                if h.kid.isNull() {
                    trace(0)
                }
                trace(h.kid.value)
                0
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
        assertTrue(found[0].message.contains("'h.kid'"), found[0].message)
    }

    @Test
    fun aCheckInAnotherFunctionDoesNotCount() {
        val found = unchecked(
            """
            g: (m: Maybe<Int32>) Void {
                if m.isNull() {
                    trace(0)
                }
            }

            f: (m: Maybe<Int32>) Int32 {
                m.value
            }
            """
        )
        assertEquals(1, found.size, found.map { it.message }.toString())
    }
}
