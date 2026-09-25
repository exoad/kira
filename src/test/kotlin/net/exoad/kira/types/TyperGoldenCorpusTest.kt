package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.BodyTyper
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The W1 exit gate's typer half: every case of the C++ golden corpus
 * (src/test/resources/cpp-golden/<case>/src) parses and gives no error in phases A and B,
 * under STRICT. Phase C and the rule passes are switched off here; their own packages check
 * them.
 *
 * The corpus arrives with the runtime package (W1.3) and uses the design's dialect, which the
 * parser package (W1.1) grows. Until the corpus directory exists the test is skipped, and
 * says so. A case that does not parse is skipped only while the dialect parser is absent
 * ([TyperTestSupport.parsingDialect]); with the parser present it fails, and a typer error
 * always fails.
 */
class TyperGoldenCorpusTest {
    private val corpus = File("src/test/resources/cpp-golden")

    @TestFactory
    fun everyGoldenCaseTypesWithoutErrorsInPhasesAAndB(): List<DynamicTest> {
        if (!corpus.isDirectory) {
            return listOf(DynamicTest.dynamicTest("no golden corpus in this branch") {
                assumeTrue(false, "src/test/resources/cpp-golden does not exist here yet (W1.3)")
            })
        }
        val cases = corpus.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }?.sortedBy { it.name }.orEmpty()
        if (cases.isEmpty()) {
            return listOf(DynamicTest.dynamicTest("empty golden corpus") {
                fail("$corpus exists but holds no <case>/src directory")
            })
        }
        return cases.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val program = TyperTestSupport.parsingDialect("golden case ${dir.name}") {
                    phasesAAndB { TyperTestSupport.project(dir, TyperMode.STRICT) }
                }
                assertTrue(!program.hasErrors, "${dir.name}:\n${TyperTestSupport.render(program)}")
            }
        }
    }

    private fun phasesAAndB(block: () -> TypedProgram): TypedProgram {
        val body = KiraTyper.bodyTyper
        val rules = KiraTyper.rulePasses.toList()
        KiraTyper.bodyTyper = BodyTyper.NONE
        KiraTyper.rulePasses.clear()
        try {
            return block()
        } finally {
            KiraTyper.bodyTyper = body
            KiraTyper.rulePasses.addAll(rules)
        }
    }
}
