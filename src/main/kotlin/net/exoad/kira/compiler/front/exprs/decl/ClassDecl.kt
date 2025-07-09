package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Modifiers
import net.exoad.kira.compiler.front.elements.TypeSpecifier

/**
 * TODO: Only the root object type `Any` will have [parent] marked as null
 */
open class ClassDecl(
    override val name: TypeSpecifier,
    val modifiers: List<Modifiers> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parent: TypeSpecifier? = null
) : Decl(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String
    {
        return "ClassDecl[[ $modifiers ]]{ $name -> $members }"
    }
}

