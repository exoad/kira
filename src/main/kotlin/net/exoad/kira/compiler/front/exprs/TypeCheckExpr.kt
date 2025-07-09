package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.TypeSpecifier

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