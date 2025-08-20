package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.Intrinsic

open class IntrinsicCallExpr(val name: Intrinsic, val parameters: List<Expr>) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIntrinsicCallExpr(this)
    }

    override fun toString(): String
    {
        return "IntrinsicCallExpr[[ $name ]]{ $parameters }"
    }
}