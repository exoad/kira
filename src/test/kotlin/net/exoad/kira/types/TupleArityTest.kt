package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tuples are T0 to T10: each is a stdlib type, an alias may name any of them, and T11 and up are refused. */
class TupleArityTest {
    @Test
    fun anAliasOfASevenTupleTypesAndEmits() {
        // bibo's detection_filter.kira:20 shape.
        val text = """
            pub T7<Bool, Str, Float64, Float64, Float64, Float64, Float64> as Raw

            pub score: (r: Raw) Float64 {
                r.first ? r.third + r.seventh : 0.0
            }
        """.trimIndent() + "\n"
        val program = TyperTestSupport.snippet(text)
        assertTrue(program.diagnostics.none { it.isError }, program.diagnostics.joinToString(" | ") { it.message })
        assertTrue(PyTestSupport.emit(text).python().contains("def score("))
    }

    @Test
    fun aTenTupleIsTheLargest() {
        val ten = TyperTestSupport.snippet(
            "pub last: (t: T10<Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Str>) Str {\n    t.tenth\n}\n"
        )
        assertTrue(ten.diagnostics.none { it.isError }, ten.diagnostics.joinToString(" | ") { it.message })
        val e = assertThrows<DiagnosticsException> {
            TyperTestSupport.snippet("pub last: (t: T11<Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Str>) Int32 {\n    0\n}\n")
        }
        assertEquals("parse.tuple.arity", e.tag, e.message)
        assertTrue(e.message.contains("T0 to T10"), e.message)
        // A typo's arity is refused the same way, never generated or looked up.
        assertEquals("parse.tuple.arity", assertThrows<DiagnosticsException> { TyperTestSupport.snippet("pub f: (t: T100000<Int32>) Int32 {\n    0\n}\n") }.tag)
    }
}
