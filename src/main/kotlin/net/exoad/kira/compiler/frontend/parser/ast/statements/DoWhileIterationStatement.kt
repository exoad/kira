package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

class DoWhileIterationStatement(val condition: Expr, val statements: List<Statement>) :
    Statement(expr = condition) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitDoWhileIterationStatement(this)
    }

    override fun toString(): String {
        return "DoWhile{ $condition -> $statements }"
    }
}