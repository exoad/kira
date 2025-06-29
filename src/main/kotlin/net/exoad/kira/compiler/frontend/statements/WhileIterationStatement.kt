package net.exoad.kira.compiler.frontend.statements

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

class WhileIterationStatement(val condition: ExpressionNode, val statements: List<StatementNode>) :
    StatementNode(expression = condition)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitWhileIterationStatement(this)
    }

    override fun toString(): String
    {
        return "WhileIterationStatement{ $condition -> $statements }"
    }
}