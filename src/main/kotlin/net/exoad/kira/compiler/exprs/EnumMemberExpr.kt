package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.DataLiteral
import net.exoad.kira.compiler.elements.Identifier

/**
 * An enumerated constant within an enum.
 *
 * [value] although is of [DataLiteral], the parser makes sure that this also implements [net.exoad.kira.compiler.elements.SimpleLiteral]
 * due to the nature and grammar of the language. we do not allow complex literals like [net.exoad.kira.compiler.elements.MapLiteral], [net.exoad.kira.compiler.elements.ListLiteral]
 */
open class EnumMemberExpr(val name: Identifier, val value: DataLiteral<*>?) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitEnumMemberExpr(this)
    }

    override fun toString(): String
    {
        return "EnumMemberExpr{ $name ${if(value != null) "-> $value" else ""} }"
    }
}