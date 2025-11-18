package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

class VariantDecl(
    override val name: Type,
    val modifiers: List<Modifier> = emptyList(),
    val variants: List<ClassDecl> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parents: List<Type> = emptyList(),
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitVariantDecl(this)
    }

    override fun toString(): String {
        return "VariantDecl(name=$name, modifiers=${modifiers.ifEmpty { "[]" }}, variants=$variants, members=$members, parents=${parents.ifEmpty { "[]" }})"
    }
}