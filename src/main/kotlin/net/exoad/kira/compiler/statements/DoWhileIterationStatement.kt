package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

class DoWhileIterationStatement(val condition: Expr, val statements: List<Statement>) :
    Statement(expr = condition)
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