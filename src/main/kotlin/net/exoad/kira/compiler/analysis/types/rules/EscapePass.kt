package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import java.util.IdentityHashMap

/**
 * Where `Fx` values and `this` go (design 3.4, D5). It fills `TypedModel.fxEscapes` for every
 * `Fx`-typed parameter of a function or lambda with a body, and `ClassSymbol.thisEscapes`. It
 * reports nothing: views are second-class (decision 4b), and every view rule is ViewPass's.
 *
 * - An `Fx` parameter escapes when it is stored (in a local, field or global), returned, put
 *   in a container (an array literal or a construction), captured by a lambda, or passed to a
 *   parameter that escapes, to an extern, magic, virtual or trait-dispatched callee, or
 *   through an `Fx` value; an operator an `@op_*` overload implements passes its operand the
 *   same way (`Keeper {} + f`). The parameters of a virtual method always escape (a vtable has no
 *   templates), and so do the `Fx` parameters of a function that is itself taken as a value
 *   (`h: Fx<...> = applyTo`, [Coercion.FnRef]): a function whose `Fx` parameter is a template
 *   parameter (design 5.1) is a template, and a template converts to no `kira::Fn`. The rest
 *   is a fixpoint over the call graph, starting from "nothing escapes".
 * - `this` escapes a class when it is used as a value anywhere but as a receiver (returned,
 *   stored, passed, put in a container) or when a lambda capturing it escapes.
 */
internal class EscapePass : RulePass {
    override val name: String = "escape"

    /** Where a value goes. */
    private sealed interface Flow {
        data object Return : Flow

        data object Store : Flow

        data object Container : Flow

        /** Passed to [param] of a callee; [unknown] when the callee cannot be analysed. */
        data class Arg(val param: ParamSymbol?, val unknown: Boolean) : Flow
    }

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fxEsc = IdentityHashMap<ParamSymbol, Boolean>()
        // Every Fx parameter with a body to look at starts non-escaping.
        for (b in bodies) {
            b.fn?.params?.forEach { p -> seed(p, b.fn, fxEsc) }
            AstScan.walk(b.roots) { n, _ ->
                if (n is FunctionDeclParameterExpr) {
                    (r.model.declSyms[n] as? ParamSymbol)?.let { seed(it, null, fxEsc) }
                }
            }
        }
        // A function taken as a value is no template: its Fx parameters are std::function.
        for ((_, c) in r.model.coercions) {
            if (c is Coercion.FnRef) {
                for (p in c.fn.params) {
                    if (fxEsc.containsKey(p)) {
                        fxEsc[p] = true
                    }
                }
            }
        }
        // Captured by a lambda: escaping, whatever the lambda does.
        for ((_, captures) in r.model.captures) {
            for (c in captures) {
                if (c is Capture.Value && c.symbol is ParamSymbol && fxEsc.containsKey(c.symbol)) {
                    fxEsc[c.symbol] = true
                }
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (b in bodies) {
                if (Flows(r, b, fxEsc).run()) {
                    changed = true
                }
            }
        }
        r.model.fxEscapes.putAll(fxEsc)
        ThisInInitially(r, bodies).run()
    }

    /**
     * `rules.escape.this-in-initially` (W2.9 1.2.11, review 1 #4): in a class's `initially`,
     * `this` is used only for the class's own field reads and writes, and as the receiver of a
     * method whose body (and every override's) keeps `this` to itself. It is never a value, an
     * argument, a capture or stored: the object is not built yet. A value class would hand out a
     * half-built copy where the reference backends hand out the finished object, and C++ has no
     * `shared_ptr` to an object under construction. The rule is the typer's, so every target
     * agrees; it replaces W2.4's C++-only refusal for `initially` (a `finally`'s stays there).
     */
    private class ThisInInitially(private val r: Rules, private val bodies: List<Body>) {
        private val model = r.model

        /** Every method that overrides or implements another, under the one it overrides (the methods a call may dispatch to). */
        private val overriders: IdentityHashMap<FnSymbol, MutableList<FnSymbol>> by lazy {
            val out = IdentityHashMap<FnSymbol, MutableList<FnSymbol>>()
            r.program.modules.forEach { m ->
                m.declarations.filterIsInstance<ClassSymbol>().flatMap { it.methods }.forEach { fn ->
                    var o = fn.overrides
                    val seen = IdentityHashMap<FnSymbol, Boolean>()
                    while (o != null && seen.put(o, true) == null) {
                        out.getOrPut(o) { mutableListOf() }.add(fn)
                        o = o.overrides
                    }
                }
            }
            out
        }

        private val escaping = IdentityHashMap<FnSymbol, Boolean>()

        fun run() {
            for (b in bodies) {
                val cls = b.owner as? ClassSymbol ?: continue
                if (b.kind != BodyKind.INITIALLY || cls.kind != ClassKind.USER) {
                    continue
                }
                b.roots.forEach { root -> walk(root, cls) { node, why -> report(cls, node, why) } }
            }
        }

        private fun report(cls: ClassSymbol, node: ASTNode, why: String) {
            r.report(
                "rules.escape.this-in-initially",
                "`this` in ${cls.name}'s initially is only for its own fields and for calling a method that keeps `this` to itself; here $why. " +
                    "The object is not built yet: hand on the fields it needs, or do this after construction.",
                node,
            )
        }

        /**
         * Every use of `this` under [root] that is no field access and no call of a method that
         * keeps `this` ([keepsThis]), reported through [bad]; a lambda that captures `this` is one.
         */
        private fun walk(root: ASTNode, owner: ClassSymbol, bad: (ASTNode, String) -> Unit) {
            val allowed: MutableSet<ASTNode> = java.util.Collections.newSetFromMap(IdentityHashMap())
            AstScan.walk(listOf(root)) { n, _ ->
                when (n) {
                    is MemberAccessExpr -> if (n.origin is ThisExpr && model.members[n] is MemberRef.Field) {
                        allowed.add(n.origin)
                    }
                    is FunctionCallExpr -> {
                        val rc = model.calls[n]
                        val recv = rc?.receiver
                        // A call of an Fx field reads the field; the closure in it was made outside the object.
                        if (rc != null && rc.kind != CallKind.FN_VALUE && (recv is ThisExpr || rc.implicitThis)) {
                            val fn = rc.fn
                            if (fn != null && rc.kind != CallKind.COPY && keepsThis(fn)) {
                                recv?.let { allowed.add(it) }
                            } else {
                                bad((n.name as? MemberAccessExpr)?.member ?: n.name, "it calls '${fn?.name ?: "a method"}', which lets `this` escape")
                                recv?.let { allowed.add(it) }
                            }
                        }
                    }
                    is LambdaExpr -> if (model.captures[n].orEmpty().any { it is Capture.This && it.owner === owner }) {
                        bad(n, "a lambda captures it")
                    }
                    else -> {}
                }
            }
            AstScan.walk(listOf(root)) { n, _ ->
                if (n is ThisExpr && n !in allowed) {
                    bad(n, "it is used as a value (returned, stored, passed or put in a container)")
                }
            }
        }

        /** Whether [fn] and every method that overrides it keep `this` to themselves: no use of it as a value, no lambda capturing it, and only calls on it that keep it too. */
        private fun keepsThis(fn: FnSymbol): Boolean = (listOf(fn) + overriders[fn].orEmpty()).all { !escapes(it) }

        private fun escapes(fn: FnSymbol): Boolean {
            escaping[fn]?.let { return it }
            if (fn.body == null) {
                return (fn.foreign !is Foreign.Magic).also { escaping[fn] = it }
            }
            // Assumed kept while its own body is looked at, so a recursion adds nothing.
            escaping[fn] = false
            val owner = fn.owner as? ClassSymbol
            var found = false
            fn.body?.forEach { s -> if (owner != null) walk(s, owner) { _, _ -> found = true } }
            escaping[fn] = found
            return found
        }
    }

    private fun seed(p: ParamSymbol, fn: FnSymbol?, fxEsc: IdentityHashMap<ParamSymbol, Boolean>) {
        if (p.type is KType.Fn) {
            fxEsc[p] = fn?.isVirtual == true
        }
    }

    /** One pass over one body under the current escape map; returns true when it marked something escaping. */
    private class Flows(
        private val r: Rules,
        private val b: Body,
        private val fxEsc: IdentityHashMap<ParamSymbol, Boolean>,
    ) {
        private val model = r.model
        private var changed = false

        fun run(): Boolean {
            AstScan.walk(b.roots) { n, _ -> node(n) }
            return changed
        }

        private fun node(n: ASTNode) {
            // An operator an `@op_*` overload implements is a call of it (DECISIONS 2): its operand is an argument.
            (n as? Expr)?.let { e -> model.opCalls[e]?.let { args(it) } }
            when (n) {
                is ReturnStatement -> AstScan.values(n.expr).forEach { sink(it, Flow.Return) }
                is VariableDecl -> n.value?.let { v -> AstScan.values(v).forEach { sink(it, Flow.Store) } }
                is AssignmentExpr -> AstScan.values(n.value).forEach { sink(it, Flow.Store) }
                is PlaceAssignmentExpr -> if (n.operator == null) {
                    AstScan.values(n.value).forEach { sink(it, Flow.Store) }
                }
                is ObjectInitExpr -> {
                    n.positionalArgs.forEach { v -> AstScan.values(v).forEach { sink(it, Flow.Container) } }
                    n.namedArgs.forEach { a -> AstScan.values(a.value).forEach { sink(it, Flow.Container) } }
                }
                is ArrayLiteral -> n.value.forEach { v -> AstScan.values(v).forEach { sink(it, Flow.Container) } }
                is FunctionCallExpr -> call(n)
                else -> {}
            }
        }

        private fun call(e: FunctionCallExpr) {
            val rc = model.calls[e]
            if (rc == null) {
                // Unresolved: every argument goes somewhere unknown.
                e.positionalParameters.forEach { p -> AstScan.values(p.value).forEach { sink(it, Flow.Arg(null, true)) } }
                e.namedParameters.forEach { p -> AstScan.values(p.value).forEach { sink(it, Flow.Arg(null, true)) } }
                return
            }
            args(rc)
        }

        /** Every given argument of [rc] flows to its parameter. */
        private fun args(rc: ResolvedCall) {
            val fn = rc.fn
            rc.args.forEachIndexed { i, a ->
                val given = a as? ArgBinding.Given ?: return@forEachIndexed
                val param = fn?.params?.getOrNull(i)
                val unknown = param == null || calleeUnknown(rc)
                AstScan.values(given.expr).forEach { sink(it, Flow.Arg(param, unknown)) }
            }
        }

        /** A callee whose parameters this pass never analysed, or dispatches at run time. */
        private fun calleeUnknown(rc: ResolvedCall): Boolean = when (rc.kind) {
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> rc.fn?.body == null
            else -> true
        }

        private fun escapesBy(flow: Flow): Boolean = when (flow) {
            Flow.Return, Flow.Store, Flow.Container -> true
            is Flow.Arg -> flow.unknown || flow.param == null || fxEsc[flow.param] ?: true
        }

        private fun sink(v: Expr, flow: Flow) {
            when (v) {
                is Identifier -> {
                    val sym = model.refs[v] as? ParamSymbol ?: return
                    if (fxEsc[sym] == false && escapesBy(flow)) {
                        fxEsc[sym] = true
                        changed = true
                    }
                }
                // `this` used as a value: returned, stored, put in a container or passed on (a callee of any kind may keep it).
                is ThisExpr -> markThisEscapes(b.owner)
                is LambdaExpr -> if (escapesBy(flow)) {
                    model.captures[v].orEmpty().forEach { c -> if (c is Capture.This) markThisEscapes(c.owner) }
                }
                else -> {}
            }
        }

        /** A value's `this` (a struct's, a value class's) is never a shared handle, and a trait has no object of its own: only a reference class is marked. */
        private fun markThisEscapes(owner: TypeSymbol?) {
            if (owner is ClassSymbol && owner.isRef) {
                owner.thisEscapes = true
            }
        }
    }
}
