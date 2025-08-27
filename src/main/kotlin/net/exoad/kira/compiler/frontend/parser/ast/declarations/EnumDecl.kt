package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers
import net.exoad.kira.compiler.frontend.parser.ast.expressions.EnumMemberExpr

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
            append("__ENUM__")
            append(modifiers.ifEmpty { "" })
            append("{ ")
            append(name)
            append(" -> ")
            append(members)
            append(" }")
        }
    }
}