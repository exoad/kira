package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Type

class VariableDecl(
    override val name: Identifier,
    val type: Type,
    val value: Expr
) :
    Decl(name)
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