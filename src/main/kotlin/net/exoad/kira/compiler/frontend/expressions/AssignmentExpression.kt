package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.compiler.frontend.elements.IdentifierNode

open class AssignmentExpressionNode(
    val target: IdentifierNode,
    val value: ExpressionNode
) : ExpressionNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitAssignmentExpression(this)
    }

    override fun toString(): String
    {
        return "AssignmentExpression{ ${target.name} -> $value }"
    }
}