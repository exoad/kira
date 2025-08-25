package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.FunctionLiteral
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers

open class FunctionDecl(
    override val name: Identifier,
    open val value: FunctionLiteral,
    override val modifiers: List<Modifiers> = emptyList(),
) : FirstClassDecl(name, modifiers)
{
    fun isAnonymous(): Boolean
    {
        return name is AnonymousIdentifier
    }

    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionDecl(this)
    }

    override fun toString(): String
    {
        return "FunctionDecl[[ $modifiers ]]{ ${if(isAnonymous()) "(_Anon_)" else ""} $name -> $value}"
    }

    override fun isStub(): Boolean
    {
        return value.body == null
    }
}