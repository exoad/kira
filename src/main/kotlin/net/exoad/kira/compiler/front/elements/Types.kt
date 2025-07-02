package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

open class Type(val name: String) : Expr()
{
    override fun toString(): String
    {
        return "Type{ '$name' }"
    }

    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitType(this)
    }
}