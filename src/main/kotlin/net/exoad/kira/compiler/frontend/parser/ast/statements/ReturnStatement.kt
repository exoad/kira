package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/**
 * `return e`. [implicit]: a value body's last expression, written without `return` (a block's
 * last expression is its value); a Never one lowers as the statement it was written as.
 */
open class ReturnStatement(override val expr: Expr, val implicit: Boolean = false) : Statement(expr) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitReturnStatement(this)
    }

    override fun toString(): String {
        return "ReturnStatement(expr=$expr)"
    }
}