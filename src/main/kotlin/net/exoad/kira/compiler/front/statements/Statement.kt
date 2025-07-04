package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

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