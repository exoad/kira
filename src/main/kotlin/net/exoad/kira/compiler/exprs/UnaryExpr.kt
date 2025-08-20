package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.UnaryOp

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

