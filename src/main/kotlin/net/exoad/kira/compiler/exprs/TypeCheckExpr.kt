package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.TypeSpecifier

class TypeCheckExpr(val value: Expr, val type: TypeSpecifier) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitTypeCheckExpr(this)
    }

    override fun toString(): String
    {
        return "TypeCheckExpr{ $value -> $type }"
    }
}