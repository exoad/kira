package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

class TraitDecl(
    override val name: Type,
    val modifiers: Array<Modifier>,
    val members: List<FunctionDecl>,
    val parents: List<Type> = emptyList()
) :
    Decl(name) {
    init {
        require(members.count { it.isAnonymous() } < 2) { "Trait members can only have ONE anonymous member." }
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTraitDecl(this)
    }

    override fun toString(): String {
        return "TraitDecl(name=$name, modifiers=${modifiers.ifEmpty { "[]" }}, members=$members, parents=${parents.ifEmpty { "[]" }})"
    }
}