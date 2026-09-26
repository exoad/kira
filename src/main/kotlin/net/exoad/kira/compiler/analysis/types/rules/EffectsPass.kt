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
 * PURE is the answer to one question, D33's: may this operand be evaluated in any order
 * against its siblings without the program noticing? Kira evaluates call arguments and the
 * operands of an operator left to right (C and JS do); C++ leaves them unsequenced, so the
 * C++ emitter spills the operands of a call or an operator into typed temporaries, in source
 * order, whenever two or more of them are IMPURE (R19). That condition is sufficient only if
 * IMPURE covers everything a sibling's evaluation could interact with, so an expression is
 * IMPURE when it
 *
 * - has an effect: it writes anything but the writer's own locals (a `mut` parameter, the
 *   receiver of a `mut fx`, a global, a field through a class reference, a `Ref`, a
 *   `MutView`), prints, calls an extern, a virtual or trait-dispatched method or an `Fx`
 *   value, calls a function that does any of these, or calls a magic binding without
 *   `pure: true`;
 * - or may throw: a `throw` is caught by a `try` around the call, so which operand ran first
 *   decides what a sibling's print or write left behind;
 * - or reads shared state ([Rules.isSharedPlace]): a `mut` global, a `mut` parameter, a field
 *   of a class (its own `this` included), a view (its elements, or the view itself, which is
 *   iterated or indexed next), or the contents of a view or reference receiver (`v.get(0)`,
 *   `c.peek()`), which a sibling could change before the read.
 *
 * Reads of locals, by-value parameters, constants and what they hold by copy are pure: only
 * a `mut` argument or the receiver of a `mut fx` nested in a sibling could change those, and
 * ExclusivityPass refuses that pair (`rules.exclusivity.order`). A stdlib binding marked
 * `pure: true` keeps its promise (no effect, a result from its receiver and arguments); the
 * receiver's own effect is the receiver expression's, so `XS.size()` on a `mut` global is
 * IMPURE while `xs.size()` on a local is PURE. A checked binding's panic is not a throw a
 * `try` catches (D10), so it stays pure as the manifests say.
 *
 * The computation starts from "every function is pure" and removes functions until nothing
 * changes, so mutually recursive functions that do nothing impure stay pure. "Pure" never
 * licenses dropping a call: the emitter spills or emits it in place, and only that.
 */
internal class EffectsPass : RulePass {
    override val name: String = "effects"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fnBodies = bodies.filter { it.fn != null }
        val pure = IdentityHashMap<FnSymbol, Boolean>()
        fnBodies.forEach { pure[it.fn!!] = true }
        var changed = true
        while (changed) {
            changed = false
            for (b in fnBodies) {
                val fn = b.fn!!
                if (pure[fn] != true) {
                    continue
                }
                if (!bodyIsPure(r, b, pure)) {
                    pure[fn] = false
                    changed = true
                }
            }
        }
        val model = r.model
        for ((fn, isPure) in pure) {
            model.fnEffects[fn] = if (isPure) Effect.PURE else Effect.IMPURE
        }
        // A stdlib binding marked pure is pure to the emitter too, so it can ask one table.
        for (m in program.modules) {
            if (!m.isStdlib) {
                continue
            }
            for (s in m.declarations) {
                val fns = when (s) {
                    is FnSymbol -> listOf(s)
                    is ClassSymbol -> s.methods
                    is TraitSymbol -> s.methods
                    else -> emptyList()
                }
                for (fn in fns) {
                    if (fn.foreign is Foreign.Magic && fn.body == null && r.bindings.isPure(fn)) {
                        model.fnEffects[fn] = Effect.PURE
                    }
                }
            }
        }
        val exprs = ExprEffects(r, pure)
        for (b in bodies) {
            b.roots.forEach { root -> AstTree.walk(root) { n -> if (n is Expr && n !is Type) exprs.of(n) } }
        }
    }

    /** True when nothing in [b] (outside its lambdas, which run only when called) is impure under the current [pure] set. */
    private fun bodyIsPure(r: Rules, b: Body, pure: Map<FnSymbol, Boolean>): Boolean {
        var ok = true
        AstScan.walk(b.roots) { n, lambdas ->
            if (!ok || lambdas.isNotEmpty()) {
                return@walk
            }
            if (nodeIsImpure(r, n, pure)) {
                ok = false
            }
        }
        return ok
    }

    companion object {
        /** Whether [n] is itself an impure operation (its children aside). */
        fun nodeIsImpure(r: Rules, n: ASTNode, pure: Map<FnSymbol, Boolean>): Boolean {
            val model = r.model
            val e = n as? Expr ?: return false
            model.opCalls[e]?.let { if (!callIsPure(r, it, pure)) return true }
            if (sharedRead(r, e)) {
                return true
            }
            return when (n) {
                is FunctionCallExpr -> !callIsPure(r, model.calls[n], pure)
                is IntrinsicExpr -> n.intrinsicKey.name != "_static_assert"
                is ThrowExpr -> true
                is AssignmentExpr -> !localWrite(r, model.places[n.target])
                is CompoundAssignmentExpr -> !localWrite(r, model.places[n.left])
                is PlaceAssignmentExpr -> !localWrite(r, model.places[n.target])
                is ObjectInitExpr -> {
                    val ri = model.inits[n] ?: return true
                    val cls = ri.cls
                    if (cls != null && cls.kind == ClassKind.CLASS && cls.initially != null) {
                        return true
                    }
                    ri.fields.any { f -> f is FieldInit.Default && f.field.default?.let { d -> !exprTreeIsPure(r, d, pure) } == true }
                }
                else -> false
            }
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
         * A call is pure when its callee is, every defaulted parameter's default is, and its
         * receiver is no view or reference (the callee reads the storage those borrow or share,
         * which a sibling could change; a struct or container receiver is read as the place
         * the receiver expression is).
         */
        fun callIsPure(r: Rules, rc: ResolvedCall?, pure: Map<FnSymbol, Boolean>): Boolean {
            if (rc == null) {
                return false
            }
            val fn = rc.fn
            val callee = when (rc.kind) {
                CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR ->
                    fn != null && (if (fn.foreign is Foreign.Magic && fn.body == null) r.bindings.isPure(fn) else pure[fn] == true)
                CallKind.MAGIC -> fn != null && r.bindings.isPure(fn)
                CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE, CallKind.PRINT -> false
            }
            if (!callee) {
                return false
            }
            val receiverType = rc.receiver?.let { r.model.types[it] }
            if (receiverType != null && (r.isView(receiverType) || r.isReference(receiverType))) {
                return false
            }
            return rc.args.all { a -> a !is ArgBinding.Default || a.param.default?.let { exprTreeIsPure(r, it, pure) } != false }
        }

        /** Every node of [e] is pure (lambda bodies aside). */
        fun exprTreeIsPure(r: Rules, e: Expr, pure: Map<FnSymbol, Boolean>): Boolean {
            var ok = true
            AstScan.walk(listOf(e)) { n, lambdas ->
                if (ok && lambdas.isEmpty() && nodeIsImpure(r, n, pure)) {
                    ok = false
                }
            }
            return ok
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

    /** Per-expression purity, bottom-up: a node is pure when it is not itself impure and every child expression is. */
    private class ExprEffects(private val r: Rules, private val pure: Map<FnSymbol, Boolean>) {
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
            if (nodeIsImpure(r, e, pure)) {
                return Effect.IMPURE
            }
            for (k in AstTree.children(e)) {
                if (k is Expr && k !is Type && of(k) == Effect.IMPURE) {
                    return Effect.IMPURE
                }
                if (k !is Expr && childrenImpure(k)) {
                    return Effect.IMPURE
                }
            }
            return Effect.PURE
        }

        /** A non-expression child (a statement of an if-expression): impure when anything under it is. */
        private fun childrenImpure(n: ASTNode): Boolean {
            for (k in AstTree.children(n)) {
                if (k is Expr && k !is Type) {
                    if (of(k) == Effect.IMPURE) {
                        return true
                    }
                } else if (childrenImpure(k)) {
                    return true
                }
            }
            return false
        }
    }
}
