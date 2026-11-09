package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

/**
 * `a..b`: [begin] and [end] are both included, [begin, end]; with [exclusive] (`a..<b`) [end] is left out, [begin, end),
 * and the range is empty when [begin] is not below [end].
 */
open class RangeExpr(val begin: Expr, val end: Expr, val exclusive: Boolean = false) : InIterationTargetExpr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitRangeExpr(this)
    }

    override fun toString(): String {
        return if (exclusive) "Range{ [$begin, $end) }" else "Range{ [$begin, $end] }"
    }
}