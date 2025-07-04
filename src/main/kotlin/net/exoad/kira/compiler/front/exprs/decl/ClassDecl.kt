package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Modifiers
import net.exoad.kira.compiler.front.elements.Type

open class ClassDecl(
    override val name: Type,
    val modifiers: List<Modifiers> = emptyList(),
    val members: List<FirstClassDecl> = emptyList()
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

