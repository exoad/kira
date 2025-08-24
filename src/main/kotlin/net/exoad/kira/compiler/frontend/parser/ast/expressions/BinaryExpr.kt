package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp

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


