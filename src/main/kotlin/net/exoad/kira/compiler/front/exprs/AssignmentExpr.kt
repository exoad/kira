package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

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