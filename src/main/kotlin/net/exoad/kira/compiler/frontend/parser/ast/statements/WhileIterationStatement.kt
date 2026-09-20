package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

class WhileIterationStatement(val condition: Expr, val statements: List<Statement>) :
    Statement(condition) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitWhileIterationStatement(this)
    }

    override fun toString(): String {
        return "WhileIterationStatement(condition=$condition, body=$statements)"
    }
}