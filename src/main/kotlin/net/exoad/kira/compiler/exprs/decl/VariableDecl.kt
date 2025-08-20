package net.exoad.kira.compiler.exprs.decl

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier
import net.exoad.kira.compiler.elements.Modifiers
import net.exoad.kira.compiler.elements.TypeSpecifier
import net.exoad.kira.compiler.exprs.Expr

open class VariableDecl(
    override val name: Identifier,
    open val typeSpecifier: TypeSpecifier,
    open val value: Expr?, // if this is null, then this is a "noimpl" or "noval" , see [isStub]
    override val modifiers: List<Modifiers> = emptyList(),
) : FirstClassDecl(name, modifiers)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitVariableDecl(this)
    }

    override fun toString(): String
    {
        return "VariableDeclaration[[ $modifiers  ]]{ $name ($typeSpecifier) := $value}"
    }

    override fun isStub(): Boolean
    {
        return value == null
    }
}