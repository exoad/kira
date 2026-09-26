package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue

/**
 * Design 4.5: with `lineDirectives` on (the default; the goldens turn it off), each statement
 * is preceded by `#line N "path"`, a lambda's statements too, so g++ and MSVC point an error at
 * the `.kira` line. The emitted tree must still compile under the warning contract.
 */
class CppLineDirectivesTest {
    private val module = Module(
        "lines:app",
        """
        pub fx scale: (k: Int32) Fx<Tuple1<Int32>, Int32> {
            base: Int32 = k * 2
            return fx(x: Int32) Int32 {
                y: Int32 = x + base
                return y
            }
        }

        pub fx count: (n: Int32) Int32 {
            mut t: Int32 = 0
            for i: Int32 in 0..n {
                t += i
            }
            return t
        }
        """,
    )

    private val tree by lazy { CppExprTestSupport.emit("lines", listOf(module), CppOptions(lineDirectives = true)) }

    @Test
    fun everyStatementPointsAtItsKiraLine() {
        val s = tree.source(module)
        val file = "src/lines/app.kira"
        listOf(
            "      #line 4 \"$file\"\n      const std::int32_t base{k * 2};",
            "      #line 5 \"$file\"\n      return [base](std::int32_t x) -> std::int32_t",
            "          #line 6 \"$file\"\n          const std::int32_t y{x + base};",
            "          #line 7 \"$file\"\n          return y;",
            "      #line 13 \"$file\"\n      for(std::int32_t i = 0, i_end = n; i < i_end; ++i)",
            "          #line 14 \"$file\"\n          t += i;",
        ).forEach { assertTrue(s.contains(it), "missing:\n$it\n--- emitted ---\n$s") }
    }

    @TestFactory
    fun theDirectivesCompile(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("#line [${tc.id}]") {
            val driver = "#include \"src/lines/app.kira.hxx\"\n#include <cstdio>\n\nint main()\n{\n    std::printf(\"%d %d\\n\", app::scale(3)(1), app::count(4));\n    return 0;\n}\n"
            val out = CppExprTestSupport.compileAndRun(tree, driver, tc)
            assertTrue(out == "7 6\n", "${tc.id}: $out")
        }
    }
}
