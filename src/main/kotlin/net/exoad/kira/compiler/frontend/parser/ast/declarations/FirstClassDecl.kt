package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers

abstract class FirstClassDecl(
    override val name: Identifier,
    open val modifiers: List<Modifiers>,
) : Decl(name) {
    abstract fun isStub(): Boolean
}