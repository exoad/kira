package net.exoad.kira.types.body

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The annotated fixtures of src/test/resources/typer/body (format: [BodyFixtures]).
 *
 * - `positive/`: every `@type` clause holds, and the only diagnostics are the annotated ones
 *   (the legacy loop's deprecation warning, say). Together they hold at least 150 cases.
 * - `negative/`: every annotated error is reported on its line with its exact code, and
 *   nothing else is.
 */
class TyperBodyFixtureTest {
    private val root = File("src/test/resources/typer/body")

    private fun fixtures(dir: String): List<File> =
        File(root, dir).listFiles { f -> f.isDirectory || f.extension == "kira" }!!.sortedBy { it.name }

    @TestFactory
    fun positive(): List<DynamicTest> = fixtures("positive").map { fixture ->
        DynamicTest.dynamicTest(fixture.name) {
            val program = BodyFixtures.type(fixture)
            val files = BodyFixtures.sources(fixture)
            val parsed = files.associateWith { BodyFixtures.parse(it.readText()) }
            val failures = BodyFixtures.checkDiagnostics(program, files, parsed).toMutableList()
            files.forEach { failures.addAll(BodyFixtures.checkTypes(program, it, parsed[it]!!).first) }
            if (failures.isNotEmpty()) {
                fail("${fixture.name}:\n${failures.joinToString("\n")}")
            }
        }
    }

    @TestFactory
    fun negative(): List<DynamicTest> = fixtures("negative").map { fixture ->
        DynamicTest.dynamicTest(fixture.name) {
            val program = BodyFixtures.type(fixture)
            val files = BodyFixtures.sources(fixture)
            val parsed = files.associateWith { BodyFixtures.parse(it.readText()) }
            assertTrue(parsed.values.any { p -> p.diags.isNotEmpty() }, "${fixture.name} annotates no diagnostic")
            val failures = BodyFixtures.checkDiagnostics(program, files, parsed).toMutableList()
            files.forEach { failures.addAll(BodyFixtures.checkTypes(program, it, parsed[it]!!).first) }
            if (failures.isNotEmpty()) {
                fail("${fixture.name}:\n${failures.joinToString("\n")}")
            }
        }
    }

    @Test
    fun thePositiveFixturesHoldAtLeast150Cases() {
        val count = fixtures("positive").flatMap { BodyFixtures.sources(it) }.sumOf { BodyFixtures.parse(it.readText()).types.size }
        assertTrue(count >= 150, "the positive fixtures hold $count @type cases; the brief asks for at least 150")
    }
}
