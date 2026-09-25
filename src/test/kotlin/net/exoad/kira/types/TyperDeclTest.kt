package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden dumps of phases A and B: one line per symbol (kind, type, flags) plus the
 * diagnostics, for examples/01..09 and for every fixture in src/test/resources/typer/decls.
 * The stdlib is typed too but left out of the dumps, so a stdlib change does not move them.
 *
 * To regenerate after an intended change: `KIRA_UPDATE_GOLDENS=1 ./gradlew test --tests
 * 'net.exoad.kira.types.TyperDeclTest'`, then review the diff.
 */
class TyperDeclTest {
    private val update = System.getenv("KIRA_UPDATE_GOLDENS") == "1"
    private val fixtures = File("src/test/resources/typer/decls")

    @TestFactory
    fun examples(): List<DynamicTest> {
        val dirs = File("examples").listFiles { f -> f.isDirectory && f.name.matches(Regex("0[1-9]-.*")) }!!
            .sortedBy { it.name }
        assertEquals(9, dirs.size, "examples/01..09: ${dirs.map { it.name }}")
        return dirs.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val program = TyperTestSupport.project(dir, TyperMode.STRICT)
                assertEveryTypeResolved(program)
                check(File(fixtures, "examples/${dir.name}.txt"), TypedModelDumper.dumpSymbols(program))
            }
        }
    }

    @TestFactory
    fun fixtures(): List<DynamicTest> {
        val files = fixtures.listFiles { f -> f.isFile && f.extension == "kira" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no fixtures in $fixtures")
        return files.map { file ->
            DynamicTest.dynamicTest(file.name) {
                val program = TyperTestSupport.type(TyperTestSupport.Src(file.canonicalPath, file.readText()))
                assertEveryTypeResolved(program)
                check(File(fixtures, file.nameWithoutExtension + ".txt"), TypedModelDumper.dumpSymbols(program))
            }
        }
    }

    /**
     * Fixtures in the design's dialect (typer/decls/dialect). They need the frontend package's
     * parser; in a branch without it a fixture that does not parse is skipped, with the reason.
     */
    @TestFactory
    fun dialectFixtures(): List<DynamicTest> {
        val dir = File(fixtures, "dialect")
        val files = dir.listFiles { f -> f.isFile && f.extension == "kira" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no fixtures in $dir")
        return files.map { file ->
            DynamicTest.dynamicTest("dialect/${file.name}") {
                val program = try {
                    TyperTestSupport.type(TyperTestSupport.Src(file.canonicalPath, file.readText()))
                } catch (e: net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "${file.name} does not parse in this branch yet: ${e.message?.lineSequence()?.firstOrNull()}",
                    )
                    return@dynamicTest
                }
                assertEveryTypeResolved(program)
                check(File(dir, file.nameWithoutExtension + ".txt"), TypedModelDumper.dumpSymbols(program))
            }
        }
    }

    /** Phase B resolves every Type node of the user's modules (a constant argument is not a type). */
    private fun assertEveryTypeResolved(program: TypedProgram) {
        val missing = mutableListOf<String>()
        for (m in program.workspaceModules) {
            AstTree.walk(m.source.ast) { node ->
                if (node is Type && node !is ConstTypeArg && program.model.typeRefs[node] == null && program.model.consts[node] == null) {
                    val where = program.locate(node)?.second
                    missing.add("${m.uri} ${where ?: "?"}: $node")
                }
            }
        }
        assertTrue(missing.isEmpty(), "Type nodes without a typeRefs entry:\n${missing.joinToString("\n")}")
    }

    private fun check(golden: File, actual: String) {
        if (update || !golden.exists()) {
            if (!update) {
                throw AssertionError("missing golden ${golden.path}; run with KIRA_UPDATE_GOLDENS=1 to create it:\n$actual")
            }
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            return
        }
        assertEquals(golden.readText().replace("\r\n", "\n"), actual, "golden ${golden.path} differs")
    }
}
