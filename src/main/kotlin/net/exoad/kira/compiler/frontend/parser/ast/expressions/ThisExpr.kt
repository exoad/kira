package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

/**
 * The `this` keyword as a primary expression.
 *
 * A fresh instance per use, never a singleton: the typed model keys nodes by
 * identity, so two uses of `this` must be two nodes.
 */
class ThisExpr : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitThisExpr(this)
    }

    override fun toString(): String {
        return "This"
    }
}
