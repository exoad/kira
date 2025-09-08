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
    val parent: Type? = null,
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String {
        return "__CLASS__${modifiers.ifEmpty { "" }}{ $name -> $members }"
    }
}

