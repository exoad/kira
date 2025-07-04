package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor

open class MemberAccessExpr(val origin: Expr, val member: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitMemberAccessExpr(this)
    }

    override fun toString(): String
    {
        return "MemberAccessExpr{ $origin -> $member }"
    }
}