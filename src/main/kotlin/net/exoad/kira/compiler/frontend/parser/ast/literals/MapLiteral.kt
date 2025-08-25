package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class MapLiteral(override val value: Map<Expr, Expr>, val mutable: Boolean) : DataLiteral<Map<Expr, Expr>>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitMapLiteral(this)
    }

    override fun toString(): String
    {
        return "LMap${if(mutable) "[[ MUTABLE ]]" else ""}{ $value }"
    }
}