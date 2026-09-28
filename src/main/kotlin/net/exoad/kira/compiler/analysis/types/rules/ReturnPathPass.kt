package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ContinueStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement

/**
 * A function, method, trait default or lambda that returns a value returns (or throws) on
 * every path (design 3.4; C++'s `-Wreturn-type` is an error under `-Werror`):
 * `rules.return.missing`.
 *
 * A path ends at a `return`, a `throw`, a call typed `Never` (`panic`), an `if` whose every
 * branch (else included) ends, a `try` whose block and handler both end, a `do`/`while` whose
 * body ends with no `break` or `continue` of its own to leave the body before its end, or a
 * `while true` or `do`/`while true` with no `break` of its own (a `continue` only re-enters
 * it). Any other loop (a `for`, a `while` over a condition that is not the constant `true`)
 * may run its body zero times or leave, so it ends no path. Statements after an end are
 * unreachable and change nothing.
 */
internal class ReturnPathPass : RulePass {
    override val name: String = "return"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            val fn = b.fn
            if (fn != null && needsReturn(fn.ret) && !ends(r, b.roots.filterIsInstance<Statement>())) {
                r.report(
                    "rules.return.missing",
                    "${b.what} returns ${fn.ret.display()}, but not on every path: add a return (or a throw) at the end.",
                    fn.decl,
                )
            }
            AstScan.walk(b.roots) { n, _ ->
                if (n is LambdaExpr) {
                    val ret = (r.model.types[n] as? KType.Fn)?.ret ?: return@walk
                    val body = n.def.body ?: return@walk
                    if (needsReturn(ret) && !ends(r, body)) {
                        r.report(
                            "rules.return.missing",
                            "This lambda returns ${ret.display()}, but not on every path: add a return (or a throw) at the end.",
                            n,
                        )
                    }
                }
            }
        }
    }

    private fun needsReturn(ret: KType): Boolean = ret != KType.Void && !ret.containsError()

    private fun ends(r: Rules, statements: List<Statement>): Boolean = statements.any { ends(r, it) }

    private fun ends(r: Rules, s: Statement): Boolean = when (s) {
        is ReturnStatement -> true
        is IfSelectionStatement -> {
            var hasElse = false
            var all = ends(r, s.thenStatements)
            for (b in s.elseBranches) {
                when (b) {
                    is ElseIfBranchStatement -> all = all && ends(r, b.statements)
                    is ElseBranchStatement -> {
                        hasElse = true
                        all = all && ends(r, b.statements)
                    }
                }
            }
            hasElse && all
        }
        is WhileIterationStatement -> isTrue(r, s.condition) && !breaks(s.statements)
        is DoWhileIterationStatement ->
            (ends(r, s.statements) && !breaks(s.statements) && !continues(s.statements)) || (isTrue(r, s.condition) && !breaks(s.statements))
        is ForIterationStatement -> false
        else -> endsExpr(r, s.expr)
    }

    private fun endsExpr(r: Rules, e: Expr): Boolean = when (e) {
        is ThrowExpr -> true
        is TryExpr -> ends(r, e.tryBlock) && ends(r, e.handlerBlock)
        is IfExpr -> ends(r, e.thenBranch) && ends(r, e.elseBranch)
        else -> r.model.types[e] == KType.Never
    }

    private fun isTrue(r: Rules, cond: Expr): Boolean = (r.model.consts[cond] as? ConstValue.BoolConst)?.value == true

    /** A `break` that leaves this loop (one inside a nested loop leaves that loop). */
    private fun breaks(statements: List<Statement>): Boolean = statements.any { breaks(it) }

    private fun breaks(n: ASTNode): Boolean = when (n) {
        is BreakStatement -> true
        is WhileIterationStatement, is DoWhileIterationStatement, is ForIterationStatement -> false
        is LambdaExpr -> false
        else -> AstTree.children(n).any { breaks(it) }
    }

    /** A `continue` that re-enters this loop (one inside a nested loop re-enters that loop). */
    private fun continues(statements: List<Statement>): Boolean = statements.any { continues(it) }

    private fun continues(n: ASTNode): Boolean = when (n) {
        is ContinueStatement -> true
        is WhileIterationStatement, is DoWhileIterationStatement, is ForIterationStatement -> false
        is LambdaExpr -> false
        else -> AstTree.children(n).any { continues(it) }
    }
}
