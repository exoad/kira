package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.ViewOrigin
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
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
 * view stands for what it was lent from; no view is held in a local (decision 4b), so a view
 * iterated stands for the places ViewPass recorded as its origins (`TypedModel.viewOrigins`).
 *
 * - `rules.exclusivity.argument`: a written argument's place overlaps another argument of the
 *   same call (the same root, and one path a prefix of the other). C++ would pass one object
 *   by `T&` and by `const T&` at once.
 * - `rules.exclusivity.receiver`: a written argument's place overlaps the call's receiver, or
 *   the receiver of a `mut fx` overlaps another argument.
 * - `rules.exclusivity.order` (narrowed in round 3, 40-round3 3.2 and the user's OQ-1): a
 *   write made while one sibling operand is evaluated (by a call nested in it, named or hidden
 *   in its callee, or an assignment in an if-expression in it) that reaches what the emitter
 *   cannot copy in another. Kira evaluates left to right (D33), and the emitter copies every
 *   by-value operand a sibling may change into a typed temporary first, so a by-value
 *   argument, an operator operand or a compound target of an immutable value reading the
 *   written place is in order (`sub(x, inc(mut x))`, `lenOf(xs, pushTo(mut xs))`, `ticks +=
 *   next()`, `z += inc(mut z)`, `sub(ws[0], poke(ws.from(0)))`); a view operand is ViewPass's.
 *   Refused: (1) a receiver of reference type a sibling may rebind (Kira holds the object
 *   until the call returns and C++ only a raw pointer or a `const Rc&` to the variable:
 *   `k.plus(rebind(mut k))`, `sc.k.plus(resetVia(sc))`, `m.unwrap().plus(rebindM(mut m))`; a
 *   class `mut fx` writes the object, not the variable, so `k.plus(k.grow())` is in order);
 *   (2) a `mut` argument a sibling also writes (`f(mut x, g(mut x))`: a `mut` place is never
 *   copied), and two named writes of one place by two operands; (3) interim, until W2.9.8
 *   lowers Q4: the receiver of a container or a mutable value (a method call's, an implicit
 *   `this`, an operator's left operand, a compound target) beside a named or hidden write of
 *   its place (`gl.get(setFirst())`, `gm.get(putKey())`, `ga.plus(bumpA())`,
 *   `this.plus(bump())`, `plus(bump())`); Q4 reads it when the call runs, which no lowering
 *   gives yet. The index spelling `gl[setFirst()]` already reads `gl` when `kira::at` runs.
 * - `rules.exclusivity.alias` (design 5.1): an argument C++ passes by `const&` (a struct, a
 *   `Str`, a container; the receiver of a struct method too) is a place the callee writes
 *   out of sight (`f(GS)` where `f` calls `bumpGS()`): Kira hands the callee a copy, C++ a
 *   reference to what it writes, and the callee would read its own write. A class is shared
 *   by both languages, so only a hidden write that may rebind the reference (of its place or
 *   one around it, `GK = K {}`, or `sc.k` through the parameter `sc` in `readAfter(sc.k,
 *   sc)`) counts: as an argument, C++'s `const Rc<K>&` then names the new object; as the
 *   receiver, C++'s `this` points into an object the write may destroy.
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it; a view iterated stands for what it was lent from, `half(xs)` for `xs`),
 *   passes it or a place around it as `mut` or as a `MutView`, calls a `mut fx` without a
 *   body on it or on a place around it (`xs.add(x)`, a magic mutator), or calls a function
 *   that writes it out of sight ([HiddenWrites]: `note()` adding to the global `LOG` being
 *   iterated, a `mut fx` with a body writing the iterated field, an override the object may
 *   have, a lambda the callee may run): iterator invalidation. A write through another handle
 *   of the same class counts (`g.items` while `h.items` is iterated: `g` and `h` may be one
 *   object), and so does a drop that may run an IMPURE `finally` while a shared place is
 *   iterated ([FinallyRuns]: a `finally` may write anything). A `mut fx` with a body writing
 *   only another field (`s.reset()` writing `s.a` while `s.items` is iterated) is no
 *   invalidation.
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    private companion object {
        /** The stdlib containers: the one mutable value (Q3, D44), read by Q4 when a call on them runs. */
        val CONTAINERS = setOf("List", "Arr", "Map", "Set", "Deque", "Stack", "Queue", "StrBuf")
    }

    /**
     * A place an expression touches while it is evaluated: the node that touches it, its text,
     * and the place's own text. A [lifetime] touch is a class receiver read as a reference: it
     * conflicts only with a write that may rebind the reference (of the place or one around it),
     * and not with an [objectOnly] write at that place (a class `mut fx`, which writes the
     * object's fields through the reference and never the variable holding it). An
     * [objectOnly] write whose callee's body is [analysed] is stood for by that body's writes
     * ([HiddenWrites]), listed as [hidden] touches of their own: `sc.bumpC()` writes `sc.c`,
     * not `sc`.
     */
    private class Touch(
        val place: Place,
        val at: ASTNode,
        val text: String,
        val placeText: String,
        val lifetime: Boolean = false,
        val objectOnly: Boolean = false,
        val analysed: Boolean = false,
        val hidden: Boolean = false,
    )

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val hidden = HiddenWrites.of(r, bodies)
        val finallies = FinallyRuns(r, bodies)
        for (b in bodies) {
            Walk(r, b, hidden, finallies).run()
        }
    }

    /**
     * What may run an IMPURE `finally` (round 2's minor: the loop rule missed the write a
     * `finally` makes when a drop in the body runs it, `for x in GL { GD = null }`). A node may
     * when it drops the last handle of such an object itself (`Effects.dropsHere`), or calls
     * what may: a function whose body does (a fixpoint), a stdlib mutator on a value that may
     * hold one (`xs.clear()`), a construction with an `initially`, or a call that may run any
     * Kira code (R-C, [CallReach.mayRunAnything]). A `finally` may write anything, so the loop
     * rule counts such a node as a write of every shared place. Nothing runs one when no class
     * has an IMPURE `finally` ([any]).
     */
    private class FinallyRuns(private val r: Rules, bodies: List<Body>) {
        private val model = r.model
        private val effects = EffectsPass.of(r)
        val any: Boolean = effects.anyImpureFinally
        private val fnRuns: MutableSet<FnSymbol> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        private val constructing: MutableSet<ClassSymbol> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

        init {
            if (any) {
                val fnBodies = bodies.filter { it.fn != null }
                var changed = true
                while (changed) {
                    changed = false
                    for (b in fnBodies) {
                        val fn = b.fn!!
                        if (fn in fnRuns) {
                            continue
                        }
                        if (fn.params.any { !it.byRef && effects.mayDrop(it.type) } || first(b, b.roots) != null) {
                            fnRuns.add(fn)
                            changed = true
                        }
                    }
                }
            }
        }

        /** The first node of [roots] (lambda bodies aside, which run only when called) that may run an IMPURE `finally`. */
        fun first(b: Body, roots: List<ASTNode>): ASTNode? {
            if (!any) {
                return null
            }
            var found: ASTNode? = null
            AstScan.walk(roots) { n, lambdas ->
                if (found == null && lambdas.isEmpty() && node(b, n)) {
                    found = n
                }
            }
            return found
        }

        private fun node(b: Body, n: ASTNode): Boolean {
            if (effects.dropsHere(n)) {
                return true
            }
            val e = n as? Expr ?: return false
            model.opCalls[e]?.let { if (callRuns(b, it)) return true }
            return when (e) {
                is FunctionCallExpr -> model.calls[e]?.let { callRuns(b, it) } ?: true
                is ObjectInitExpr -> construction(b, model.inits[e]?.cls)
                else -> false
            }
        }

        private fun callRuns(b: Body, rc: ResolvedCall): Boolean {
            val recvType = if (rc.implicitThis) (b.owner as? ClassSymbol)?.selfType else rc.receiver?.let { model.types[it] }
            if (CallReach.mayRunAnything(rc, model, recvType)) {
                return true
            }
            if (rc.kind == CallKind.CTOR) {
                return construction(b, (rc.returnType as? KType.Nominal)?.sym as? ClassSymbol)
            }
            val fn = rc.fn ?: return false
            if (fn.body != null) {
                return fn in fnRuns || (rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT)
            }
            if (fn.foreign is Foreign.Magic && !r.bindings.isPure(fn)) {
                // A mutator may drop what it replaces or removes (`xs.clear()`, `xs.set(0, v)`, `m.remove(k)`).
                return recvType != null && effects.mayDrop(recvType) ||
                    rc.args.any { a -> (a as? ArgBinding.Given)?.let { effects.mayDrop(model.types[it.expr]) } == true }
            }
            return false
        }

        /** A construction of [cls] runs its and its superclasses' `initially` and the field defaults it leaves out. */
        private fun construction(b: Body, cls: ClassSymbol?): Boolean {
            if (cls == null || !constructing.add(cls)) {
                return false
            }
            try {
                var c: ClassSymbol? = cls
                val seen = HashSet<ClassSymbol>()
                while (c != null && seen.add(c)) {
                    if (c.initially != null) {
                        return true
                    }
                    if (c.fields.any { f -> f.default?.let { first(b, listOf(it)) } != null }) {
                        return true
                    }
                    c = c.superclass?.sym as? ClassSymbol
                }
                return false
            } finally {
                constructing.remove(cls)
            }
        }
    }

    private class Walk(private val r: Rules, private val b: Body, private val hidden: HiddenWrites, private val finallies: FinallyRuns) {
        private val model = r.model

        fun run() {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionCallExpr -> {
                        call(n)
                        aliasing(n)
                        val rc = model.calls[n]
                        if (rc != null) {
                            val what = "'${rc.fn?.name ?: KiraUnparser.text(n.name)}'"
                            val args = rc.sourceOrder.mapNotNull { i ->
                                val given = rc.args[i] as? ArgBinding.Given ?: return@mapNotNull null
                                // A `mut` place is bound by reference and never copied (R19): clause 2. A second-class
                                // argument (a `mut Unsafe<T>` given a view) moves nothing (F2), and a view is ViewPass's.
                                val slot = rc.fn?.params?.getOrNull(i)?.type?.substitute(rc.substitution)
                                Operand(given.expr, if (given.byRef && !r.isSecondClass(slot)) Role.MUT else Role.VALUE)
                            }
                            siblings(n, listOfNotNull(receiver(n, rc)) + args, what)
                        }
                    }
                    // `a + b` is `a.@_op_add_(b)` (DECISIONS 2): the left operand is its receiver.
                    is BinaryExpr -> if (n.operator != BinaryOp.AND && n.operator != BinaryOp.OR) {
                        siblings(n, listOf(held(n.leftExpr), Operand(n.rightExpr, Role.VALUE)), "the operator")
                    }
                    // `a += b` is `a = a.@_op_add_(b)`: the target is the receiver of the operator its desugaring calls.
                    is CompoundAssignmentExpr -> siblings(n, listOf(held(n.left), Operand(n.right, Role.VALUE)), "the assignment")
                    // A plain assignment's target is only located, which the emitter binds in order after the value.
                    is PlaceAssignmentExpr -> siblings(
                        n,
                        listOf(if (n.operator != null) held(n.target) else Operand(n.target, Role.VALUE), Operand(n.value, Role.VALUE)),
                        "the assignment",
                    )
                    is ObjectInitExpr -> {
                        val cls = model.inits[n]?.cls
                        if (cls != null && cls.kind == ClassKind.CLASS) {
                            siblings(n, (n.positionalArgs + n.namedArgs.map { it.value }).map { Operand(it, Role.VALUE) }, "the construction of ${cls.name}")
                        }
                    }
                    is InterpolatedStringLiteral -> siblings(n, n.parts.filterIsInstance<InterpolationPart.Hole>().map { Operand(it.expr, Role.VALUE) }, "the string")
                    is ForIterationStatement -> loop(n)
                    else -> {}
                }
            }
        }

        /**
         * What a sibling's write may not reach (F1, 40-round3 3.2). Kira evaluates operands left
         * to right (D33); the emitter copies every by-value operand a sibling may change into a
         * typed temporary first, so only what it never copies is this rule's:
         *
         * - [LIFETIME], clause 1: a receiver of reference type (a class, a trait, a `Ref`, a
         *   handle). Kira holds the object until the call returns, C++ only a raw pointer or a
         *   `const Rc&` to the variable, so a sibling that may rebind the variable, or a place
         *   around it, is refused ([lifetimeOf]).
         * - [MUT], clause 2: a `mut` argument. A `mut` place is bound by reference and never
         *   copied (R19), so a sibling's named write of it is refused (`f(mut x, g(mut x))`).
         * - [HELD], clause 3 (interim, until W2.9.8 lowers Q4): the receiver of a container or a
         *   mutable value (a method call's, an implicit `this`, an operator's left operand, a
         *   compound assignment's target). Q4 reads it when the call runs, and a snapshot would
         *   give the answer Q4 rejected, so a sibling's named or hidden write of its place is
         *   refused. An immutable value (a number, `Str`, an immutable class) is read first
         *   (OQ-1, READ FIRST) and is a [VALUE].
         * - [VALUE]: a by-value argument or operand, read first and copied by D33 (`sub(x,
         *   inc(mut x))`, `ticks += next()`, `lenOf(xs, pushTo(mut xs))`), or a view (ViewPass's).
         */
        private enum class Role { VALUE, MUT, HELD, LIFETIME }

        /** A sibling operand: its expression (null for an implicit `this`) and [role]; [held] is an implicit `this`'s touch. */
        private class Operand(val expr: Expr?, val role: Role, val held: List<Touch> = emptyList())

        /**
         * A call's receiver as an operand, or null when it has none the order rule reads: a
         * reference ([Role.LIFETIME]); a container or mutable value the call only reads
         * ([Role.HELD]); an implicit `this` of a mutable struct the call only reads; nothing for
         * a value receiver the call writes (a `mut fx` binds it by reference after the arguments
         * ran, in Kira and in C++ alike; `rules.exclusivity.receiver` covers an argument
         * overlapping it) or an immutable one (read first).
         */
        private fun receiver(n: FunctionCallExpr, rc: ResolvedCall): Operand? {
            val receiverWritten = r.callOperands(b, n).any { it.isReceiver && it.writes }
            if (rc.implicitThis) {
                val owner = b.owner as? ClassSymbol ?: return null
                if (receiverWritten || r.isReference(owner.selfType) || !readAtCall(owner.selfType)) {
                    return null
                }
                val at = (n.name as? MemberAccessExpr)?.member ?: n.name
                return Operand(null, Role.HELD, listOf(Touch(Place.This(owner), at, "this", "this")))
            }
            val recv = rc.receiver ?: return null
            val t = model.types[recv]
            return when {
                t != null && r.isReference(t) -> Operand(recv, Role.LIFETIME)
                receiverWritten -> null
                else -> held(recv)
            }
        }

        /** An operand Q4 reads when its call runs ([Role.HELD]) when it is a container or mutable value, else a [Role.VALUE]. */
        private fun held(e: Expr): Operand = Operand(e, if (readAtCall(model.types[e])) Role.HELD else Role.VALUE)

        /**
         * Whether Q4 reads a receiver of type [t] when its call runs, which no lowering gives yet:
         * a container (`List`, `Arr`, `Map`, `Set`, `Deque`, `Stack`, `Queue`, `StrBuf`) or a
         * mutable value (a struct with a `mut` field or a `mut fx`); a type parameter or an
         * unknown type may be one. An immutable value (a scalar, `Str`, an enum, an immutable
         * struct, `Maybe`, `Result`, a tuple) is read first (OQ-1); a reference is [Role.LIFETIME]'s;
         * a view is ViewPass's.
         */
        private fun readAtCall(t: KType?): Boolean = when (t) {
            null, KType.Error, is KType.Param -> true
            is KType.Nominal -> when (val sym = t.sym) {
                is ClassSymbol -> when (sym.kind) {
                    ClassKind.STRUCT -> sym.fields.any { it.isMut } || sym.methods.any { it.isMutMethod }
                    ClassKind.MAGIC -> sym.name in CONTAINERS
                    else -> false
                }
                else -> false
            }
            else -> false
        }

        /** A place touch of [e] for a [Role.MUT] or [Role.HELD] operand: where it is (R-A's `readPlace`, or the place a lent view aliases). */
        private fun placeTouch(e: Expr): List<Touch> =
            r.placeOf(e)?.let { listOf(Touch(it, e, KiraUnparser.text(e), KiraUnparser.text(e))) }.orEmpty()

        /**
         * The receiver of a class `mut fx` whose body this pass analysed: the object is shared
         * by both languages, and the body writes what [HiddenWrites] lists (`this.child`,
         * rebased onto the receiver), so an argument overlapping the receiver is the alias
         * rule's (`readSwapped(child)`, which rebinds `this.child`), not an object passed by
         * reference twice. A receiver of a `mut fx` without a body (a stdlib handle's, a
         * virtual one) keeps the receiver rule: its writes are unknown.
         */
        private fun isAnalysedReferenceReceiver(e: FunctionCallExpr, op: CallOperand): Boolean =
            r.writesObjectOnly(b, e, op) && model.calls[e]?.let { r.hasAnalysedBody(it) } == true

        private fun call(e: FunctionCallExpr) {
            val ops = r.callOperands(b, e)
            for (i in ops.indices) {
                val w = ops[i]
                if (!w.writes || isAnalysedReferenceReceiver(e, w)) {
                    continue
                }
                for (j in ops.indices) {
                    if (i == j) {
                        continue
                    }
                    val o = ops[j]
                    if (!o.place.overlaps(w.place)) {
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
            val all = r.callOperands(b, e)
            // An operand overlapping a written operand of the same call is the argument/receiver rule's, unless the
            // written one is the receiver of a class `mut fx` with a body, whose writes are listed as hidden writes.
            val written = all.filter { it.writes && !isAnalysedReferenceReceiver(e, it) }
            // A written reference receiver (a class `mut fx`) stays: its own writes are the object's, and a rebind still kills it.
            val ops = all.mapNotNull { op ->
                val passing = passing(rc, op) ?: return@mapNotNull null
                if (op.writes && passing != Passing.REFERENCE) {
                    return@mapNotNull null
                }
                if (written.any { w -> w !== op && op.place.overlaps(w.place) }) {
                    return@mapNotNull null
                }
                op to passing
            }
            if (ops.isEmpty()) {
                return
            }
            val hiddenWrites = hidden.of(b, e)
            if (hiddenWrites.isEmpty()) {
                return
            }
            val fnName = rc.fn?.name ?: KiraUnparser.text(e.name)
            for ((op, passing) in ops) {
                val what = if (op.isReceiver) "the receiver" else "the argument"
                if (passing == Passing.REFERENCE) {
                    val w = hiddenWrites.firstOrNull { w -> op.place.overlaps(w) && w.path().size <= op.place.path().size } ?: continue
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
                    val w = hiddenWrites.firstOrNull { w -> op.place.overlaps(w) } ?: continue
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
         * `rules.exclusivity.order` over the sibling [operands] of [e] (F1, 40-round3 3.2): what
         * evaluating one of them writes against what the emitter cannot copy in another ([Role]).
         * Lambdas inside an operand run later, or never: they are skipped. Refused:
         *
         * - a named or hidden write ([HiddenWrites]: a global a callee assigns, a field of `this`
         *   or of a class parameter, what a lambda it runs writes) that may rebind a
         *   [Role.LIFETIME] receiver (`k.plus(rebind(mut k))`, `sc.k.plus(resetVia(sc))`);
         * - a named write of a [Role.MUT] argument's place (`f(mut x, g(mut x))`);
         * - a named or hidden write of a [Role.HELD] receiver's place (`gl.get(setFirst())`,
         *   `ga.plus(bumpA())`, `this.plus(bump())`, `plus(bump())` on a mutable struct), until
         *   W2.9.8 lowers Q4;
         * - two named writes of one place by two operands (`pair(b.grow(), b.grow())` on a struct).
         *
         * Every other read of a written place is a by-value read D33 copies first, and is allowed.
         */
        private fun siblings(e: Expr, operands: List<Operand>, what: String) {
            if (operands.size < 2) {
                return
            }
            val writes = operands.map { op -> op.expr?.let { writesOf(it) }.orEmpty() }
            val pins = operands.map { op ->
                when (op.role) {
                    Role.VALUE -> emptyList()
                    Role.MUT -> placeTouch(op.expr!!)
                    Role.HELD -> op.held.ifEmpty { op.expr?.let { placeTouch(it) }.orEmpty() }
                    Role.LIFETIME -> lifetimeOf(op.expr!!)
                }
            }
            val hidden = operands.mapIndexed { i, op ->
                if (pins.withIndex().any { (j, p) -> j != i && p.isNotEmpty() && operands[j].role != Role.MUT }) op.expr?.let { hiddenWritesOf(it) }.orEmpty() else emptyList()
            }
            if (writes.all { it.isEmpty() } && hidden.all { it.isEmpty() }) {
                return
            }
            fun pinnedAgainst(w: List<Touch>, hid: List<Touch>, k: Int): Pair<Touch, Touch>? {
                val p = pins[k]
                if (p.isEmpty()) {
                    return null
                }
                return overlap(w, p) ?: if (operands[k].role != Role.MUT) overlap(hid, p) else null
            }
            // Once per pair of operands: a write in one against the other's pinned place, either way round, then
            // a named write in both.
            for (i in operands.indices) {
                for (j in i + 1 until operands.size) {
                    val (w, hit, k) = pinnedAgainst(writes[i], hidden[i], j)?.let { Triple(it.first, it.second, j) }
                        ?: pinnedAgainst(writes[j], hidden[j], i)?.let { Triple(it.first, it.second, i) }
                        ?: overlap(writes[i], writes[j])?.let { Triple(it.first, it.second, -1) }
                        ?: continue
                    val where = if (w.hidden) " inside its callee" else ""
                    val message = when (if (k < 0) null else operands[k].role) {
                        Role.LIFETIME -> "'${w.text}' writes '${w.placeText}'$where while one operand of $what is evaluated, and '${hit.text}' is the receiver, a " +
                            "reference read before the arguments (D33, D37): Kira keeps its object alive until $what returns, but C++ holds " +
                            "only a raw pointer, and rebinding the last reference destroys the object $what then runs on. Evaluate the " +
                            "argument into a local first."
                        Role.MUT -> "'${w.text}' writes '${w.placeText}' while one operand of $what is evaluated, and '${hit.text}' is passed to it as mut " +
                            "(D33, D37): a mut place is bound by reference and never copied (R19), so the two writes have no order both " +
                            "languages keep. Evaluate the other operand into a local first."
                        Role.HELD -> "'${w.text}' writes '${w.placeText}'$where while one operand of $what is evaluated, and '${hit.text}' is " +
                            "${if (e is FunctionCallExpr) "the receiver" else "the receiver of the operator"}, a container or a mutable value, " +
                            "which Kira reads when $what runs (Q4); that order is not lowered yet (W2.9.8), and a copy taken first would read " +
                            "it before the write. Evaluate the other operand into a local first."
                        else -> "'${w.text}' writes '${w.placeText}' while one operand of $what is evaluated, and '${hit.text}' writes it again " +
                            "(D33, D37): a place written by two operands of one call. Evaluate one of them into a local first."
                    }
                    r.report("rules.exclusivity.order", message, hit.at)
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
         * contents, which both languages read after the arguments. An [Touch.objectOnly] write
         * whose body is [Touch.analysed] never conflicts by itself: the body's own writes are
         * hidden touches, and they decide (`sc.bumpC()` writes `sc.c`, which leaves `sc.k`
         * alone; `sc.resetK2()` writes `sc.k`).
         */
        private fun conflicts(w: Touch, t: Touch): Boolean {
            if (!t.place.overlaps(w.place)) {
                return false
            }
            if (!t.lifetime) {
                return true
            }
            val depth = w.place.path().size - t.place.path().size
            return when {
                w.objectOnly && w.analysed -> false
                w.objectOnly -> depth < 0
                else -> depth <= 0
            }
        }

        /**
         * The one read a reference receiver makes: the reference in its place, as a
         * [Touch.lifetime]. A call result is kept alive by its own temporary when the callee
         * returns the reference by value (a Kira function returns `kira::Rc<C>`), but a magic
         * method may return a reference into its receiver (`m.unwrap()` is
         * `kira::unwrap(const std::shared_ptr<U>&)`, which returns that `const&`; `ks.get(0)`
         * is `kira::at`, a reference into the list), so its lifetime is its receiver's, through
         * any chain of them. A field read off such a reference (`ks.get(0).child`) has no place
         * of its own (the emitter writes `kira::at(ks,0)->child`, a raw pointer indirection with
         * no lifetime the C++ type system tracks), so it lives exactly as long as the reference
         * it is read through: the walk continues into the field access's own origin the same
         * way. None when there is no place underneath.
         */
        private fun lifetimeOf(recv: Expr): List<Touch> {
            val text = KiraUnparser.text(recv)
            fun placesOf(e: Expr): List<Place> {
                // Phase C records a field access as Place.Field(receiver, sym) even when the receiver has
                // no place of its own (a call result), with a null receiver precisely to say so (Place.kt):
                // that Field aliases nothing, by identity, so it is no better than having no place at all
                // here and must not stop this walk short.
                r.placeOf(e)?.takeUnless { it is Place.Field && it.receiver == null }?.let { return listOf(it) }
                if (e is FunctionCallExpr) {
                    val rc = model.calls[e] ?: return emptyList()
                    if (rc.kind == CallKind.MAGIC && rc.receiver != null) {
                        return placesOf(rc.receiver)
                    }
                }
                if (e is MemberAccessExpr) {
                    return placesOf(e.origin)
                }
                return emptyList()
            }
            return placesOf(recv).map { Touch(it, recv, text, r.describe(it), lifetime = true) }
        }

        /** The places the calls inside [e] (its lambdas aside) write out of sight ([HiddenWrites]), each as a [Touch.hidden] of the call. */
        private fun hiddenWritesOf(e: Expr): List<Touch> {
            val out = mutableListOf<Touch>()
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (lambdas.isNotEmpty() || n !is FunctionCallExpr) {
                    return@walk
                }
                val text = KiraUnparser.text(n)
                hidden.of(b, n).forEach { out.add(Touch(it, n, text, r.describe(it), hidden = true)) }
            }
            return out
        }

        /** The places evaluating [e] writes: the written operands of every call inside it, and assignment targets inside it. */
        private fun writesOf(e: Expr): List<Touch> {
            val out = mutableListOf<Touch>()
            fun add(place: Place, at: ASTNode, text: String, placeText: String) {
                out.add(Touch(place, at, text, placeText))
            }
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (lambdas.isNotEmpty()) {
                    return@walk
                }
                when (n) {
                    is FunctionCallExpr -> for (op in r.callOperands(b, n)) {
                        if (op.writes) {
                            val text = when {
                                op.isReceiver -> KiraUnparser.text(n)
                                op.how == "mut" -> "mut ${op.text}"
                                else -> "the MutView '${op.text}'"
                            }
                            // A class `mut fx` writes the object its receiver refers to, never the variable holding
                            // the reference; with a body, it writes what the body writes (its hidden writes).
                            val objectOnly = r.writesObjectOnly(b, n, op)
                            val analysed = objectOnly && model.calls[n]?.let { r.hasAnalysedBody(it) } == true
                            out.add(Touch(op.place, n, text, r.describe(op.place), objectOnly = objectOnly, analysed = analysed))
                        }
                    }
                    is AssignmentExpr -> model.places[n.target]?.let { add(it, n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { add(it, n, KiraUnparser.text(n), KiraUnparser.text(n.left)) }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { add(it, n, KiraUnparser.text(n), KiraUnparser.text(n.target)) }
                    else -> {}
                }
            }
            return out
        }

        private fun loop(s: ForIterationStatement) {
            val plan = model.loops[s] ?: return
            if (plan.kind == LoopKind.RANGE || plan.kind == LoopKind.RANGE_INCLUSIVE) {
                return
            }
            val target = s.forIterationExpr.target
            // The place iterated, or the places ViewPass says a view iterated points into.
            val iterated = r.placeOf(target)?.let { listOf(it) }
                ?: model.viewOrigins(target).mapNotNull { (it as? ViewOrigin.Stored)?.place }
            if (iterated.isEmpty()) {
                return
            }
            val iteratedText = KiraUnparser.text(target)
            var reported = false
            fun writes(places: List<Place>, at: ASTNode, how: String): Boolean {
                if (places.any { p -> iterated.any { mayBeSame(p, it) } }) {
                    reported = true
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
                    is AssignmentExpr -> model.places[n.target]?.let { writes(listOf(it), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is CompoundAssignmentExpr -> model.places[n.left]?.let { writes(listOf(it), n.left, "assigns '${KiraUnparser.text(n.left)}'") }
                    is PlaceAssignmentExpr -> model.places[n.target]?.let { writes(listOf(it), n.target, "assigns '${KiraUnparser.text(n.target)}'") }
                    is FunctionCallExpr -> {
                        val rc = model.calls[n]
                        val fnName = rc?.fn?.name
                        var seen = false
                        for (op in r.callOperands(b, n)) {
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
                                    writes(listOf(op.place), op.at, "calls the `mut fx` '$fnName' on $around")
                                }
                            } else if (op.how == "mut") {
                                writes(listOf(op.place), op.at, "passes '${op.text}' as mut")
                            } else {
                                writes(listOf(op.place), op.at, "passes a MutView of '${op.text}'")
                            } || seen
                        }
                        if (!seen) {
                            // A write the call site does not show: the callee's own, an override's, or a lambda's it may run.
                            hidden.of(b, n).firstOrNull { w -> iterated.any { mayBeSame(w, it) } }?.let { w ->
                                writes(listOf(w), n, "calls '${fnName ?: KiraUnparser.text(n.name)}', which writes '${r.describe(w)}'")
                            }
                        }
                    }
                    else -> {}
                }
            }
            // A `finally` a drop in the body runs may write anything a reference reaches: every shared place. A
            // temporary's field (`makeItem().labels`) is none: the emitter iterates a copy made while the temporary lives.
            if (!reported && iterated.any { r.isSharedPlace(it) && !temporary(it) }) {
                finallies.first(b, s.body)?.let { n ->
                    r.report(
                        "rules.exclusivity.loop",
                        "This loop iterates '$iteratedText', and its body runs '${KiraUnparser.text(n)}', which may drop the last handle of an object whose " +
                            "`finally` is impure (D37): a `finally` may write anything a reference reaches, '$iteratedText' included, and changing a " +
                            "collection while iterating it invalidates the iteration. Collect the changes and apply them after the loop.",
                        n,
                    )
                }
            }
        }

        /** A place rooted at a field of no place (`makeItem().labels`): part of a temporary, which nothing else names. */
        private fun temporary(p: Place): Boolean = (p.root() as? Place.Field)?.receiver == null && p.root() is Place.Field

        /**
         * Two places may be one storage: they overlap, or they are reached through the same
         * field of a reference type (`g.items` and `h.items` with `g` and `h` two handles of one
         * class, `this.items` and `other.items`), whose objects two handles may share (round 2's
         * q6: `for xs in h.items { g.reset() }` with `g` aliasing `h`).
         */
        private fun mayBeSame(a: Place, b: Place): Boolean {
            if (a.overlaps(b)) {
                return true
            }
            // A temporary's field is iterated through a copy the emitter makes while the temporary lives (W2.3's statement part).
            if (temporary(a) || temporary(b)) {
                return false
            }
            val pa = a.path()
            val pb = b.path()
            for (i in pa.indices) {
                val s = pa[i] as? PathStep.FieldStep ?: continue
                if (!r.ownerIsReference(s.sym)) {
                    continue
                }
                for (j in pb.indices) {
                    if ((pb[j] as? PathStep.FieldStep)?.sym !== s.sym) {
                        continue
                    }
                    val ra = pa.drop(i)
                    val rb = pb.drop(j)
                    if ((0 until minOf(ra.size, rb.size)).all { ra[it].matches(rb[it]) }) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
