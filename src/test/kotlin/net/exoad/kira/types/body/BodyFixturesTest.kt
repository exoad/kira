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

    // `for n: Str in 0..3` reports types.for.range-type once and types.assign.mismatch twice (each bound).
    private val body = "module \"fixture:count\"\n\npub fx f: () Void {\n    for n: Str in 0..3 { // @error types.for.range-type ; %s\n        trace(n)\n    }\n}\n"

    private fun failuresWith(clauses: String): List<String> {
        val file = File(dir, "count.kira").apply { writeText(body.format(clauses)) }
        val program = BodyFixtures.type(file)
        val parsed = mapOf(file to BodyFixtures.parse(file.readText()))
        return BodyFixtures.checkDiagnostics(program, listOf(file), parsed)
    }

    @Test
    fun theExactCountPerLinePasses() {
        assertEquals(emptyList(), failuresWith("@error types.assign.mismatch ; @error types.assign.mismatch"))
    }

    @Test
    fun oneClauseShortIsAnUnexpectedDiagnostic() {
        val failures = failuresWith("@error types.assign.mismatch")
        assertEquals(1, failures.size, failures.joinToString("\n"))
        assertTrue(failures.single().startsWith("unexpected: "), failures.single())
    }

    @Test
    fun oneClauseOverIsAMissingDiagnostic() {
        val failures = failuresWith("@error types.assign.mismatch ; @error types.assign.mismatch ; @error types.assign.mismatch")
        assertEquals(1, failures.size, failures.joinToString("\n"))
        assertTrue(failures.single().endsWith("expected error types.assign.mismatch, which was not reported"), failures.single())
    }
}
