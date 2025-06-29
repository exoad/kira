package net.exoad.kira.compiler.frontend.elements

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

open class IdentifierNode(val name: String) : ExpressionNode()
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
