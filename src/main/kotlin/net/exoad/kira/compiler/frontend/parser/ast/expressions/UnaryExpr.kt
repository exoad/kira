package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp

class UnaryExpr(val operator: UnaryOp, val operand: Expr) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitUnaryExpr(this)
    }

    override fun toString(): String {
        return "Unary{ $operator -> $operand }"
    }
}

