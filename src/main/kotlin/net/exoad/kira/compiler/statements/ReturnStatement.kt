package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

open class ReturnStatement(override val expr: Expr) : Statement(expr)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitReturnStatement(this)
    }

    override fun toString(): String
    {
        return "ReturnStatement{ $expr }"
    }
}