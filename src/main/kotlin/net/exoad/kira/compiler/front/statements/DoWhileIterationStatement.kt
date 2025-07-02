package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

class DoWhileIterationStatement(val condition: Expr, val statements: List<StatementNode>) :
    StatementNode(expr = condition)
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