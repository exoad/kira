package net.exoad.kira.compiler.frontend.expressions.declarations

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.compiler.frontend.elements.IdentifierNode
import net.exoad.kira.compiler.frontend.elements.TypeNode

class VariableDeclarationNode(
    override val name: IdentifierNode,
    val type: TypeNode,
    val value: ExpressionNode
) :
    DeclarationsNode(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitVariableDeclaration(this)
    }

    override fun toString(): String
    {
        return "VariableDeclaration{ $name ($type) := $value}"
    }
}