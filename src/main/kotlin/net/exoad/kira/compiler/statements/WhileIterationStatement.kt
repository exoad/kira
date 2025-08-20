package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

class WhileIterationStatement(val condition: Expr, val statements: List<Statement>) :
    Statement(condition)
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