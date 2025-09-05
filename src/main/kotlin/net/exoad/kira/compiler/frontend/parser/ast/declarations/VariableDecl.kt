package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class VariableDecl(
    override val name: Identifier,
    open val type: Type,
    open val value: Expr?, // if this is null, then this is a "noimpl" or "noval" , see [isStub]
    override val modifiers: List<Modifiers> = emptyList(),
) : FirstClassDecl(name, modifiers) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitVariableDecl(this)
    }

    override fun toString(): String {
        return "__VAR__${modifiers.ifEmpty { "" }}{ $name ($type) := $value}"
    }

    override fun isStub(): Boolean {
        return value == null
    }
}