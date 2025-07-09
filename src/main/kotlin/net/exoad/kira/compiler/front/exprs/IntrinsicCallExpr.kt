package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.Intrinsic
import net.exoad.kira.compiler.front.ASTVisitor

open class IntrinsicCallExpr(val name: Intrinsic, val parameters: List<Expr>) :
    Expr()
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