package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.AbsoluteFileLocation
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers

abstract class FirstClassDecl(
    override val name: Identifier,
    open val modifiers: List<Modifiers>,
) : Decl(name)
{
    abstract fun isStub(): Boolean
}