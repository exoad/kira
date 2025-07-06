package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.BinaryOp

class BinaryExpr(
    val leftExpr: Expr,
    val rightExpr: Expr,
    val operator: BinaryOp,
) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBinaryExpr(this)
    }

    override fun toString(): String
    {
        return "BinaryExpr{ $leftExpr ${operator.symbol} $rightExpr }"
    }
}


