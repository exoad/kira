package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier

abstract class FirstClassDecl(
    override val name: Identifier,
    open val modifiers: List<Modifier>,
) : Decl(name) {
    abstract fun isStub(): Boolean
}