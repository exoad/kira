package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.AbsoluteFileLocation
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers
import net.exoad.kira.compiler.front.exprs.EnumMemberExpr

class EnumDecl(
    override val name: Identifier,
    val members: Array<EnumMemberExpr>,
    val modifiers: List<Modifiers> = emptyList(),
) : Decl(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitEnumDecl(this)
    }

    override fun toString(): String
    {
        return buildString {
            append("EnumDecl")
            append(if(modifiers.isNotEmpty()) "[[ $modifiers ]]" else "")
            append("{ ")
            append(name)
            append(" -> ")
            append(members)
            append(" }")
        }
    }
}