package net.exoad.kira.core

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.source.SourceContext
import kotlin.reflect.KClass

abstract class CompilerIntrinsic(val name: String, val validTargets: Set<KClass<out ASTNode>>) {
    abstract fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext)

    abstract fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode
}