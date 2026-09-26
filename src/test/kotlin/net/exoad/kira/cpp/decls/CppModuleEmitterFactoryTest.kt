package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The factory: STRICT typing first, and no file when the typer or a part says no. */
class CppModuleEmitterFactoryTest {
    @Test
    fun aTyperErrorIsTheRunsDiagnosticAndNoModuleIsEmitted() {
        val emitted = DeclTestSupport.emit(DeclTestSupport.module("test:main", "pub X: Int32 = \"not a number\"\npub Y: Nope = 1"))
        val errors = emitted.runDiagnostics.filter { it.isError }
        assertTrue(errors.any { it.code.startsWith("types.") }, emitted.render(emitted.runDiagnostics))
        val m = emitted.module("test:main")
        assertEquals("", m.header)
        assertEquals(null, m.source)
        assertTrue(m.diagnostics.isEmpty(), "the failed emitter reports nothing per module; the run carries the errors")
    }

    @Test
    fun aTyperWarningPassesThroughAsAWarning() {
        val emitted = DeclTestSupport.emit(DeclTestSupport.module("test:main", "use \"test:main\"\npub X: Int32 = 1"))
        val warning = emitted.runDiagnostics.firstOrNull { it.code == "types.use.self" } ?: kotlin.test.fail(emitted.render(emitted.runDiagnostics))
        assertTrue(!warning.isError)
        assertTrue(emitted.header("test:main").contains("inline constexpr std::int32_t X = 1;"))
    }

    @Test
    fun theTyperReadsTheManifestGlobs() {
        val emitted = DeclTestSupport.emit(
            DeclTestSupport.module("pico:hall", "pub X: Int32 = 1"),
            options = CppOptions(lineDirectives = false, headerOnly = listOf("pico:**"), freestanding = listOf("pico:hall")),
        )
        val h = emitted.header("pico:hall")
        assertTrue(h.contains("#include \"kira/core.hxx\""), h)
        assertEquals(null, emitted.module("pico:hall").source)
    }

    @Test
    fun anUnsupportedConstructThrownByAPartBecomesADiagnostic() {
        val throwing = object : CppStmtPart {
            override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) = throw UnsupportedConstruct(s, "a strange statement")
            override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) =
                throw UnsupportedConstruct(fn!!.decl!!, "the body of ${fn.name}")
        }
        val emitted = DeclTestSupport.emit(
            DeclTestSupport.module("test:main", "pub fx f: () Void { }"),
            parts = CppEmitParts(stmts = throwing),
        )
        val m = emitted.module("test:main")
        assertEquals("", m.header)
        assertEquals(listOf(CppModuleEmitterFactory.UNSUPPORTED_CODE), m.diagnostics.map { it.code })
        assertTrue(m.diagnostics.single().message.contains("the body of f is not lowered yet"), m.diagnostics.single().render())
        assertTrue(m.diagnostics.single().position != null, "the diagnostic names the node's line")
    }
}
