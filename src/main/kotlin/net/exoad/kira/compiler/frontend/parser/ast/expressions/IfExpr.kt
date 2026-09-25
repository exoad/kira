package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * `if c { a } else { b }` in expression position. The `else` is mandatory; an
 * `else if` nests another [IfExpr] as the single statement of [elseBranch].
 * A branch's value is the `expr` of its last [Statement].
 */
open class IfExpr(
    val condition: Expr,
    val thenBranch: List<Statement>,
    val elseBranch: List<Statement>,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIfExpr(this)
    }

    override fun toString(): String {
        return "IfExpr(condition=$condition, then=$thenBranch, else=$elseBranch)"
    }
}
