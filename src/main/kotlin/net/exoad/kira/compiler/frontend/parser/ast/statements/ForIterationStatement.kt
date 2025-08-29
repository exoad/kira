package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr

class ForIterationStatement(val forIterationExpr: ForIterationExpr, val body: List<Statement>) :
    Statement(forIterationExpr) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitForIterationStatement(this)
    }

    override fun toString(): String {
        return "FOR{ $forIterationExpr -> $body }"
    }
}