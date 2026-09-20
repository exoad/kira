package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class ReturnStatement(override val expr: Expr) : Statement(expr) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitReturnStatement(this)
    }

    override fun toString(): String {
        return "ReturnStatement(expr=$expr)"
    }
}