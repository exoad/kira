package net.exoad.kira.compiler.frontend.elements

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

open class TypeNode(val name: String) : ExpressionNode()
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