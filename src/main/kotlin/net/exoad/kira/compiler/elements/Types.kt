package net.exoad.kira.compiler.elements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

open class TypeSpecifier(
    val name: String,
    val nullable: Boolean,
    val childGenericTypeSpecifier: Array<TypeSpecifier> = emptyArray(),
) : Expr()
{
    override fun toString(): String
    {
        return "Type${if(childGenericTypeSpecifier.isEmpty()) "" else childGenericTypeSpecifier.toString()}{ '$name'${if(nullable) ":Nullable" else ""} }"
    }

    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitTypeSpecifier(this)
    }
}