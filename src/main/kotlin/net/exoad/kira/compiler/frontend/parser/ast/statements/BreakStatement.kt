package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr

open class BreakStatement : Statement(NoExpr) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitBreakStatement(this)
    }

    override fun toString(): String {
        return "BREAK{ }"
    }
}

