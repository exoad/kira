package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.TypeSpecifier

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