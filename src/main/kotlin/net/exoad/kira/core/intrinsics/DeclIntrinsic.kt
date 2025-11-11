package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext
import kotlin.reflect.KClass

object DeclIntrinsic : CompilerIntrinsic {
    override val name: String = "__decl__"

    override val validTargets: Set<KClass<out ASTNode>> = emptySet()

    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        if (invocation.parameters != null && invocation.parameters.size > 1) {
            throw KiraRuntimeException("Decl intrinsic can only have one parameter")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        if (invocation.parameters == null || invocation.parameters.isEmpty()) {
            return NoExpr
        }
        return Identifier(invocation.parameters!![0].toString())
    }
}