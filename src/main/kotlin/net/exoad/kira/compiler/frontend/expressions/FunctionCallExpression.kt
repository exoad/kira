package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.compiler.frontend.elements.IdentifierNode

open class FunctionCallExpressionNode(val name: IdentifierNode, val parameters: List<ExpressionNode>) : ExpressionNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionCallExpression(this)
    }

    override fun toString(): String
    {
        return "FunctionCallExpression{ $name -> $parameters }"
    }
}