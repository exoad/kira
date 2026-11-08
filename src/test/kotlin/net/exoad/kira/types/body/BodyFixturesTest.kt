package net.exoad.kira.types.body

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixture checker itself: a clause is spent by one diagnostic, so the count per line is
 * checked, not only the set of codes.
 */
class BodyFixturesTest {
    @TempDir
    lateinit var dir: File

    // Three comparisons of a Str with an Int32 on one line report types.op.mismatch three times.
    private val body = "pub f: () Void {\n    n: Str { \"a\" }\n    trace(n < 1 && n > 2 && n < 3) // @error types.op.mismatch ; %s\n}\n"

    private fun failuresWith(clauses: String): List<String> {
        val file = File(dir, "count.kira").apply { writeText(body.format(clauses)) }
        val program = BodyFixtures.type(file)
        val parsed = mapOf(file to BodyFixtures.parse(file.readText()))
        return BodyFixtures.checkDiagnostics(program, listOf(file), parsed)
    }

    @Test
    fun theExactCountPerLinePasses() {
        assertEquals(emptyList(), failuresWith("@error types.op.mismatch ; @error types.op.mismatch"))
    }

    @Test
    fun oneClauseShortIsAnUnexpectedDiagnostic() {
        val failures = failuresWith("@error types.op.mismatch")
        assertEquals(1, failures.size, failures.joinToString("\n"))
        assertTrue(failures.single().startsWith("unexpected: "), failures.single())
    }

    @Test
    fun oneClauseOverIsAMissingDiagnostic() {
        val failures = failuresWith("@error types.op.mismatch ; @error types.op.mismatch ; @error types.op.mismatch")
        assertEquals(1, failures.size, failures.joinToString("\n"))
        assertTrue(failures.single().endsWith("expected error types.op.mismatch, which was not reported"), failures.single())
    }
}
