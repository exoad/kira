package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import kotlin.test.assertTrue
import kotlin.test.fail

/** Emits a module's declarations through fake statement and expression parts, and reads the header back. */
object DeclEmitRun {
    /** A statement part whose every body returns the module's declaration [name], spelled through `ctx.qualified`. */
    class ReturnsNamed(val name: String) : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            w.line("/* stmt */")
        }

        override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
            w.line("return ${ctx.qualified(ctx.symbol.members[name] ?: fail("no declaration '$name'"))};")
        }
    }

    object FakeExprs : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = "<${e.javaClass.simpleName}>"
    }

    fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    fun render(diagnostics: List<CppDiagnostic>): String = diagnostics.joinToString("\n") { it.render() }.ifEmpty { "(none)" }

    fun emit(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), parts: CppEmitParts = CppEmitParts(stmts = ReturnsNamed("LIMIT"), exprs = FakeExprs), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null) =
        DeclTestSupport.emitWith(DeclTestSupport.module(uri, body), uri = uri, parts = parts, options = options, usageOf = usageOf)

    fun header(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null): String {
        val (emitted, _) = emit(body, uri, options, usageOf = usageOf)
        val errors = emitted.diagnostics.filter { it.isError }
        if (errors.isNotEmpty()) {
            fail("errors:\n" + render(errors) + "\n--- header ---\n" + emitted.header)
        }
        return CppWriter.normalize(emitted.header)
    }
}
