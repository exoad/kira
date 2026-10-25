package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

/**
 * `fx (params) Ret { body }` in expression position. A statement-level
 * `fx name: (...)` stays a [net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl].
 * Captures are by value and immutable (design 2.1).
 */
open class LambdaExpr(val def: FunctionDefExpr) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitLambdaExpr(this)
    }

    override fun toString(): String {
        return "Lambda($def)"
    }
}
