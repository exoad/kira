package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor

/**
 * An inclusive range where [begin] and [end] are both included aka [begin, end] not [begin, end)
 */
open class RangeExpr(val begin: Expr, val end: Expr) : ForIterationTargetExpr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitRangeExpr(this)
    }

    override fun toString(): String
    {
        return "ForIterationTargetRangeExpr{ [$begin, $end] }"
    }
}