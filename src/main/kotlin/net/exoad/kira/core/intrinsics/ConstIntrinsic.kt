package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * `@_const fx f: (...) R { }` (design D7): a declaration marker asking for
 * the function to be evaluable at compile time (`constexpr` in the C++
 * backend). It takes no arguments. Whether the body qualifies is a typer
 * check (wave 2); only the arity is checked here.
 */
object ConstIntrinsic : CompilerIntrinsic("_const", setOf(FunctionDecl::class, VariableDecl::class)) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
            throw KiraRuntimeException("@_const takes no arguments (modifier like)")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode = NoExpr
}
