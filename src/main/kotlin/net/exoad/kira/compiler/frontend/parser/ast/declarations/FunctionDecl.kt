package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousFunction
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers

open class FunctionDecl(
    override val name: Identifier,
    open val value: AnonymousFunction,
    override val modifiers: List<Modifiers> = emptyList(),
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