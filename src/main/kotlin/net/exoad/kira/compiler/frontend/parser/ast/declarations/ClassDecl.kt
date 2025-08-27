package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier

/**
 * TODO: Only the root object type `Any` will have [parent] marked as null
 */
open class ClassDecl(
    override val name: TypeSpecifier,
    val modifiers: List<Modifiers> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parent: TypeSpecifier? = null,
) : Decl(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String
    {
        return "__CLASS__${modifiers.ifEmpty { "" }}{ $name -> $members }"
    }
}

