package net.exoad.kira.core

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.source.SourceContext
import kotlin.reflect.KClass

interface CompilerIntrinsic {
    val name: String

    val validTargets: Set<KClass<out ASTNode>>

    fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext)

    fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode
}