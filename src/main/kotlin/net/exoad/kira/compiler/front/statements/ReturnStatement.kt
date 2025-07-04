package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

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