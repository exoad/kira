package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp

class UnaryExpr(val operator: UnaryOp, val operand: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitUnaryExpr(this)
    }

    override fun toString(): String
    {
        return "Unary{ $operator -> $operand }"
    }
}

