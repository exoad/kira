package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.compiler.Intrinsic
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

open class IntrinsicCallExpression(val name: Intrinsic, val parameters: List<ExpressionNode>) :
    ExpressionNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIntrinsicCallExpression(this)
    }

    override fun toString(): String
    {
        return "IntrinsicCallExpression[[ $name ]]{ $parameters }"
    }
}