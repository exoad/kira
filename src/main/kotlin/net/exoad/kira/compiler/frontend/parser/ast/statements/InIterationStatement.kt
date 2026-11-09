package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.InIterationExpr

/** `while NAME: TYPE in EXPR { body }`: the loop over a range, a collection or a view (a `while <condition>` is [WhileIterationStatement]). */
class InIterationStatement(val inIterationExpr: InIterationExpr, val body: List<Statement>) :
    Statement(inIterationExpr) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitInIterationStatement(this)
    }

    override fun toString(): String {
        return "InIterationStatement(expr=$inIterationExpr, body=$body)"
    }
}