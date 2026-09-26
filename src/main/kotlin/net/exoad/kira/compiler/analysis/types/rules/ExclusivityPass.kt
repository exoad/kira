package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassKind
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement

/**
 * Exclusivity (design 3.4, D37): a place written through one name must not be read or
 * written through another in the same call, and a loop must not change what it iterates.
 *
 * A call writes a place through a `mut` argument, the receiver of a `mut fx`, a `MutView`
 * argument or receiver (`fill(arr.view())`, `v.set(0, 9)`): [Rules.callOperands]. A lent
 * view stands for what it was lent from, and a view local (or a local holding a view) for
 * what was stored in it ([ViewAliases]), so `mv: MutView<T> = arr.view()` makes `fill(mv)` a
 * write of `arr`.
 *
 * - `rules.exclusivity.argument`: a written argument's place overlaps another argument of the
 *   same call (the same root, and one path a prefix of the other). C++ would pass one object
 *   by `T&` and by `const T&` at once.
 * - `rules.exclusivity.receiver`: a written argument's place overlaps the call's receiver, or
 *   the receiver of a `mut fx` overlaps another argument.
 * - `rules.exclusivity.order`: a place is written while one operand is evaluated (a call
 *   nested in it that writes the place, or an assignment inside an if-expression in it) and
 *   read or written by a sibling operand: `sz(b.items.size(), b.grow())`, `f(n, put(mut n,
 *   3))`, `b.x + b.grow()`, `pair8(arr[0], fill(arr.view()))`. Kira evaluates left to right
 *   (D33); C++ leaves these unsequenced, or sequences them the other way round, and a place
 *   is never spilled (R19), so the order would decide the value. The siblings are: a call's
 *   receiver and arguments (a Str or container method binds to a free function, and even a
 *   member call evaluates a place receiver to a reference, not a value); the operands of a
 *   binary operator (not `&&`/`||`, which sequence); the target and value of a compound
 *   assignment (C++ evaluates the right side first, so `n += bump(mut n)` reads the new `n`);
 *   the sub-expressions of an assignment's target (its indices) and its value (`xs[i] =
 *   bump(mut i)`); the arguments of a class construction (`std::make_shared<C>(a, b)` is a
 *   call; a struct's designated initializer and an array literal are sequenced); and the
 *   holes of an interpolated string (`kira::cat`). The receiver of a `mut fx` is not a
 *   sibling read: the callee takes it by reference after the arguments ran, in both
 *   languages, and `rules.exclusivity.receiver` covers a `mut` argument overlapping it. Writes
 *   hidden inside a callee's body (a
 *   global it assigns) are EffectsPass's: the operand is IMPURE, a sibling reading that state
 *   is READS, and the emitter spills them.
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it), passes it or a place around it as `mut` or as a `MutView`, calls a `mut
 *   fx` on it or on a place around it (`s.reset()` while iterating `s.items`), or calls a
 *   function that writes it out of sight (`note()` adding to the global `LOG` being
 *   iterated, [HiddenWrites]): iterator invalidation.
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    /** A place an expression touches while it is evaluated: the node that touches it, its text, and the place's own text. */
    private class Touch(val place: Place, val at: ASTNode, val text: String, val placeText: String)

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val hidden = HiddenWrites.of(r, bodies)
        for (b in bodies) {
            Walk(r, b, ViewAliases.of(r, b), hidden).run()
        }
    }

    private class Walk(private val r: Rules, private val b: Body, private val aliases: ViewAliases, private val hidden: HiddenWrites) {
        private val model = r.model

        fun run() {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionCallExpr -> {
                        call(n)
                        val rc = model.calls[n]
                        if (rc != null) {
                            val args = rc.sourceOrder.mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr }
                            // A receiver the call writes is a reference the callee takes after the arguments ran,
                            // in Kira and in C++ alike; a receiver it only reads is a value taken before them.
                            val receiverWritten = r.callOperands(b, n, aliases).any { it.isReceiver && it.writes }
                            val receiver = rc.receiver?.takeIf { !receiverWritten }
                            siblings(n, listOfNotNull(receiver) + args, "'${rc.fn?.name ?: KiraUnparser.text(n.name)}'")
                        }
                    }
                    is BinaryExpr -> if (n.operator != BinaryOp.AND && n.operator != BinaryOp.OR) {
                        siblings(n, listOf(n.leftExpr, n.rightExpr), "the operator")
                    }
                    is CompoundAssignmentExpr -> siblings(n, listOf(n.left, n.right), "the assignment")
                    is PlaceAssignmentExpr -> siblings(n, listOf(n.target, n.value), "the assignment", if (n.operator == null) model.places[n.target] else null)
                    is ObjectInitExpr -> {
                        val cls = model.inits[n]?.cls
                        if (cls != null && cls.kind == ClassKind.CLASS) {
                            siblings(n, n.positionalArgs + n.namedArgs.map { it.value }, "the construction of ${cls.name}")
                        }
                    }
                    is InterpolatedStringLiteral -> siblings(n, n.parts.filterIsInstance<InterpolationPart.Hole>().map { it.expr }, "the string")
                    is ForIterationStatement -> loop(n)
                    else -> {}
                }
            }
        }

        private fun call(e: FunctionCallExpr) {
            val ops = r.callOperands(b, e, aliases)
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
                    if (w.places.none { wp -> o.places.any { it.overlaps(wp) } }) {
                        continue
                    }
                    if (o.writes && j < i) {
                        // Reported once, from the first of two written operands.
                        continue
                    }
                    val fnName = model.calls[e]?.fn?.name ?: "this call"
                    val written = if (w.how == "mut") "`mut ${w.text}`" else "the MutView '${w.text}'"
                    when {
                        w.isReceiver -> r.report(
                            "rules.exclusivity.receiver",
                            "'$fnName' writes its receiver '${w.text}', and the argument '${o.text}' overlaps it (D37): " +
                                "C++ would pass one object by reference twice. Copy the argument first.",
                            o.at,
                        )
                        o.isReceiver -> r.report(
                            "rules.exclusivity.receiver",
                            "$written overlaps the receiver '${o.text}' of '$fnName' (D37): the callee writes what its receiver " +
                                "reads. Copy one of them first.",
                            w.at,
                        )
                        else -> r.report(
                            "rules.exclusivity.argument",
                            "$written overlaps the argument '${o.text}' of '$fnName' (D37): a place written through one argument must " +
                                "not be passed again in the same call. Copy one of them first.",
                            w.at,
                        )
                    }
                }
            }
        }

        /**
         * `rules.exclusivity.order` over the sibling [operands] of [e]: what evaluating one of
         * them writes against what evaluating any other reads or writes. Lambdas inside an
         * operand run later, or never: they are skipped. [assigned] is the place a plain
         * assignment writes after both operands: the target itself and the places around it are
         * no reads of the target operand (only its indices are), and `n = bump(mut n)` is in
         * order.
         */
        private fun siblings(e: Expr, operands: List<Expr>, what: String, assigned: Place? = null) {
            if (operands.size < 2) {
                return
            }
            val writes = operands.map { writesOf(it) }
            if (writes.all { it.isEmpty() }) {
                return
            }
            val reads = operands.mapIndexed { i, op ->
                val all = readsOf(op)
                if (assigned != null && i == 0) all.filter { !(it.place.overlaps(assigned) && it.place.path().size <= assigned.path().size) } else all
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

        /** The places evaluating [e] writes: the written operands of every call inside it, and assignment targets inside it. */
        private fun writesOf(e: Expr): List<Touch> {
            val out = mutableListOf<Touch>()
            fun add(places: List<Place>, at: ASTNode, text: String, placeText: String) {
                places.forEach { out.add(Touch(it, at, text, placeText)) }
            }
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (lambdas.isNotEmpty()) {
                    return@walk
                }
                when (n) {
                    is FunctionCallExpr -> for (op in r.callOperands(b, n, aliases)) {
                        if (op.writes) {
                            val text = when {
                                op.isReceiver -> KiraUnparser.text(n)
                                op.how == "mut" -> "mut ${op.text}"
                                else -> "the MutView '${op.text}'"
                            }
                            op.places.forEach { out.add(Touch(it, n, text, r.describe(it))) }
                        }
                    }
                    is AssignmentExpr -> model.places[n.target]?.let { add(aliases.expand(it), n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { add(aliases.expand(it), n, KiraUnparser.text(n), KiraUnparser.text(n.left)) }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { add(aliases.expand(it), n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
                    else -> {}
                }
            }
            return out
        }

        /** The places evaluating [e] reads: every place expression inside it (a lent view reads what it was lent from; a view local what it stands for). */
        private fun readsOf(e: Expr): List<Touch> {
            val out = mutableListOf<Touch>()
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (lambdas.isNotEmpty() || n !is Expr) {
                    return@walk
                }
                r.placeOf(n)?.let { p -> aliases.expand(p).forEach { out.add(Touch(it, n, KiraUnparser.text(n), KiraUnparser.text(n))) } }
            }
            return out
        }

        private fun loop(s: ForIterationStatement) {
            val plan = model.loops[s] ?: return
            if (plan.kind == LoopKind.RANGE || plan.kind == LoopKind.RANGE_INCLUSIVE) {
                return
            }
            val target = s.forIterationExpr.target
            val iterated = r.placeOf(target)?.let { aliases.expand(it) } ?: return
            val iteratedText = KiraUnparser.text(target)
            fun writes(places: List<Place>, at: ASTNode, how: String): Boolean {
                if (places.any { p -> iterated.any { p.overlaps(it) } }) {
                    r.report(
                        "rules.exclusivity.loop",
                        "This loop iterates '$iteratedText', and its body $how (D37): changing a collection while iterating it " +
                            "invalidates the iteration. Collect the changes and apply them after the loop.",
                        at,
                    )
                    return true
                }
                return false
            }
            AstScan.walk(s.body) { n, lambdas ->
                if (lambdas.isNotEmpty()) {
                    return@walk
                }
                when (n) {
                    is AssignmentExpr -> model.places[n.target]?.let { writes(aliases.expand(it), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { writes(aliases.expand(it), n.left, "assigns '${KiraUnparser.text(n.left)}'") }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { writes(aliases.expand(it), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is FunctionCallExpr -> {
                        val fnName = model.calls[n]?.fn?.name
                        var seen = false
                        for (op in r.callOperands(b, n, aliases)) {
                            if (!op.writes) {
                                continue
                            }
                            seen = if (op.isReceiver) {
                                val around = if (iterated.any { op.place.path().size < it.path().size }) "'${op.text}', which holds it" else "it"
                                writes(op.places, op.at, "calls the `mut fx` '$fnName' on $around")
                            } else if (op.how == "mut") {
                                writes(op.places, op.at, "passes '${op.text}' as mut")
                            } else {
                                writes(op.places, op.at, "passes a MutView of '${op.text}'")
                            } || seen
                        }
                        if (!seen) {
                            // A write the call site does not show: the callee's own.
                            hidden.of(b, n, aliases).firstOrNull { w -> iterated.any { w.overlaps(it) } }?.let { w ->
                                writes(listOf(w), n, "calls '$fnName', which writes '${r.describe(w)}'")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
