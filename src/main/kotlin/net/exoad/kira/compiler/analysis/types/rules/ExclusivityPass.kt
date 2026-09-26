package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.substitute
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
 *   languages, and `rules.exclusivity.receiver` covers a `mut` argument overlapping it. A
 *   receiver of reference type (a class) in a member call reads only the reference: C++17
 *   evaluates it before the arguments and the object after them, as Kira does, so
 *   `c.plus(c.grow())` on a class is in order (on a struct it is not: Kira reads the value
 *   first). But Kira holds that reference until the callee returns and C++ only a raw
 *   pointer, so a sibling that may rebind the variable it was read from, or one around it
 *   (`k.plus(rebind(mut k))`, `k.plus(if c { k = K {}; 1 } else { 2 })`), may destroy the
 *   object the callee runs on: that is the one read the receiver makes. Writes hidden
 *   inside a callee's body (a global it assigns) are EffectsPass's: the operand is IMPURE,
 *   a sibling reading that state is READS, and the emitter spills them.
 * - `rules.exclusivity.alias` (design 5.1): an argument C++ passes by `const&` (a struct, a
 *   `Str`, a container; the receiver of a struct method too) is a place the callee writes
 *   out of sight (`f(GS)` where `f` calls `bumpGS()`): Kira hands the callee a copy, C++ a
 *   reference to what it writes, and the callee would read its own write. A class is shared
 *   by both languages, so only a hidden write that may rebind the reference (of its place or
 *   one around it, `GK = K {}`) counts: as an argument, C++'s `const Rc<K>&` then names the
 *   new object; as the receiver, C++'s `this` points into an object the write may destroy.
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it; a view iterated stands for what it was lent from, `half(xs)` for `xs`),
 *   passes it or a place around it as `mut` or as a `MutView`, calls a `mut fx` without a
 *   body on it or on a place around it (`xs.add(x)`, a magic mutator), or calls a function
 *   that writes it out of sight ([HiddenWrites]: `note()` adding to the global `LOG` being
 *   iterated, a `mut fx` with a body writing the iterated field, an override the object may
 *   have, a lambda the callee may run): iterator invalidation. A `mut fx` with a body writing
 *   only another field (`s.reset()` writing `s.a` while `s.items` is iterated) is no
 *   invalidation.
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    /**
     * A place an expression touches while it is evaluated: the node that touches it, its text,
     * and the place's own text. A [lifetime] touch is a class receiver read as a reference: it
     * conflicts only with a write that may rebind the reference (of the place or one around it),
     * and not with an [objectOnly] write at that place (a class `mut fx`, which writes the
     * object's fields through the reference and never the variable holding it).
     */
    private class Touch(val place: Place, val at: ASTNode, val text: String, val placeText: String, val lifetime: Boolean = false, val objectOnly: Boolean = false)

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
                        aliasing(n)
                        val rc = model.calls[n]
                        if (rc != null) {
                            val args = rc.sourceOrder.mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr }
                            val what = "'${rc.fn?.name ?: KiraUnparser.text(n.name)}'"
                            val reference = rc.receiver?.takeIf { isReferenceReceiver(rc, it) }
                            if (reference != null) {
                                // A member call on a reference (a class): C++17 evaluates the postfix-expression
                                // to the reference before the arguments and the callee reads the object after
                                // them, exactly as Kira does, so neither the receiver's own place nor what its
                                // expression reads on the way is a sibling read. What remains is the object's
                                // lifetime: Kira holds the reference until the callee returns, C++ only a raw
                                // pointer (`k->plus(...)`), so an argument that rebinds the variable the reference
                                // was read from (`rebind(mut k)`, `k = K {}` in an if-expression) may destroy the
                                // object the callee then runs on. A class `mut fx` on that place writes the
                                // object, not the variable, and is in order (`k.plus(k.grow())`).
                                siblings(n, listOf(reference) + args, what, reference = reference)
                            } else {
                                // A receiver the call writes is a reference the callee takes after the arguments
                                // ran, in Kira and in C++ alike; a receiver it only reads is a value taken before
                                // them.
                                val receiverWritten = r.callOperands(b, n, aliases).any { it.isReceiver && it.writes }
                                siblings(n, listOfNotNull(rc.receiver?.takeIf { !receiverWritten }) + args, what)
                            }
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

        /**
         * A receiver C++ takes as `this` from a reference (a class, a trait, a `Ref`) in a member
         * call, which C++17 sequences before the arguments. A magic method binds to a free
         * function over the reference, unsequenced with the arguments like any argument.
         */
        private fun isReferenceReceiver(rc: ResolvedCall, recv: Expr): Boolean =
            (rc.kind == CallKind.METHOD || rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT) && model.types[recv]?.let { r.isReference(it) } == true

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
         * `rules.exclusivity.alias` (design 5.1, D37): an argument C++ passes by `const&` (a
         * struct, a `Str`, a container: [Rules.aliasesCaller]) that is a place the callee
         * writes out of sight ([HiddenWrites]: a global, or `this` through the receiver). Kira
         * hands the callee a copy, which its own write never reaches; C++ hands it a reference
         * to the very storage it writes, and the callee reads the written value. The receiver
         * of a struct method that is not a `mut fx` is a `const S&` as well. A `mut` argument
         * overlapping the receiver or another argument is the `argument`/`receiver` rule's.
         *
         * A class is shared in both languages, so a write to its fields is seen the same way
         * on either side, and only a write that may rebind the reference diverges: of the
         * place itself or one around it (`GK = K {}` while `GK` is the argument, whose
         * `const Rc<K>&` then names the new object where Kira's copy of the reference still
         * names the old; or while `GK` is the receiver, whose `this` is a raw pointer into an
         * object the write may have destroyed).
         */
        private fun aliasing(e: FunctionCallExpr) {
            val rc = model.calls[e] ?: return
            val all = r.callOperands(b, e, aliases)
            val written = all.filter { it.writes }
            // An operand overlapping a written operand of the same call is the argument/receiver rule's. A written
            // reference receiver (a class `mut fx`) stays: its own writes are the object's, and a rebind still kills it.
            val ops = all.mapNotNull { op ->
                val passing = passing(rc, op) ?: return@mapNotNull null
                if (op.writes && passing != Passing.REFERENCE) {
                    return@mapNotNull null
                }
                if (written.any { w -> w !== op && w.places.any { wp -> op.places.any { it.overlaps(wp) } } }) {
                    return@mapNotNull null
                }
                op to passing
            }
            if (ops.isEmpty()) {
                return
            }
            val hiddenWrites = hidden.of(b, e, aliases)
            if (hiddenWrites.isEmpty()) {
                return
            }
            val fnName = rc.fn?.name ?: KiraUnparser.text(e.name)
            for ((op, passing) in ops) {
                val what = if (op.isReceiver) "the receiver" else "the argument"
                if (passing == Passing.REFERENCE) {
                    val w = hiddenWrites.firstOrNull { w -> op.places.any { it.overlaps(w) && w.path().size <= it.path().size } } ?: continue
                    val consequence = if (op.isReceiver) {
                        "Kira keeps the object alive until '$fnName' returns, but C++ runs '$fnName' on a raw pointer into it, which the write may destroy"
                    } else {
                        "Kira hands '$fnName' the reference as it was, but C++ passes a `const Rc&` to the variable itself, and after the write '$fnName' reads the new object"
                    }
                    r.report(
                        "rules.exclusivity.alias",
                        "'$fnName' writes '${r.describe(w)}', and $what '${op.text}' is that reference (design 5.1): $consequence (D37). " +
                            "Copy the reference into a local first.",
                        op.at,
                    )
                } else {
                    val w = hiddenWrites.firstOrNull { w -> op.places.any { it.overlaps(w) } } ?: continue
                    r.report(
                        "rules.exclusivity.alias",
                        "'$fnName' writes '${r.describe(w)}', and $what '${op.text}' is passed to it by reference (design 5.1): Kira hands " +
                            "'$fnName' a copy its write never reaches, but C++ would let it read what it wrote (D37). Copy the value into a local first.",
                        op.at,
                    )
                }
            }
        }

        /** How C++ hands the callee an operand Kira copies: a `const&` to the caller's storage ([CONST_REF]), or a class reference shared by both languages ([REFERENCE]). */
        private enum class Passing { CONST_REF, REFERENCE }

        /** The [Passing] of an operand, or null when C++ copies it too (a scalar, a view) or the argument/receiver rule owns it (a `mut` argument, a struct `mut fx` receiver). */
        private fun passing(rc: ResolvedCall, op: CallOperand): Passing? {
            val t: KType
            if (op.isReceiver) {
                t = (op.at as? Expr)?.let { model.types[it] } ?: return null
                if (op.writes && !r.isReference(t)) {
                    return null
                }
            } else {
                val i = rc.args.indexOfFirst { (it as? ArgBinding.Given)?.expr === op.at }
                if (i < 0) {
                    return null
                }
                val given = rc.args[i] as ArgBinding.Given
                if (given.byRef) {
                    return null
                }
                val param = rc.fn?.params?.getOrNull(i)
                t = param?.type?.substitute(rc.substitution) ?: model.types[given.expr] ?: return null
            }
            if (!r.aliasesCaller(t)) {
                return null
            }
            return if (r.isReference(t)) Passing.REFERENCE else Passing.CONST_REF
        }

        /**
         * `rules.exclusivity.order` over the sibling [operands] of [e]: what evaluating one of
         * them writes against what evaluating any other reads or writes. Lambdas inside an
         * operand run later, or never: they are skipped. [assigned] is the place a plain
         * assignment writes after both operands: the target itself and the places around it are
         * no reads of the target operand (only its indices are), and `n = bump(mut n)` is in
         * order. [reference] is a class receiver of a member call: its only read is the
         * [Touch.lifetime] of the reference it evaluates to.
         */
        private fun siblings(e: Expr, operands: List<Expr>, what: String, assigned: Place? = null, reference: Expr? = null) {
            if (operands.size < 2) {
                return
            }
            val writes = operands.map { writesOf(it) }
            if (writes.all { it.isEmpty() }) {
                return
            }
            val reads = operands.mapIndexed { i, op ->
                val all = if (op === reference) lifetimeOf(op) else readsOf(op)
                if (assigned != null && i == 0) all.filter { !(it.place.overlaps(assigned) && it.place.path().size <= assigned.path().size) } else all
            }
            // Once per pair of operands: a write in one against a read or write in the other, either way round.
            for (i in operands.indices) {
                for (j in i + 1 until operands.size) {
                    val pair = overlap(writes[i], reads[j] + writes[j]) ?: overlap(writes[j], reads[i]) ?: continue
                    val (w, hit) = pair
                    if (hit.lifetime) {
                        r.report(
                            "rules.exclusivity.order",
                            "'${w.text}' writes '${w.placeText}' while one operand of $what is evaluated, and '${hit.text}' is the receiver, a " +
                                "reference read before the arguments (D33, D37): Kira keeps its object alive until $what returns, but C++ holds " +
                                "only a raw pointer, and rebinding the last reference destroys the object $what then runs on. Evaluate the " +
                                "argument into a local first.",
                            hit.at,
                        )
                        continue
                    }
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
                touched.firstOrNull { conflicts(w, it) }?.let { return w to it }
            }
            return null
        }

        /**
         * Whether the write [w] conflicts with the touch [t]. A [Touch.lifetime] read of a
         * reference conflicts only with a write that may rebind it: of the place itself, unless
         * the write is [Touch.objectOnly], or of a place around it (`st = Holder {}` while
         * `st.k` is the receiver). A write below the reference (`k.n = 5`) is the object's
         * contents, which both languages read after the arguments.
         */
        private fun conflicts(w: Touch, t: Touch): Boolean {
            if (!t.place.overlaps(w.place)) {
                return false
            }
            if (!t.lifetime) {
                return true
            }
            val depth = w.place.path().size - t.place.path().size
            return depth < 0 || (depth == 0 && !w.objectOnly)
        }

        /** The one read a reference receiver makes: the reference in its place, as a [Touch.lifetime]; none when it is no place (a call result, kept alive by its own temporary). */
        private fun lifetimeOf(recv: Expr): List<Touch> {
            val p = model.places[recv] ?: return emptyList()
            val text = KiraUnparser.text(recv)
            return aliases.expand(p).map { Touch(it, recv, text, text, lifetime = true) }
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
                            // A class `mut fx` writes the object its receiver refers to, never the variable holding the reference.
                            val objectOnly = op.isReceiver && (op.at as? Expr)?.let { model.types[it] }?.let { r.isReference(it) } == true
                            op.places.forEach { out.add(Touch(it, n, text, r.describe(it), objectOnly = objectOnly)) }
                        }
                    }
                    is AssignmentExpr -> model.places[n.target]?.let { add(aliases.expand(it, forWrite = true), n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { add(aliases.expand(it, forWrite = true), n, KiraUnparser.text(n), KiraUnparser.text(n.left)) }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { add(aliases.expand(it, forWrite = true), n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
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
            // The place iterated, or what a view iterated was lent from (`half(xs)` returning a view of xs).
            val iterated = r.placeOf(target)?.let { aliases.expand(it) } ?: aliases.standsFor(target)
            if (iterated.isEmpty()) {
                return
            }
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
                    is AssignmentExpr -> model.places[n.target]?.let { writes(aliases.expand(it, forWrite = true), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { writes(aliases.expand(it, forWrite = true), n.left, "assigns '${KiraUnparser.text(n.left)}'") }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { writes(aliases.expand(it, forWrite = true), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is FunctionCallExpr -> {
                        val rc = model.calls[n]
                        val fnName = rc?.fn?.name
                        var seen = false
                        for (op in r.callOperands(b, n, aliases)) {
                            if (!op.writes) {
                                continue
                            }
                            seen = if (op.isReceiver) {
                                if (rc != null && r.hasAnalysedBody(rc)) {
                                    // A `mut fx` with a body writes what its body writes (a field beside the
                                    // iterated one is no invalidation): the hidden-write rule below has it.
                                    false
                                } else {
                                    val around = if (iterated.any { op.place.path().size < it.path().size }) "'${op.text}', which holds it" else "it"
                                    writes(op.places, op.at, "calls the `mut fx` '$fnName' on $around")
                                }
                            } else if (op.how == "mut") {
                                writes(op.places, op.at, "passes '${op.text}' as mut")
                            } else {
                                writes(op.places, op.at, "passes a MutView of '${op.text}'")
                            } || seen
                        }
                        if (!seen) {
                            // A write the call site does not show: the callee's own, an override's, or a lambda's it may run.
                            hidden.of(b, n, aliases).firstOrNull { w -> iterated.any { w.overlaps(it) } }?.let { w ->
                                writes(listOf(w), n, "calls '${fnName ?: KiraUnparser.text(n.name)}', which writes '${r.describe(w)}'")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
