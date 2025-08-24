package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor

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