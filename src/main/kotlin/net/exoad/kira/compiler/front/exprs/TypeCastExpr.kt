package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.TypeSpecifier

class TypeCastExpr(val value: Expr, val type: TypeSpecifier) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitTypeCastExpr(this)
    }

    override fun toString(): String
    {
        return "TypeCastExpr{ $value -> $type }"
    }
}