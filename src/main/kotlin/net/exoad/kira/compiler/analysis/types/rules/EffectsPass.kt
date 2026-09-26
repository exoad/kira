package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import java.util.IdentityHashMap

/**
 * Purity, bottom-up over the call graph (design 3.4, D33). It reports nothing; it fills
 * `TypedModel.fnEffects` for every function with a body (and every stdlib binding marked
 * `pure: true`) and `TypedModel.effects` for every expression of every body.
 *
 * The answer is three-valued ([Effect], ordered PURE < READS < IMPURE), because D33 asks two
 * things of an operand: does evaluating it change anything a sibling could see, and does a
 * sibling's change reach it? Kira evaluates call arguments and the operands of an operator
 * left to right (C and JS do); C++ leaves them unsequenced, so the C++ emitter spills the
 * operands of a call or an operator into typed temporaries, in source order, when one of
 * them is IMPURE and another is not PURE (R19). An expression is
 *
 * - IMPURE when it has an effect: it writes anything but the writer's own locals (a `mut`
 *   parameter, the receiver of a `mut fx`, a global, a field through a class reference, a
 *   `Ref`, a `MutView`), prints, calls an extern, a virtual or trait-dispatched method or an
 *   `Fx` value, calls a function that does any of these, calls a magic binding without
 *   `pure: true`, or may throw (a `throw` is caught by a `try` around the call, so which
 *   operand ran first decides what a sibling's print or write left behind);
 * - READS when it has no effect but reads shared state ([Rules.isSharedPlace]): a `mut`
 *   global, a `mut` parameter, a parameter C++ passes by `const&` (a struct, a `Str`, a
 *   container: design 5.1, so the callee reads the caller's storage, which a sibling's
 *   effect could write), a field through `this` (a class's shared object; a struct's `const
 *   S&`), a view (its elements, or the view itself, which is iterated or indexed next), or
 *   the contents of a view or reference receiver (`v.get(0)`, `c.peek()`), which a sibling's
 *   effect could change before the read. Two READS siblings need no ordering, so `p[0] |
 *   p[1] << 8` on a View parameter is emitted bare;
 * - PURE otherwise: reads of locals, scalar parameters, constants and what they hold by
 *   copy. Only a `mut` argument, the receiver of a `mut fx` or a `MutView` lent from such a
 *   place, nested in a sibling, could change those, and ExclusivityPass refuses that pair
 *   (`rules.exclusivity.order`).
 *
 * A stdlib binding marked `pure: true` keeps its promise (no effect, a result from its
 * receiver and arguments); the receiver's own effect is the receiver expression's, so
 * `XS.size()` on a `mut` global is READS while `xs.size()` on a local is PURE. A checked
 * binding's panic is not a throw a `try` catches (D10), so it stays pure as the manifests
 * say.
 *
 * A function's effect is the join of its body's (its lambdas aside, which run only when
 * called). The computation starts from "every function is PURE" and raises functions until
 * nothing changes, so mutually recursive functions that do nothing impure stay pure. "Pure"
 * never licenses dropping a call: the emitter spills or emits it in place, and only that.
 */
internal class EffectsPass : RulePass {
    override val name: String = "effects"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fnBodies = bodies.filter { it.fn != null }
        val fns = IdentityHashMap<FnSymbol, Effect>()
        fnBodies.forEach { fns[it.fn!!] = Effect.PURE }
        var changed = true
        while (changed) {
            changed = false
            for (b in fnBodies) {
                val fn = b.fn!!
                val was = fns[fn]!!
                if (was == Effect.IMPURE) {
                    continue
                }
                val now = bodyEffect(r, b, fns)
                if (now > was) {
                    fns[fn] = now
                    changed = true
                }
            }
        }
        val model = r.model
        model.fnEffects.putAll(fns)
        // A stdlib binding marked pure is pure to the emitter too, so it can ask one table.
        for (m in program.modules) {
            if (!m.isStdlib) {
                continue
            }
            for (s in m.declarations) {
                val declared = when (s) {
                    is FnSymbol -> listOf(s)
                    is ClassSymbol -> s.methods
                    is TraitSymbol -> s.methods
                    else -> emptyList()
                }
                for (fn in declared) {
                    if (fn.foreign is Foreign.Magic && fn.body == null && r.bindings.isPure(fn)) {
                        model.fnEffects[fn] = Effect.PURE
                    }
                }
            }
        }
        val exprs = ExprEffects(r, fns)
        for (b in bodies) {
            b.roots.forEach { root -> AstTree.walk(root) { n -> if (n is Expr && n !is Type) exprs.of(n) } }
        }
    }

    /** The join of every node of [b] (outside its lambdas, which run only when called) under the current [fns]. */
    private fun bodyEffect(r: Rules, b: Body, fns: Map<FnSymbol, Effect>): Effect {
        var e = Effect.PURE
        AstScan.walk(b.roots) { n, lambdas ->
            if (e == Effect.IMPURE || lambdas.isNotEmpty()) {
                return@walk
            }
            e = maxOf(e, nodeEffect(r, n, fns))
        }
        return e
    }

    companion object {
        /** The effect of [n] itself (its children aside). */
        fun nodeEffect(r: Rules, n: ASTNode, fns: Map<FnSymbol, Effect>): Effect {
            val model = r.model
            val e = n as? Expr ?: return Effect.PURE
            var out = Effect.PURE
            model.opCalls[e]?.let { out = maxOf(out, callEffect(r, it, fns)) }
            if (sharedRead(r, e)) {
                out = maxOf(out, Effect.READS)
            }
            val own = when (n) {
                is FunctionCallExpr -> callEffect(r, model.calls[n], fns)
                is IntrinsicExpr -> if (n.intrinsicKey.name != "_static_assert") Effect.IMPURE else Effect.PURE
                is ThrowExpr -> Effect.IMPURE
                is AssignmentExpr -> if (localWrite(r, model.places[n.target])) Effect.PURE else Effect.IMPURE
                is CompoundAssignmentExpr -> if (localWrite(r, model.places[n.left])) Effect.PURE else Effect.IMPURE
                is PlaceAssignmentExpr -> if (localWrite(r, model.places[n.target])) Effect.PURE else Effect.IMPURE
                is ObjectInitExpr -> {
                    val ri = model.inits[n] ?: return Effect.IMPURE
                    val cls = ri.cls
                    if (cls != null && cls.kind == ClassKind.CLASS && cls.initially != null) {
                        return Effect.IMPURE
                    }
                    var defaults = Effect.PURE
                    for (f in ri.fields) {
                        val d = (f as? FieldInit.Default)?.field?.default ?: continue
                        defaults = maxOf(defaults, exprTreeEffect(r, d, fns))
                    }
                    defaults
                }
                else -> Effect.PURE
            }
            return maxOf(out, own)
        }

        /**
         * A read of state a sibling operand could change before it happens (D33): a `mut`
         * global or `mut` parameter by name, or a place that is shared ([Rules.isSharedPlace]).
         */
        fun sharedRead(r: Rules, e: Expr): Boolean {
            val model = r.model
            when (e) {
                is Identifier -> when (val s = model.refs[e]) {
                    is GlobalSymbol -> if (s.isMut) return true
                    is ParamSymbol -> if (s.byRef) return true
                    else -> {}
                }
                is MemberAccessExpr -> ((model.members[e] as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol)?.let { if (it.isMut) return true }
                else -> {}
            }
            val p = model.places[e] ?: return false
            // A view read as a value is iterated or indexed next: its contents are borrowed storage.
            return r.isSharedPlace(p) || r.isView(model.types[e])
        }

        /**
         * A call's effect: its callee's, joined with READS when its receiver is a view or a
         * reference (the callee reads the storage those borrow or share, which a sibling could
         * change; a struct or container receiver is read as the place the receiver expression
         * is), and with every defaulted parameter's default.
         */
        fun callEffect(r: Rules, rc: ResolvedCall?, fns: Map<FnSymbol, Effect>): Effect {
            if (rc == null) {
                return Effect.IMPURE
            }
            val fn = rc.fn
            var e = when (rc.kind) {
                CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> when {
                    fn == null -> Effect.IMPURE
                    fn.foreign is Foreign.Magic && fn.body == null -> if (r.bindings.isPure(fn)) Effect.PURE else Effect.IMPURE
                    else -> fns[fn] ?: Effect.IMPURE
                }
                CallKind.MAGIC -> if (fn != null && r.bindings.isPure(fn)) Effect.PURE else Effect.IMPURE
                CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE, CallKind.PRINT -> Effect.IMPURE
            }
            if (e == Effect.IMPURE) {
                return e
            }
            val receiverType = rc.receiver?.let { r.model.types[it] }
            if (receiverType != null && (r.isView(receiverType) || r.isReference(receiverType))) {
                e = Effect.READS
            }
            for (a in rc.args) {
                val d = (a as? ArgBinding.Default)?.param?.default ?: continue
                e = maxOf(e, exprTreeEffect(r, d, fns))
            }
            return e
        }

        /** The join of every node of [e] (lambda bodies aside). */
        fun exprTreeEffect(r: Rules, e: Expr, fns: Map<FnSymbol, Effect>): Effect {
            var out = Effect.PURE
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (out != Effect.IMPURE && lambdas.isEmpty()) {
                    out = maxOf(out, nodeEffect(r, n, fns))
                }
            }
            return out
        }

        /**
         * A write that touches only the writer's own storage: the place starts at a local and
         * reaches no shared object on the way (no field of a class, trait, `Ref` or stdlib
         * handle; no element of a view).
         */
        fun localWrite(r: Rules, p: Place?): Boolean {
            if (p == null || p.root() !is Place.Local) {
                return false
            }
            return p.path().all { step ->
                when (step) {
                    is PathStep.FieldStep -> !r.ownerIsReference(step.sym)
                    is PathStep.IndexStep -> step.kind != IndexKind.MUT_VIEW && step.kind != IndexKind.VIEW
                }
            }
        }
    }

    /** Per-expression effect, bottom-up: the join of the node's own effect and every child expression's. */
    private class ExprEffects(private val r: Rules, private val fns: Map<FnSymbol, Effect>) {
        private val memo = IdentityHashMap<Expr, Effect>()

        fun of(e: Expr): Effect {
            memo[e]?.let { return it }
            val effect = compute(e)
            memo[e] = effect
            r.model.effects[e] = effect
            return effect
        }

        private fun compute(e: Expr): Effect {
            if (e is LambdaExpr) {
                // Creating a closure is pure; its body is walked on its own, for its own expressions.
                return Effect.PURE
            }
            var out = nodeEffect(r, e, fns)
            for (k in AstTree.children(e)) {
                if (out == Effect.IMPURE) {
                    break
                }
                out = maxOf(out, if (k is Expr && k !is Type) of(k) else childrenEffect(k))
            }
            return out
        }

        /** A non-expression child (a statement of an if-expression): the join of everything under it. */
        private fun childrenEffect(n: ASTNode): Effect {
            var out = Effect.PURE
            for (k in AstTree.children(n)) {
                if (out == Effect.IMPURE) {
                    break
                }
                out = maxOf(out, if (k is Expr && k !is Type) of(k) else childrenEffect(k))
            }
            return out
        }
    }
}
