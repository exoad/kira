package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.BinaryOp

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


