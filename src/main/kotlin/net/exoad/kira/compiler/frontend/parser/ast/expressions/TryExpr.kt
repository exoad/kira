package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

open class TryExpr(
    val tryBlock: List<Statement>,
    val exceptionName: Identifier?,
    val exceptionType: Type?,
    val handlerBlock: List<Statement>
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTryExpr(this)
    }

    override fun toString(): String {
        return "TryExpr(tryCount=${tryBlock.size}, handlerCount=${handlerBlock.size}, exceptionName=${exceptionName ?: "<none>"}, exceptionType=${exceptionType ?: "<none>"})"
    }
}
