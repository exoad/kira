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
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import java.util.IdentityHashMap

/**
 * Purity, bottom-up over the call graph (design 3.4, D33). It reports nothing; it fills
 * `TypedModel.fnEffects` for every function with a body (and every stdlib binding marked
 * `pure: true`) and `TypedModel.effects` for every expression of every body.
 *
 * A function is PURE when it calls only pure functions (or bindings marked `pure: true`),
 * writes no global, calls nothing extern, virtual, trait-dispatched or through an `Fx`
 * value, prints nothing, and writes only to its own locals: a write to a `mut` parameter, to
 * the receiver of a `mut fx`, through a class reference, a `Ref`, or a `MutView` counts as
 * impure. A `throw` does not: as the manifests say of a checked binding, a pure call may
 * still panic. The computation starts from "every function is pure" and removes functions
 * until nothing changes, so mutually recursive functions that do nothing impure stay pure.
 *
 * "Pure" means only that D33 needs no spill for the call; the emitter never drops a pure
 * call (its panic is still an effect the program relies on).
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
            model.opCalls[n as? Expr ?: return false]?.let { if (!callIsPure(r, it, pure)) return true }
            return when (n) {
                is FunctionCallExpr -> !callIsPure(r, model.calls[n], pure)
                is IntrinsicExpr -> n.intrinsicKey.name != "_static_assert"
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

        /** A call is pure when its callee is, and every defaulted parameter's default is. */
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
