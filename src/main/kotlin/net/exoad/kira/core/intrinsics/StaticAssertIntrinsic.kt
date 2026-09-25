package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * `@_static_assert(condition, "message")` (design D7): a module-level
 * callable whose condition must fold at compile time. It takes the
 * condition and an optional message; that the condition folds and holds
 * is the typer's check (wave 2), only the arity is checked here.
 */
object StaticAssertIntrinsic : CompilerIntrinsic("_static_assert", emptySet()) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        val n = invocation.parameters?.size ?: 0
        if (invocation.parameters == null || n !in 1..2) {
            throw KiraRuntimeException("@_static_assert takes a condition and an optional message: @_static_assert(A == B, \"why\")")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode = NoExpr
}
