package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TypedModelDumper goldens of whole golden-corpus modules after phase C: one line per typed
 * expression with its type, call, member, coercion, conversion, constant, place and symbol.
 * The proto case is the design's worked example (5.9); a change to any fact it records shows
 * up here line by line.
 *
 * To regenerate after an intended change: `KIRA_UPDATE_GOLDENS=1 ./gradlew test --tests
 * 'net.exoad.kira.types.body.TyperBodyGoldenTest'`, then review the diff.
 */
class TyperBodyGoldenTest {
    private val update = System.getenv("KIRA_UPDATE_GOLDENS") == "1"
    private val goldens = File("src/test/resources/typer/body/goldens")

    /** Case directory under cpp-golden, and the module's source path under its src/. */
    private val cases = listOf(
        "proto" to "pilot/proto.kira",
        "hall" to "pico/hall.kira",
        "closures" to "lang/closures.kira",
    )

    @TestFactory
    fun dumps(): List<DynamicTest> = cases.map { (case, path) ->
        DynamicTest.dynamicTest(case) {
            val dir = File("src/test/resources/cpp-golden/$case")
            val program = TyperTestSupport.project(dir, TyperMode.STRICT)
            assertTrue(program.diagnostics.isEmpty(), TyperTestSupport.render(program))
            val source = program.workspaceModules.single { it.source.file.replace('\\', '/').endsWith("src/$path") }.source
            val dump = TypedModelDumper.dump(program, source) + "\n"
            val golden = File(goldens, "$case.txt")
            if (update || !golden.exists()) {
                if (!update) {
                    throw AssertionError("missing golden ${golden.path}; run with KIRA_UPDATE_GOLDENS=1 to create it:\n$dump")
                }
                golden.parentFile.mkdirs()
                golden.writeText(dump)
                return@dynamicTest
            }
            assertEquals(golden.readText().replace("\r\n", "\n"), dump, "golden ${golden.path} differs")
        }
    }
}
