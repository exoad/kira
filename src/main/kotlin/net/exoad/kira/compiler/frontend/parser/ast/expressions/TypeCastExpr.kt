package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier

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