package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
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
 * written through another in the same call, and a loop must not change what it iterates;
 * and, under copy by default (50-round4), a `mut` place, which is never copied, is bound only
 * where nothing can move or free it while the call runs (rule M).
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
 *   next()`, `z += inc(mut z)`, `sub(ws[0], poke(ws.from(0)))`); a view operand is ViewPass's;
 *   a reference receiver's handle is copied first too, holding its object for the call
 *   (`k.plus(rebind(mut k))`, round 4). Refused: (2) a `mut` argument a sibling also writes
 *   (`f(mut x, g(mut x))`: a `mut` place is never copied), and two named writes of one place
 *   by two operands; (3) interim, until W2.9.8
 *   lowers Q4: the receiver of a container or a mutable value (a method call's, an implicit
 *   `this`, an operator's left operand, a compound target) beside a named or hidden write of
 *   its place (`gl.get(setFirst())`, `gm.get(putKey())`, `ga.plus(bumpA())`,
 *   `this.plus(bump())`, `plus(bump())`); Q4 reads it when the call runs, which no lowering
 *   gives yet. The index spelling `gl[setFirst()]` already reads `gl` when `kira::at` runs.
 * - `rules.exclusivity.mut`, rule M (50-round4 2.7): a `mut` operand of a call (a `mut`
 *   argument, the value receiver of a `mut fx`) is bound `T&` and never copied (R19), so it
 *   must be storage nothing the call runs can move or free: PRIVATE (a local, a by-value
 *   parameter, through value steps), STABLE (at a fixed offset, value-class fields only, in
 *   an object something holds for the call: `this` of a class, the object behind a PRIVATE
 *   handle slot; or the caller's own `mut` place, a `mut` parameter or a value `mut fx`'s
 *   `this`, which rule M kept still at the caller), or passed to a CONFINED call
 *   (`CallReach.confined`) that has no other `mut` operand that may hold it or lie inside it
 *   (`Rules.mayHold`, both ways). `bump(mut h.item.count, fx() Void { c.reset() })` is
 *   refused: `h.item` is a handle step, and the lambda may free the Item.
 * - Round 4 deleted `rules.exclusivity.alias` and order clause 1: every shape they refused is
 *   an argument or a receiver handle the emitter copies at the call (50-round4 1.3, 1.6).
 * - `rules.exclusivity.loop`: a `for` body assigns the place it iterates (or a place inside
 *   or around it; a view iterated stands for what it was lent from, `half(xs)` for `xs`),
 *   passes it or a place around it as `mut` or as a `MutView`, calls a `mut fx` without a
 *   body on it or on a place around it (`xs.add(x)`, a magic mutator), or calls a function
 *   that writes it out of sight ([HiddenWrites]: `note()` adding to the global `LOG` being
 *   iterated, a `mut fx` with a body writing the iterated field, an override the object may
 *   have, a lambda the callee may run): iterator invalidation. A write through another handle
 *   of the same class counts (`g.items` while `h.items` is iterated: `g` and `h` may be one
 *   object). A `mut fx` with a body writing only another field (`s.reset()` writing `s.a`
 *   while `s.items` is iterated) is no invalidation. A write the rule does not see (a
 *   `finally` a drop runs, a hook reached through a global) is no memory error: the emitter
 *   iterates a copy of a range whose body is IMPURE unless the range is PRIVATE (W6).
 */
internal class ExclusivityPass : RulePass {
    override val name: String = "exclusivity"

    private companion object {
        /** The stdlib containers: the one mutable value (Q3, D44), read by Q4 when a call on them runs. */
        val CONTAINERS = setOf("List", "Arr", "Map", "Set", "Deque", "Stack", "Queue", "StrBuf")
    }

    /**
     * A place an expression touches while it is evaluated: the node that touches it, its text,
     * and the place's own text; [hidden] when a callee writes it out of sight ([HiddenWrites]).
     */
    private class Touch(
        val place: Place,
        val at: ASTNode,
        val text: String,
        val placeText: String,
        val hidden: Boolean = false,
    )

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val hidden = HiddenWrites.of(r, bodies)
        for (b in bodies) {
            Walk(r, b, hidden).run()
        }
    }

    private class Walk(private val r: Rules, private val b: Body, private val hidden: HiddenWrites) {
        private val model = r.model

        fun run() {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionCallExpr -> {
                        call(n)
                        mutOperands(n)
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
         * typed temporary first, so only what it never copies is this rule's (round 4 deleted
         * clause 1, a reference receiver a sibling may rebind: the emitter copies the handle in
         * Kira's order, which holds the object for the call, 50-round4 1.6):
         *
         * - [MUT], clause 2: a `mut` argument. A `mut` place is bound by reference and never
         *   copied (R19), so a sibling's named write of it is refused (`f(mut x, g(mut x))`).
         * - [HELD], clause 3 (interim, until W2.9.8 lowers Q4): the receiver of a container or a
         *   mutable value (a method call's, an implicit `this`, an operator's left operand, a
         *   compound assignment's target). Q4 reads it when the call runs, and a snapshot would
         *   give the answer Q4 rejected, so a sibling's named or hidden write of its place is
         *   refused. An immutable value (a number, `Str`, an immutable class) is read first
         *   (OQ-1, READ FIRST) and is a [VALUE].
         * - [VALUE]: a by-value argument or operand, read first and copied by D33 (`sub(x,
         *   inc(mut x))`, `ticks += next()`, `lenOf(xs, pushTo(mut xs))`), a reference receiver
         *   (its handle copied first: `k.plus(rebind(mut k))`), or a view (ViewPass's).
         */
        private enum class Role { VALUE, MUT, HELD }

        /** A sibling operand: its expression (null for an implicit `this`) and [role]; [held] is an implicit `this`'s touch. */
        private class Operand(val expr: Expr?, val role: Role, val held: List<Touch> = emptyList())

        /**
         * A call's receiver as an operand, or null when it has none the order rule reads: a
         * reference, whose handle is copied first ([Role.VALUE]: its own writes still count as a
         * sibling's); a container or mutable value the call only reads ([Role.HELD]); an implicit `this` of a mutable struct the call only reads; nothing for
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
                t != null && r.isReference(t) -> Operand(recv, Role.VALUE)
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
         * struct, `Maybe`, `Result`, a tuple) is read first (OQ-1); a reference's handle is copied
         * first; a view is ViewPass's.
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
         * rebased onto the receiver), so an argument overlapping the receiver is a by-value
         * argument the emitter copies at the call when the callee may write it
         * (`readSwapped(child)`, which rebinds `this.child`: 50-round4 1.3), not an object passed
         * by reference twice. A receiver of a `mut fx` without a body (a stdlib handle's, a
         * virtual one) keeps the receiver rule: its writes are unknown.
         */
        private fun isAnalysedReferenceReceiver(e: FunctionCallExpr, op: CallOperand): Boolean =
            r.writesObjectOnly(b, e, op) && model.calls[e]?.let { r.hasAnalysedBody(it) } == true

        /**
         * Rule M (50-round4 2.7, `rules.exclusivity.mut`): each `mut` operand `w` of [e] (a `mut`
         * argument whose slot is no second-class type, F2; the value receiver of a `mut fx`) is
         * bound `T&` and never copied, so it is accepted only when nothing [e] runs can move or
         * free its storage: it is PRIVATE ([isPrivate]), STABLE ([stable]) and [e] is no binding
         * that drops what it held mid-operation (`CallReach.dropsMidCall`, KI-20), or [e] is CONFINED
         * (`CallReach.confined`) and no other operand [e] writes (a `mut` argument, the `mut fx`
         * receiver, the source of a `MutView` it is lent) may hold `w` or lie inside it
         * (`Rules.mayHold` both ways) unless both are PRIVATE with different roots. D37's
         * argument and receiver rules have refused a same-root overlap already.
         */
        private fun mutOperands(e: FunctionCallExpr) {
            val rc = model.calls[e] ?: return
            val written = r.callOperands(b, e).filter { it.writes && !r.writesObjectOnly(b, e, it) && !secondClass(rc, e, it) }
            val muts = written.filter { it.how == "mut" || it.isReceiver }
            if (muts.isEmpty()) {
                return
            }
            val recvType = if (rc.implicitThis) (b.owner as? ClassSymbol)?.selfType else rc.receiver?.let { model.types[it] }
            val confined = CallReach.confined(rc, model, recvType)
            // STABLE keeps the storage from being moved or freed by anything but the callee itself; a binding that drops
            // what it held partway through its own C++ can run a finally that re-enters the very container (KI-20).
            val dropsMid = CallReach.dropsMidCall(rc, model, recvType)
            val fnName = rc.fn?.name ?: KiraUnparser.text(e.name)
            for (w in muts) {
                if (isPrivate(w.place)) {
                    continue
                }
                val what = if (w.isReceiver) "the receiver '${w.text}' of the mut fx" else "the mut argument '${w.text}'"
                val storage = storageOf(w.place)
                if (stable(w.place)) {
                    if (dropsMid) {
                        r.report(
                            "rules.exclusivity.mut",
                            "$what, $storage, is passed to '$fnName', which drops what it held while it still works on the container, " +
                                "and that drop may run a finally that reaches the same container and changes it mid-operation " +
                                "(a stdlib container is not reentrant). Write the container whole instead, or work on a local and store it back.",
                            w.at,
                        )
                    }
                    continue
                }
                if (!confined) {
                    r.report(
                        "rules.exclusivity.mut",
                        "$what, $storage, is passed to '$fnName', which may run code that moves or frees it while it writes through the " +
                            "reference (a mut place is never copied, R19). Pass a local and store it back.",
                        w.at,
                    )
                    continue
                }
                val tw = typeOf(e, w)
                val other = written.firstOrNull { o ->
                    o !== w && (r.mayHold(typeOf(e, o), tw) || r.mayHold(tw, typeOf(e, o))) &&
                        !(isPrivate(o.place) && isPrivate(w.place) && o.place.root() != w.place.root())
                } ?: continue
                r.report(
                    "rules.exclusivity.mut",
                    "$what, $storage, is passed to '$fnName' beside '${other.text}', which it also writes and which may hold it or lie " +
                        "inside it: the write through one may move or free the other (a mut place is never copied, R19). Pass locals and " +
                        "store them back.",
                    w.at,
                )
            }
        }

        /** A `mut` argument given to a second-class slot (`mut p: Unsafe<T>` given a view, F2) or a view receiver: the view rules' (W4), never a `mut` place. */
        private fun secondClass(rc: ResolvedCall, e: FunctionCallExpr, op: CallOperand): Boolean {
            if (op.isReceiver) {
                return r.isSecondClass(r.receiverType(b, e, op))
            }
            val i = rc.args.indexOfFirst { (it as? ArgBinding.Given)?.expr === op.at }
            return i >= 0 && r.isSecondClass(rc.fn?.params?.getOrNull(i)?.type?.substitute(rc.substitution))
        }

        private fun typeOf(e: FunctionCallExpr, op: CallOperand): KType? =
            if (op.isReceiver) r.receiverType(b, e, op) else (op.at as? Expr)?.let { model.types[it] }

        /**
         * PRIVATE (50-round4 2.0): a place only this body names, through value steps only
         * ([Rules.isReferenceStep] none): rooted at a local, a by-value parameter, the `this` of
         * a value class in a method that is no `mut fx`, or a value temporary (a receiver-less
         * field root; its own step is on the path too, so `pick(h).items` through a class is not).
         */
        private fun isPrivate(p: Place): Boolean {
            val rootPrivate = when (val root = p.root()) {
                is Place.Local -> true
                is Place.Param -> !root.sym.byRef
                is Place.This -> b.isStructOwner && !b.thisMutable
                is Place.Field -> root.receiver == null
                else -> false
            }
            return rootPrivate && p.path().none { r.isReferenceStep(it) }
        }

        /**
         * STABLE (50-round4 2.7): storage at a fixed offset in an object something holds for the
         * whole call, so a write may overwrite it in place (what `mut` means, R19) but nothing can
         * move or free it. The path starts at a held anchor, and every step after the anchor is
         * a field of a value class (no container element, no `Maybe` or `Result` payload, no
         * view element, no further handle):
         *
         * - `this` of a class, held for its method by its caller (invariant I): `this.state`;
         * - the object behind a PRIVATE handle slot (a class, trait or `Ref` value reached from a
         *   PRIVATE root through value steps; not a `Weak`, which holds nothing): `h.pose` with
         *   `h` a local;
         * - the caller's own `mut` place, a `mut` parameter or the `this` of a value class's
         *   `mut fx`: rule M kept it still at the caller for this whole call (PRIVATE there,
         *   STABLE there, or handed to a CONFINED callee, whose calls are CONFINED too), so the
         *   storage it is bound to moves only through it;
         * - a trait's `this` in a default body: a class object held for the call, or a value
         *   class's `this`, PRIVATE or kept still by rule M at the caller.
         *
         * The proof covers every write but the callee's own drops. A stdlib binding that drops what
         * it held while its C++ still holds pointers into the container (`CallReach.dropsMidCall`:
         * `items.clear()` over elements whose `finally` may be IMPURE) can run a `finally` that adds
         * to or replaces that same container through an alias, so [mutOperands] refuses it at a
         * STABLE place too (KI-20). `List.set` stores first and drops after, and stays STABLE.
         */
        private fun stable(p: Place): Boolean {
            val steps = p.path()
            val root = p.root()
            if (root is Place.This && (b.owner as? ClassSymbol)?.kind == ClassKind.CLASS) {
                return steps.isNotEmpty() && steps.first() is PathStep.FieldStep && steps.drop(1).all { valueField(it) }
            }
            // A `mut` parameter, the `this` of a value class's `mut fx`, and a trait's `this` in a default body (a class
            // object its caller holds, or a value class's `this`, kept still by its caller either way).
            if (root is Place.Param && root.sym.byRef || root is Place.This) {
                return steps.all { valueField(it) }
            }
            val rootPrivate = when (root) {
                is Place.Local -> true
                is Place.Param -> !root.sym.byRef
                is Place.Field -> root.receiver == null
                else -> false
            }
            val k = steps.indexOfFirst { r.isReferenceStep(it) }
            if (!rootPrivate || k < 0) {
                return false
            }
            val anchor = steps[k] as? PathStep.FieldStep ?: return false
            val owner = anchor.sym.owner
            if (owner is ClassSymbol && owner.kind == ClassKind.MAGIC && (owner.name == "Weak" || owner.name == "Unsafe")) {
                return false
            }
            return steps.drop(k + 1).all { valueField(it) }
        }

        /** A field of a value class: storage at a fixed offset inside the object that holds it. */
        private fun valueField(s: PathStep): Boolean = s is PathStep.FieldStep && (s.sym.owner as? ClassSymbol)?.kind == ClassKind.STRUCT

        /** What rule M's message calls a `mut` place's storage. */
        private fun storageOf(p: Place): String {
            val steps = p.path()
            val index = steps.filterIsInstance<PathStep.IndexStep>().firstOrNull()
            return when {
                steps.any { r.isReferenceStep(it) } -> "storage reached through a handle"
                index != null -> "an element of a ${index.kind.name.lowercase().replace('_', ' ')}"
                p.root() is Place.Global -> "a global"
                else -> "storage inside the caller's mut place"
            }
        }

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
                    val handle = model.calls[e]?.receiver?.let { model.types[it] }?.let { r.isReference(it) } == true
                    when {
                        w.isReceiver && handle -> r.report(
                            "rules.exclusivity.receiver",
                            "'$fnName' writes its receiver '${w.text}', and the argument '${o.text}' is that same object (D37): " +
                                "it would hold itself. Pass another one.",
                            o.at,
                        )
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
         * `rules.exclusivity.order` over the sibling [operands] of [e] (F1, 40-round3 3.2): what
         * evaluating one of them writes against what the emitter cannot copy in another ([Role]).
         * Lambdas inside an operand run later, or never: they are skipped. Refused:
         *
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
                touched.firstOrNull { it.place.overlaps(w.place) }?.let { return w to it }
            }
            return null
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
                            out.add(Touch(op.place, n, text, r.describe(op.place)))
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
            fun writes(places: List<Place>, at: ASTNode, how: String): Boolean {
                if (places.any { p -> iterated.any { mayBeSame(p, it) } }) {
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
