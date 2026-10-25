package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * `cond ? a : b`: an if-expression whose arms are one expression each, so every pass and backend
 * that reads an [IfExpr] reads it; only the spelling differs.
 */
class TernaryExpr(condition: Expr, yes: Statement, no: Statement) : IfExpr(condition, listOf(yes), listOf(no)) {
    /** The first arm's value (no field of its own: the walks see each arm once, as a branch). */
    val yes: Expr get() = thenBranch[0].expr

    val no: Expr get() = elseBranch[0].expr

    override fun toString(): String {
        return "Ternary(condition=$condition, yes=$yes, no=$no)"
    }
}
