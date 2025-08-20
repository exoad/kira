package net.exoad.kira.compiler.exprs.decl

import net.exoad.kira.compiler.elements.Identifier
import net.exoad.kira.compiler.elements.Modifiers

abstract class FirstClassDecl(
    override val name: Identifier,
    open val modifiers: List<Modifiers>,
) : Decl(name)
{
    abstract fun isStub(): Boolean
}