package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp

/**
 * Assignment to a place that is not a bare identifier: `a.b = c`, `a[i] = c`,
 * `this.x = y`, and the compound forms `a.b += c`. [operator] is null for a
 * plain `=`. [AssignmentExpr] (identifier target) and the identifier-left
 * [CompoundAssignmentExpr] are unchanged.
 */
open class PlaceAssignmentExpr(
    val target: Expr,
    val operator: BinaryOp?,
    val value: Expr,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitPlaceAssignmentExpr(this)
    }

    override fun toString(): String {
        return "PlaceAssign(target=$target, op=${operator ?: "="}, value=$value)"
    }
}
