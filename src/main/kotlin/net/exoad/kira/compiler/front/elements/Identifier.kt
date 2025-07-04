package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

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
}
