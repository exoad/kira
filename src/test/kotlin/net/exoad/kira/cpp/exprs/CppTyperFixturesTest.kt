package net.exoad.kira.cpp.exprs

import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue

/**
 * W2.1's positive typer fixtures (`src/test/resources/typer/body/positive`) hold one of
 * nearly every body shape the typer accepts: calls with named arguments, defaults and `mut`,
 * the `@_infer` math family, conversions, `bitCast` and `enumOf`, lambdas of every capture
 * kind but a class's, literals of every width, operators, statements, views and StrBuf.
 * Emitted together, every one must compile under the warning contract on gcc, clang, msvc and
 * zig-aarch64 (design 8.2), which proves the lowering of shapes no row of [CppExprRowsTest]
 * spells out. The fixtures that hold a class, a trait or a generic wait for W2.4's class
 * part: `classes.kira`, `generics.kira`, `maybe.kira` and `crossmodule/`.
 */
class CppTyperFixturesTest {
    private val dir = File("src/test/resources/typer/body/positive")
    private val names = listOf("calls", "conversions", "lambdas", "literals", "operators", "statements", "views")

    private val modules: List<Module> by lazy {
        names.map { n ->
            val text = File(dir, "$n.kira").readText()
            val uri = Regex("""^module "([^"]+)"""", RegexOption.MULTILINE).find(text)!!.groupValues[1]
            Module(uri, text.substringAfter("module \"$uri\"").trimStart('\n', '\r'))
        }
    }

    private val tree by lazy { CppExprTestSupport.emit("typer-fixtures", modules) }

    @TestFactory
    fun everyFixtureCompilesUnderTheWarningContract(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC, CppToolchain.ZIG_AARCH64).map { tc ->
            DynamicTest.dynamicTest("typer fixtures [${tc.id}]") {
                val driver = buildString {
                    modules.forEach { m -> append("#include \"").append(m.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n") }
                    append("\nint main()\n{\n    return 0;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc)
                assertTrue(stdout == null || stdout.isEmpty(), "${tc.id}: the fixtures print nothing without a driver: $stdout")
            }
        }
}
