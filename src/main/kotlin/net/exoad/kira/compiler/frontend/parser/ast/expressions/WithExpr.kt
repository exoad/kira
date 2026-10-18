package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * `receiver with { body }`, the builder operator: the body uses the receiver's members bare, and
 * the value is the receiver (a copy of it when it cannot change in place). Lowered inline, never a lambda.
 */
open class WithExpr(val receiver: Expr, val body: List<Statement>) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitWithExpr(this)
    }

    override fun toString(): String {
        return "With{ $receiver -> $body }"
    }
}
