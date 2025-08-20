package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier

open class AssignmentExpr(
    val target: Identifier,
    val value: Expr,
) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitAssignmentExpr(this)
    }

    override fun toString(): String
    {
        return "AssignmentExpr{ ${target.name} -> $value }"
    }
}