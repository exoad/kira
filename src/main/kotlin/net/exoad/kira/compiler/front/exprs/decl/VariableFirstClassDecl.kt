package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers
import net.exoad.kira.compiler.front.elements.Type

open class VariableFirstClassDecl(
    override val name: Identifier,
    open val type: Type,
    open val value: Expr?, // if this is null, then this is a "noimpl" or "noval" , see [isStub]
    override val modifiers: List<Modifiers> = emptyList()
) : FirstClassDecl(name, modifiers)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitVariableDecl(this)
    }

    override fun toString(): String
    {
        return "VariableDeclaration[[ $modifiers  ]]{ $name ($type) := $value}"
    }

    override fun isStub(): Boolean
    {
        return value == null
    }
}