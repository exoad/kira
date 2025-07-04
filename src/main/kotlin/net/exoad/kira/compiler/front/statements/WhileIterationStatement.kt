package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

class WhileIterationStatement(val condition: Expr, val statements: List<Statement>) :
    Statement(expr = condition)
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