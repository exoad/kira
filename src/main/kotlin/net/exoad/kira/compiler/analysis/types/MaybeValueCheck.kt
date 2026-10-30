package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourcePosition

/**
 * A `.value` of a Maybe read with no `isNull()` check on the same Maybe earlier, in the same block or in an
 * enclosing block of its function: a warning, `types.maybe.unchecked-value`, that names the variable. The
 * runtime panic of `.value` on null stays; this only says where a check is missing.
 *
 * This is not a flow analysis. Its limits:
 * - the Maybe is matched by its text: a local or parameter (`m`), or a field path (`h.kid`). A shadowing local
 *   of the same name in an inner block is not told apart from the outer one;
 * - a check counts when it is textually earlier, and its block is the read's block or one enclosing it in the
 *   same function. A check in a sibling branch, in another function, or after the read does not count;
 * - an `if` or `while` condition belongs to the block that holds the statement, so it counts for every branch
 *   it guards, including the else branch that runs when the condition is false;
 * - only `isNull()` counts as a check: `isSome()`, `isNone()` and `== null` do not;
 * - a lambda is its own function: its reads need a check inside the lambda.
 *
 * The workspace's modules only; the stdlib is not warned about.
 */
internal class MaybeValueCheck(private val program: TypedProgram) {
    /** One use of a Maybe: a read of its value or a null check, with the blocks that enclose it, outermost first. */
    private class Use(val key: String, val node: Expr, val blocks: List<Any>, val at: SourcePosition?, val isCheck: Boolean)

    /** Identifies one block (one statement list) of a function; compared by identity. */
    private class Block

    private val uses = mutableListOf<Use>()

    fun warn() {
        program.workspaceModules.forEach { module ->
            val root = runCatching { module.source.ast }.getOrNull() ?: return@forEach
            functionBodies(root).forEach { body ->
                uses.clear()
                statements(body, listOf(Block()))
                reportUnchecked()
            }
        }
    }

    /** Every function body in [root] (a declaration's, a method's and a lambda's), each once. */
    private fun functionBodies(root: ASTNode): List<List<Statement>> {
        val out = mutableListOf<List<Statement>>()
        AstTree.walk(root) { node ->
            if (node is FunctionDefExpr) node.body?.let { out.add(it) }
        }
        return out
    }

    /** The statements of one block; [blocks] already ends with this block's own identity. */
    private fun statements(list: List<Statement>, blocks: List<Any>) {
        list.forEach { statement(it, blocks) }
    }

    private fun statement(s: Statement, blocks: List<Any>) {
        when (s) {
            is IfSelectionStatement -> {
                expression(s.expr, blocks)
                statements(s.thenStatements, blocks + Block())
                s.elseBranches.forEach { branch ->
                    when (branch) {
                        is ElseIfBranchStatement -> {
                            expression(branch.condition, blocks)
                            statements(branch.statements, blocks + Block())
                        }
                        is ElseBranchStatement -> statements(branch.statements, blocks + Block())
                    }
                }
            }
            is WhileIterationStatement -> {
                expression(s.condition, blocks)
                statements(s.statements, blocks + Block())
            }
            is ForIterationStatement -> {
                expression(s.forIterationExpr, blocks)
                statements(s.body, blocks + Block())
            }
            else -> AstTree.children(s).forEach { if (it is Expr) expression(it, blocks) }
        }
    }

    /** An expression's uses, in source order; a block inside it (an if-value's arms) is a block of its own. */
    private fun expression(e: ASTNode, blocks: List<Any>) {
        when (e) {
            is FunctionDefExpr -> return
            is PlaceAssignmentExpr -> {
                // The target is written, not read; a Maybe's own `.value` as a target is not a use.
                val target = e.target
                if (target is MemberAccessExpr) expression(target.origin, blocks) else expression(target, blocks)
                expression(e.value, blocks)
                return
            }
            is Statement -> {
                statement(e, blocks)
                return
            }
            is MemberAccessExpr -> record(e, blocks)
        }
        val inner = blocks + Block()
        AstTree.children(e).forEach { kid ->
            if (kid is Statement) statement(kid, inner) else expression(kid, blocks)
        }
    }

    /** Records [m] as a check (`x.isNull()`) or a read (`x.value`) of a Maybe. */
    private fun record(m: MemberAccessExpr, blocks: List<Any>) {
        if (!isMaybe(program.model.types[m.origin])) return
        // `x.isNull()` is a call whose name is this access, so the member is the bare name.
        val isCheck = (m.member as? Identifier)?.value == "isNull"
        val isRead = !isCheck && (program.model.member(m) as? MemberRef.Field)?.field?.name == "value"
        if (!isCheck && !isRead) return
        // The receiver's place: a method call's node carries no origin of its own in some parses.
        val at = program.locate(m.origin)?.second ?: program.locate(m)?.second
        uses.add(Use(KiraUnparser.text(m.origin), m, blocks, at, isCheck))
    }

    private fun isMaybe(t: KType?): Boolean {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.MAGIC && sym.name == "Maybe"
    }

    /** Warns each read that no earlier check of its Maybe, in its block or an enclosing one, precedes. */
    private fun reportUnchecked() {
        uses.filter { !it.isCheck }.forEach { read ->
            val checked = uses.any { check ->
                check.isCheck && check.key == read.key && isPrefix(check.blocks, read.blocks) && earlier(check.at, read.at)
            }
            if (!checked) {
                program.report(
                    CODE,
                    "'${read.key}.value' is read with no isNull() check on '${read.key}' earlier in its block or an enclosing one: a null ${read.key} panics here",
                    read.node,
                    Severity.WARNING,
                )
            }
        }
    }

    private fun isPrefix(outer: List<Any>, inner: List<Any>): Boolean =
        outer.size <= inner.size && outer.indices.all { outer[it] === inner[it] }

    /** Whether position [a] is textually before [b]; an unknown position is never before, and a read's unknown one is last. */
    private fun earlier(a: SourcePosition?, b: SourcePosition?): Boolean {
        if (a == null || a == SourcePosition.UNKNOWN) return false
        if (b == null || b == SourcePosition.UNKNOWN) return true
        return a < b
    }

    companion object {
        const val CODE = "types.maybe.unchecked-value"
    }
}
