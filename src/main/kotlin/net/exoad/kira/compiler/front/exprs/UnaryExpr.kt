package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.UnaryOp

class UnaryExpr(val operator: UnaryOp, val operand: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitUnaryExpr(this)
    }

    override fun toString(): String
    {
        return "UnaryExpr{ $operator -> $operand }"
    }
}

