package net.exoad.kira.compiler.frontend.statements

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

class DoWhileIterationStatement(val condition: ExpressionNode, val statements: List<StatementNode>) :
    StatementNode(expression = condition)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitDoWhileIterationStatement(this)
    }

    override fun toString(): String
    {
        return "DoWhileIterationStatement{ $condition -> $statements }"
    }
}