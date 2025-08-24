package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier

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