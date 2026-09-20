package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/**
 * TODO: Only the root object type `Any` will have [parent] marked as null
 */
open class ClassDecl(
    override val name: Type,
    val modifiers: List<Modifier> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parents: List<Type> = emptyList(),
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String {
        return "Class(name=$name, mods=${modifiers.ifEmpty { "[]" }}, members=$members, parents=${parents.ifEmpty { "[]" }})"
    }
}
