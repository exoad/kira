package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * Marks a function stub as a **C extern**.
 * Optional string parameter: explicit C symbol (default = Kira function name).
 * Bodies are ignored; C-as-IR emits a prototype only. Calls are unmangled.
 */
object ExternIntrinsic : CompilerIntrinsic(
    "_extern",
    setOf(FunctionDecl::class, Identifier::class)
) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        val n = invocation.parameters?.size ?: 0
        if (n > 1) {
            throw KiraRuntimeException("@_extern accepts at most one string parameter (C symbol)")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        val kiraName = when (target) {
            is FunctionDecl -> (target.name as? Identifier)?.value
            is Identifier -> target.value
            else -> null
        } ?: return NoExpr

        val cName = when (val p = invocation.parameters?.firstOrNull()) {
            is StringLiteral -> p.value
            else -> kiraName
        }
        compilationUnit.registerExternFunction(kiraName, cName)
        return NoExpr
    }
}
