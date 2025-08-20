package net.exoad.kira.compiler.exprs.decl

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier
import net.exoad.kira.compiler.elements.Modifiers
import net.exoad.kira.compiler.exprs.EnumMemberExpr

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