package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.AnonymousIdentifier
import net.exoad.kira.compiler.front.elements.AnonymousFunction
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers

open class FunctionFirstClassDecl(
    override val name: Identifier,
    open val value: AnonymousFunction,
    override val modifiers: List<Modifiers> = emptyList()
) : FirstClassDecl(name, modifiers)
{
    init
    {
        assert(name !is AnonymousIdentifier) { "Anonymous Functions should prefer to use raw function literals. This is a compiler bug." }
    }

    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionDecl(this)
    }

    override fun toString(): String
    {
        return "FunctionDecl[[ $modifiers ]]{ $name -> $value}"
    }

    override fun isStub(): Boolean
    {
        return value.body == null
    }
}