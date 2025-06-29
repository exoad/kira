package net.exoad.kira.compiler.frontend.statements

import net.exoad.kira.compiler.frontend.ASTNode
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

open class StatementNode(open val expression: ExpressionNode) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitStatement(this)
    }

    override fun toString(): String
    {
        return "Statement{ $expression }"
    }
}