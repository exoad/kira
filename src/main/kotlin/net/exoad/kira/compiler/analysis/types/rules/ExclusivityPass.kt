package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement

/**
 * Exclusivity (design 3.4, D37): a place written through one name must not be read or
 * written through another in the same call, and a loop must not change what it iterates.
 *
 * - `rules.exclusivity.argument`: a `mut` argument's place overlaps another argument of the
 *   same call (the same root, and one path a prefix of the other; a lent view aliases what it
 *   was lent from). C++ would pass one object by `T&` and by `const T&` at once.
 * - `rules.exclusivity.receiver`: a `mut` argument's place overlaps the call's receiver, or
 *   the receiver of a `mut fx` overlaps another argument.
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it), passes it as `mut`, or calls a `mut fx` on it: iterator invalidation.
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    /** One operand of a call: its place (when it has one), its node, and whether it is written. */
    private class Operand(val place: Place, val at: ASTNode, val text: String, val writes: Boolean, val isReceiver: Boolean)

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionCallExpr -> call(r, b, n)
                    is ForIterationStatement -> loop(r, b, n)
                    else -> {}
                }
            }
        }
    }

    private fun operands(r: Rules, b: Body, e: FunctionCallExpr): List<Operand> {
        val rc = r.model.calls[e] ?: return emptyList()
        val out = mutableListOf<Operand>()
        val fn = rc.fn
        val receiverWrites = fn?.isMutMethod == true
        if (rc.implicitThis) {
            b.owner?.let { out.add(Operand(Place.This(it), (e.name as? MemberAccessExpr)?.member ?: e.name, "this", receiverWrites, true)) }
        } else {
            rc.receiver?.let { recv -> r.placeOf(recv)?.let { out.add(Operand(it, recv, KiraUnparser.text(recv), receiverWrites, true)) } }
        }
        for (a in rc.args) {
            val given = a as? ArgBinding.Given ?: continue
            val place = r.placeOf(given.expr) ?: continue
            out.add(Operand(place, given.expr, KiraUnparser.text(given.expr), given.byRef, false))
        }
        return out
    }

    private fun call(r: Rules, b: Body, e: FunctionCallExpr) {
        val ops = operands(r, b, e)
        for (i in ops.indices) {
            val w = ops[i]
            if (!w.writes) {
                continue
            }
            for (j in ops.indices) {
                if (i == j) {
                    continue
                }
                val o = ops[j]
                if (!w.place.overlaps(o.place)) {
                    continue
                }
                if (o.writes && j < i) {
                    // Reported once, from the first of two mut operands.
                    continue
                }
                val fnName = r.model.calls[e]?.fn?.name ?: "this call"
                when {
                    w.isReceiver -> r.report(
                        "rules.exclusivity.receiver",
                        "'$fnName' is a `mut fx` that writes its receiver '${w.text}', and the argument '${o.text}' overlaps it (D37): " +
                            "C++ would pass one object by reference twice. Copy the argument first.",
                        o.at,
                    )
                    o.isReceiver -> r.report(
                        "rules.exclusivity.receiver",
                        "`mut ${w.text}` overlaps the receiver '${o.text}' of '$fnName' (D37): the callee writes what its receiver " +
                            "reads. Copy one of them first.",
                        w.at,
                    )
                    else -> r.report(
                        "rules.exclusivity.argument",
                        "`mut ${w.text}` overlaps the argument '${o.text}' of '$fnName' (D37): a place passed as mut must not be passed " +
                            "again in the same call. Copy one of them first.",
                        w.at,
                    )
                }
            }
        }
    }

    private fun loop(r: Rules, b: Body, s: ForIterationStatement) {
        val plan = r.model.loops[s] ?: return
        if (plan.kind == LoopKind.RANGE || plan.kind == LoopKind.RANGE_INCLUSIVE) {
            return
        }
        val target = s.forIterationExpr.target
        val iterated = r.placeOf(target) ?: return
        val iteratedText = KiraUnparser.text(target)
        val iteratedPath = iterated.path()
        fun inside(p: Place): Boolean = p.overlaps(iterated) && p.path().size >= iteratedPath.size
        fun writes(p: Place?, at: ASTNode, how: String) {
            if (p != null && p.overlaps(iterated)) {
                r.report(
                    "rules.exclusivity.loop",
                    "This loop iterates '$iteratedText', and its body $how (D37): changing a collection while iterating it " +
                        "invalidates the iteration. Collect the changes and apply them after the loop.",
                    at,
                )
            }
        }
        AstScan.walk(s.body) { n, lambdas ->
            if (lambdas.isNotEmpty()) {
                return@walk
            }
            when (n) {
                is AssignmentExpr -> writes(r.model.places[n.target], n.target, "assigns '${KiraUnparser.text(n.target)}'")
                is CompoundAssignmentExpr -> writes(r.model.places[n.left], n.left, "assigns '${KiraUnparser.text(n.left)}'")
                is PlaceAssignmentExpr -> writes(r.model.places[n.target], n.target, "assigns '${KiraUnparser.text(n.target)}'")
                is FunctionCallExpr -> {
                    for (op in operands(r, b, n)) {
                        if (!op.writes) {
                            continue
                        }
                        if (op.isReceiver) {
                            if (inside(op.place)) {
                                writes(op.place, op.at, "calls the `mut fx` '${r.model.calls[n]?.fn?.name}' on it")
                            }
                        } else {
                            writes(op.place, op.at, "passes '${op.text}' as mut")
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
