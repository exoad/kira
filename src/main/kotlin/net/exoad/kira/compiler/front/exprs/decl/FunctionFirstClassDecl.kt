package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.FunctionLiteral
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers

open class FunctionFirstClassDecl(
    override val name: Identifier,
    open val value: FunctionLiteral,
    override val modifiers: List<Modifiers> = emptyList()
) : FirstClassDecl(name, modifiers)
{
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