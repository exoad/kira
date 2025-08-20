package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTNode
import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

open class Statement(open val expr: Expr) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitStatement(this)
    }

    override fun toString(): String
    {
        return "Statement{ $expr }"
    }
}