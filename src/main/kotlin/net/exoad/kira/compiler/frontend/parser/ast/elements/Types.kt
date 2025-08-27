package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class TypeSpecifier(
    val name: String,
    val childGenericTypeSpecifier: Array<TypeSpecifier> = emptyArray(),
) : Expr()
{
    override fun toString(): String
    {
        return "T< '$name'${
            if(childGenericTypeSpecifier.isEmpty()) ""
            else " ++ ${childGenericTypeSpecifier.joinToString { it.toString() }}"
        } >"
    }

    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitTypeSpecifier(this)
    }
}