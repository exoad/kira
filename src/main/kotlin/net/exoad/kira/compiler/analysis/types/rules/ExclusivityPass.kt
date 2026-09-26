package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
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
 * - `rules.exclusivity.order`: a place is written while one operand is evaluated (a `mut`
 *   argument or the receiver of a `mut fx` of a call nested in it, or an assignment inside
 *   an if-expression in it) and read or written by a sibling operand of the same call or
 *   operator: `sz(b.items.size(), b.grow())`, `f(n, put(mut n, 3))`, `b.x + b.grow()`. Kira
 *   evaluates left to right (D33); C++ leaves the arguments of a call and the operands of an
 *   operator unsequenced, and a place is never spilled (R19), so the order would decide the
 *   value. The receiver of a call is sequenced before its arguments in C++17, so it is not a
 *   sibling of them; a construction's and an array literal's operands are sequenced too.
 *   Writes hidden inside a callee's body (a global it assigns) are EffectsPass's: both
 *   operands are IMPURE there and the emitter spills them.
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it), passes it or a place around it as `mut`, or calls a `mut fx` on it or on
 *   a place around it (`s.reset()` while iterating `s.items`): iterator invalidation.
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    /** One operand of a call: its place (when it has one), its node, and whether it is written. */
    private class Operand(val place: Place, val at: ASTNode, val text: String, val writes: Boolean, val isReceiver: Boolean)

    /** A place an expression touches while it is evaluated: the node that touches it, its text, and the place's own text. */
    private class Touch(val place: Place, val at: ASTNode, val text: String, val placeText: String)

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionCallExpr -> {
                        call(r, b, n)
                        val rc = r.model.calls[n]
                        if (rc != null) {
                            siblings(r, b, n, rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr })
                        }
                    }
                    is BinaryExpr -> if (n.operator != BinaryOp.AND && n.operator != BinaryOp.OR) {
                        siblings(r, b, n, listOf(n.leftExpr, n.rightExpr))
                    }
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

    /**
     * `rules.exclusivity.order` over the sibling [operands] of [e]: what evaluating one of
     * them writes against what evaluating any other reads or writes. Lambdas inside an
     * operand run later, or never: they are skipped.
     */
    private fun siblings(r: Rules, b: Body, e: Expr, operands: List<Expr>) {
        if (operands.size < 2) {
            return
        }
        val writes = operands.map { writesOf(r, b, it) }
        if (writes.all { it.isEmpty() }) {
            return
        }
        val reads = operands.map { readsOf(r, it) }
        val what = when (e) {
            is FunctionCallExpr -> "'${r.model.calls[e]?.fn?.name ?: KiraUnparser.text(e.name)}'"
            else -> "the operator"
        }
        // Once per pair of operands: a write in one against a read or write in the other, either way round.
        for (i in operands.indices) {
            for (j in i + 1 until operands.size) {
                val pair = overlap(writes[i], reads[j] + writes[j]) ?: overlap(writes[j], reads[i]) ?: continue
                val (w, hit) = pair
                val how = if (hit in writes[i] || hit in writes[j]) "written again" else "read"
                r.report(
                    "rules.exclusivity.order",
                    "'${w.text}' writes '${w.placeText}' while one operand of $what is evaluated, and '${hit.text}' is $how by " +
                        "another (D33, D37): Kira evaluates left to right, but C++ leaves them unsequenced and would decide the " +
                        "value by the order it picks. Evaluate one of them into a local first.",
                    hit.at,
                )
            }
        }
    }

    private fun overlap(writes: List<Touch>, touched: List<Touch>): Pair<Touch, Touch>? {
        for (w in writes) {
            touched.firstOrNull { it.place.overlaps(w.place) }?.let { return w to it }
        }
        return null
    }

    /** The places evaluating [e] writes: the mut receivers and mut arguments of every call inside it, and assignment targets inside it. */
    private fun writesOf(r: Rules, b: Body, e: Expr): List<Touch> {
        val out = mutableListOf<Touch>()
        AstScan.walk(listOf(e)) { n, lambdas ->
            if (lambdas.isNotEmpty()) {
                return@walk
            }
            when (n) {
                is FunctionCallExpr -> for (op in operands(r, b, n)) {
                    if (op.writes) {
                        out.add(Touch(op.place, n, if (op.isReceiver) KiraUnparser.text(n) else "mut ${op.text}", op.text))
                    }
                }
                is AssignmentExpr -> r.model.places[n.target]?.let { out.add(Touch(it, n, KiraUnparser.text(n), KiraUnparser.text(n.target))) }
                is CompoundAssignmentExpr -> r.model.places[n.left]?.let { out.add(Touch(it, n, KiraUnparser.text(n), KiraUnparser.text(n.left))) }
                is PlaceAssignmentExpr -> r.model.places[n.target]?.let { out.add(Touch(it, n, KiraUnparser.text(n), KiraUnparser.text(n.target))) }
                else -> {}
            }
        }
        return out
    }

    /** The places evaluating [e] reads: every place expression inside it (a lent view reads what it was lent from). */
    private fun readsOf(r: Rules, e: Expr): List<Touch> {
        val out = mutableListOf<Touch>()
        AstScan.walk(listOf(e)) { n, lambdas ->
            if (lambdas.isNotEmpty() || n !is Expr) {
                return@walk
            }
            r.placeOf(n)?.let { out.add(Touch(it, n, KiraUnparser.text(n), KiraUnparser.text(n))) }
        }
        return out
    }

    private fun loop(r: Rules, b: Body, s: ForIterationStatement) {
        val plan = r.model.loops[s] ?: return
        if (plan.kind == LoopKind.RANGE || plan.kind == LoopKind.RANGE_INCLUSIVE) {
            return
        }
        val target = s.forIterationExpr.target
        val iterated = r.placeOf(target) ?: return
        val iteratedText = KiraUnparser.text(target)
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
                            val around = if (op.place.path().size < iterated.path().size) "'${op.text}', which holds it" else "it"
                            writes(op.place, op.at, "calls the `mut fx` '${r.model.calls[n]?.fn?.name}' on $around")
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
