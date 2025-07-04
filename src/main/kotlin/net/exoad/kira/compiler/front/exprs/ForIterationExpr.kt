package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

open class ForIterationExpr(val initializer: Identifier, val target: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitForIterationExpr(this)
    }

    override fun toString(): String
    {
        return "ForIterationExpr{ $initializer -> $target }"
    }
}

abstract class ForIterationTargetExpr : Expr()

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