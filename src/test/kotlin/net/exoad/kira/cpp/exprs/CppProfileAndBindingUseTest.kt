package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.backend.codegen.cpp.CppBinding
import net.exoad.kira.compiler.backend.codegen.cpp.CppBindingTable
import net.exoad.kira.compiler.backend.codegen.cpp.CppBindingsPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * The freestanding profile's statement rows (D41: `throw` is `kira::panic`, `try` refused;
 * design 10: no output from `trace`), where a binding's include goes (the header for
 * header-placed code, else the source), and R5's `kira::Str(...)` around a Str constant as
 * the receiver of a member-style binding.
 */
class CppProfileAndBindingUseTest {
    private val pico = Module(
        "pico:guard",
        """
        pub fx checked: (v: Int32) Int32 {
            if v < 0 {
                throw "negative"
            }
            return v
        }

        pub fx reply: (servo: Int32, mut out: StrBuf<16>) Void {
            out.set("servo=${'$'}{servo}")
        }
        """,
    )

    private val freestanding = CppOptions(lineDirectives = false, freestanding = listOf("pico:**"))

    @Test
    fun throwIsKiraPanicFreestanding() {
        val tree = CppExprTestSupport.emit("freestanding", listOf(pico), freestanding)
        val text = tree.text(pico)
        assertTrue(text.contains("#include \"kira/core.hxx\""), text)
        assertTrue(text.contains("          kira::panic(\"negative\");"), text)
        assertTrue(!text.contains("throw"), text)
    }

    @TestFactory
    fun theFreestandingModuleBuildsForThePico(): List<DynamicNode> = listOf(CppToolchain.ARM, CppToolchain.GCC).map { tc ->
        DynamicTest.dynamicTest("freestanding [${tc.id}]") {
            val tree = CppExprTestSupport.emit("freestanding", listOf(pico), freestanding)
            // A freestanding driver defines the board's kira::panic; hosted, core.hxx does.
            val driver = """
                #include "src/pico/guard.kira.hxx"

                #if !KIRA_PROFILE_HOSTED
                [[noreturn]] void kira::panic(const char*) noexcept
                {
                    for(;;)
                    {
                    }
                }
                #endif

                int main()
                {
                    kira::StrBuf<16> buf;
                    guard::reply(1500, buf);
                    return guard::checked(3) == 3 && buf.size() == 10u ? 0 : 1;
                }
            """.trimIndent() + "\n"
            val out = CppExprTestSupport.compileAndRun(tree, driver, tc)
            assertTrue(out == null || out.isEmpty(), "${tc.id}: $out")
        }
    }

    @Test
    fun tryAndTraceAreRefusedFreestanding() {
        val emitted = DeclTestSupport.emit(
            DeclTestSupport.module(
                "pico:bad",
                """
                pub fx f: () Void {
                    try {
                        trace(1)
                    } on e: Str {
                    }
                }
                """,
            ),
            options = freestanding,
        )
        // The emitter refuses it (cpp.unsupported); once W2.5's ProfilePass runs, the typer
        // refuses it first (rules.profile.try) and no module is emitted at all. Either is a refusal.
        val module = runCatching { emitted.diagnostics("pico:bad") }.getOrDefault(emptyList())
        val errors = (emitted.runDiagnostics + module).filter { it.isError }
        assertTrue(
            errors.any { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE && it.message.startsWith("try in a freestanding module") } ||
                errors.any { it.code == "rules.profile.try" },
            errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun anIncludeGoesWhereTheCodeUsingItIs() {
        val lib = Module("inc:lib", "pub fx root: (x: Float64) Float64 {\n    return sqrt(x)\n}")
        val app = Module("inc:app", "pub fx root2: (x: Float64) Float64 {\n    return sqrt(x)\n}")
        val tree = CppExprTestSupport.emit("includes", listOf(lib, app), CppOptions(lineDirectives = false, headerOnly = listOf("inc:lib")))
        assertTrue(tree.header(lib).contains("#include \"cmath\"\n"), "header-placed code includes in the header:\n${tree.header(lib)}")
        assertTrue(!tree.header(app).contains("cmath"), "source-placed code does not:\n${tree.header(app)}")
        assertTrue(tree.source(app).contains("#include \"cmath\"\n"), "it includes in the source:\n${tree.source(app)}")
    }

    @Test
    fun aStrConstantReceiverOfAMemberStyleBindingIsAKiraStr() {
        val real = CppBindingTable().also { it.loadDir(Path.of("kira")) }
        val memberStyle = object : CppBindingsPart {
            override fun lookup(key: String): CppBinding? =
                if (key == "Str.length") CppBinding("{self}.size()", pure = true) else real.lookup(key)
        }
        val emitted = DeclTestSupport.emit(
            DeclTestSupport.module(
                "r5:wrap",
                """
                pub IP: Str = "192.168.1.62"

                pub fx n: (s: Str) Size {
                    return IP.length() + s.length()
                }
                """,
            ),
            parts = CppEmitParts.standard().copy(bindings = memberStyle),
        )
        val s = emitted.source("r5:wrap") ?: ""
        assertTrue(s.contains("return kira::Str(IP).size() + s.size();"), s)
    }
}
