package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp

class BinaryExpr(
    val leftExpr: Expr,
    val rightExpr: Expr,
    val operator: BinaryOp,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitBinaryExpr(this)
    }

    override fun toString(): String {
        return "Binary{ $leftExpr ${operator.symbol.joinToString { it.rep.toString() }} $rightExpr }"
    }
}


