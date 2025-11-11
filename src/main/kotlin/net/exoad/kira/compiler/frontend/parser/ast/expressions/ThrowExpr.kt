package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class ThrowExpr(val value: Expr) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitThrowExpr(this)
    }

    override fun toString(): String {
        return "Throw{ ${value} }"
    }
}
