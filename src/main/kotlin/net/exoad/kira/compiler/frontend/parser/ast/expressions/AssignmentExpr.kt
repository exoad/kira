package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.core.CompilerIntrinsic

open class AssignmentExpr(
    val target: Identifier,
    val value: Expr,
    override val attachedIntrinsics: List<CompilerIntrinsic> = emptyList()
) : Expr(attachedIntrinsics) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitAssignmentExpr(this)
    }

    override fun toString(): String {
        return "Assignment(target=${target.value}, value=$value)"
    }
}