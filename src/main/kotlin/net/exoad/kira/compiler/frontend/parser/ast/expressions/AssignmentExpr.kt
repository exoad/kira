package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

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
        return "Assign{ ${target.name} -> $value }"
    }
}