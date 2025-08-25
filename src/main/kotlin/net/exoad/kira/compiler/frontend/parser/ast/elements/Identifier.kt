package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsSymbols
import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class Identifier(open val name: String) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIdentifier(this)
    }

    override fun toString(): String
    {
        return "Identifier{ '$name' }"
    }

    override fun hashCode(): Int
    {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean
    {
        if(this === other)
        {
            return true
        }
        if(javaClass != other?.javaClass)
        {
            return false
        }
        other as Identifier
        return name == other.name
    }
}

object AnonymousIdentifier : Identifier(DiagnosticsSymbols.NOT_REPRESENTABLE)
{
    override fun toString(): String
    {
        return "AnonymousIdentifier{  }"
    }

    override fun hashCode(): Int
    {
        return DiagnosticsSymbols.NOT_REPRESENTABLE.hashCode()
    }

    override fun equals(other: Any?): Boolean
    {
        if(this === other)
        {
            return true
        }
        if(javaClass != other?.javaClass)
        {
            return false
        }
        return true
    }
}