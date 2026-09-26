package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import java.util.IdentityHashMap

/**
 * Where values go (design 3.4, D5). It fills `TypedModel.fxEscapes` for every `Fx`-typed
 * parameter of a function or lambda with a body, `TypedModel.viewEscapes` for every
 * `View`/`MutView` parameter and local, and `ClassSymbol.thisEscapes`; and it reports the two
 * view errors.
 *
 * - An `Fx` parameter escapes when it is stored (in a local, field or global), returned, put
 *   in a container (an array literal or a construction), captured by a lambda, or passed to a
 *   parameter that escapes, to an extern, magic, virtual or trait-dispatched callee, or
 *   through an `Fx` value. The parameters of a virtual method always escape (a vtable has no
 *   templates). The rest is a fixpoint over the call graph, starting from "nothing escapes".
 * - `this` escapes a class when it is used as a value anywhere but as a receiver (returned,
 *   stored, passed, put in a container) or when a lambda capturing it escapes.
 * - A view (a symbol, or a `from`/`slice`/`view` of one) escapes by the same sinks; a view
 *   stored in a local of view type escapes when that local does.
 * - `rules.escape.view-return`: a returned view derived from a local of the function (the
 *   view outlives its storage). `rules.escape.view-field`: a class field of view type. A
 *   struct field of view type is allowed (D5).
 */
internal class EscapePass : RulePass {
    override val name: String = "escape"

    /** Where a value goes. */
    private sealed interface Flow {
        data object Return : Flow

        /** Stored in [target]: a local, a field or a global; [local] when it is a local symbol. */
        data class Store(val local: Symbol?) : Flow

        data object Container : Flow

        /** Passed to [param] of a callee; [unknown] when the callee cannot be analysed. */
        data class Arg(val param: ParamSymbol?, val unknown: Boolean) : Flow
    }

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fxEsc = IdentityHashMap<ParamSymbol, Boolean>()
        val viewEsc = IdentityHashMap<Symbol, Boolean>()
        // Every Fx parameter and every view symbol with a body to look at starts non-escaping.
        for (b in bodies) {
            b.fn?.params?.forEach { p -> seed(r, p, b.fn, fxEsc, viewEsc) }
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is FunctionDeclParameterExpr -> (r.model.declSyms[n] as? ParamSymbol)?.let { seed(r, it, null, fxEsc, viewEsc) }
                    is VariableDecl -> (r.model.declSyms[n] as? LocalSymbol)?.let { if (r.isView(it.type)) viewEsc[it] = false }
                    else -> {}
                }
            }
        }
        // Captured by a lambda: escaping, whatever the lambda does.
        for ((_, captures) in r.model.captures) {
            for (c in captures) {
                if (c is Capture.Value && c.symbol is ParamSymbol && fxEsc.containsKey(c.symbol)) {
                    fxEsc[c.symbol] = true
                }
                if (c is Capture.Value && viewEsc.containsKey(c.symbol)) {
                    viewEsc[c.symbol] = true
                }
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (b in bodies) {
                if (Flows(r, b, fxEsc, viewEsc).run()) {
                    changed = true
                }
            }
        }
        r.model.fxEscapes.putAll(fxEsc)
        r.model.viewEscapes.putAll(viewEsc)
        for (b in bodies) {
            ViewReturns(r, b).check()
        }
        viewFields(r, program)
    }

    private fun seed(r: Rules, p: ParamSymbol, fn: FnSymbol?, fxEsc: IdentityHashMap<ParamSymbol, Boolean>, viewEsc: IdentityHashMap<Symbol, Boolean>) {
        if (p.type is KType.Fn) {
            fxEsc[p] = fn?.isVirtual == true
        }
        if (r.isView(p.type)) {
            viewEsc[p] = false
        }
    }

    private fun viewFields(r: Rules, program: TypedProgram) {
        for (m in program.modules) {
            for (s in m.declarations) {
                val cls = s as? ClassSymbol ?: continue
                if (cls.kind != ClassKind.CLASS) {
                    continue
                }
                for (f in cls.fields) {
                    val t = f.type
                    val viewT = if (r.isView(t)) t else r.facts.maybeInner(t)?.takeIf { r.isView(it) }
                    if (viewT != null) {
                        r.report(
                            "rules.escape.view-field",
                            "Field '${f.name}' of class ${cls.name} is a ${t.display()}: a view borrows storage it does not own, and an " +
                                "object outlives the call that lent it (D5). Hold an Arr<T, N> or a List<T>, or keep the view in a struct.",
                            f.decl,
                        )
                    }
                }
            }
        }
    }

    /** One pass over one body under the current escape maps; returns true when it marked something escaping. */
    private class Flows(
        private val r: Rules,
        private val b: Body,
        private val fxEsc: IdentityHashMap<ParamSymbol, Boolean>,
        private val viewEsc: IdentityHashMap<Symbol, Boolean>,
    ) {
        private val model = r.model
        private var changed = false

        /** View locals of this body and the view symbols each one aliases (from what was stored in it). */
        private val aliases = IdentityHashMap<Symbol, MutableSet<Symbol>>()

        fun run(): Boolean {
            AstScan.walk(b.roots) { n, lambdas -> node(n, lambdas.isNotEmpty()) }
            // A view local escapes when it escapes; so does everything it aliases.
            var again = true
            while (again) {
                again = false
                for ((local, sources) in aliases) {
                    if (viewEsc[local] == true) {
                        for (s in sources) {
                            if (viewEsc[s] == false) {
                                viewEsc[s] = true
                                changed = true
                                again = true
                            }
                        }
                    }
                }
            }
            return changed
        }

        private fun node(n: ASTNode, inLambda: Boolean) {
            when (n) {
                is ReturnStatement -> AstScan.values(n.expr).forEach { sink(it, Flow.Return, inLambda) }
                is VariableDecl -> n.value?.let { v -> AstScan.values(v).forEach { sink(it, Flow.Store(model.declSyms[n]), inLambda) } }
                is AssignmentExpr -> AstScan.values(n.value).forEach { sink(it, Flow.Store(localOf(model.places[n.target])), inLambda) }
                is PlaceAssignmentExpr -> if (n.operator == null) {
                    AstScan.values(n.value).forEach { sink(it, Flow.Store(localOf(model.places[n.target])), inLambda) }
                }
                is ObjectInitExpr -> {
                    n.positionalArgs.forEach { v -> AstScan.values(v).forEach { sink(it, Flow.Container, inLambda) } }
                    n.namedArgs.forEach { a -> AstScan.values(a.value).forEach { sink(it, Flow.Container, inLambda) } }
                }
                is ArrayLiteral -> n.value.forEach { v -> AstScan.values(v).forEach { sink(it, Flow.Container, inLambda) } }
                is FunctionCallExpr -> call(n, inLambda)
                else -> {}
            }
        }

        private fun localOf(p: Place?): Symbol? = (p as? Place.Local)?.sym

        private fun call(e: FunctionCallExpr, inLambda: Boolean) {
            val rc = model.calls[e]
            if (rc == null) {
                // Unresolved: every argument goes somewhere unknown.
                e.positionalParameters.forEach { p -> AstScan.values(p.value).forEach { sink(it, Flow.Arg(null, true), inLambda) } }
                e.namedParameters.forEach { p -> AstScan.values(p.value).forEach { sink(it, Flow.Arg(null, true), inLambda) } }
                return
            }
            val fn = rc.fn
            rc.args.forEachIndexed { i, a ->
                val given = a as? ArgBinding.Given ?: return@forEachIndexed
                val param = fn?.params?.getOrNull(i)
                val unknown = param == null || calleeUnknown(rc)
                AstScan.values(given.expr).forEach { sink(it, Flow.Arg(param, unknown), inLambda) }
            }
        }

        /** A callee whose parameters this pass never analysed, or dispatches at run time. */
        private fun calleeUnknown(rc: ResolvedCall): Boolean = when (rc.kind) {
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> rc.fn?.body == null
            else -> true
        }

        private fun paramEscapes(flow: Flow.Arg, viewParam: Boolean): Boolean {
            if (flow.unknown) {
                return true
            }
            val p = flow.param ?: return true
            return if (viewParam) viewEsc[p] ?: true else fxEsc[p] ?: true
        }

        private fun sink(v: Expr, flow: Flow, inLambda: Boolean) {
            when (v) {
                is Identifier -> {
                    val sym = model.refs[v] ?: return
                    if (sym is ParamSymbol && fxEsc.containsKey(sym) && escapesBy(flow, viewParam = false)) {
                        mark(fxEsc, sym)
                    }
                    if (viewEsc.containsKey(sym)) {
                        viewSink(sym, flow)
                    }
                }
                is ThisExpr -> thisEscapes(flow)
                is LambdaExpr -> {
                    val captures = model.captures[v] ?: return
                    if (escapesBy(flow, viewParam = false) && captures.any { it is Capture.This && (it.owner as? ClassSymbol)?.kind == ClassKind.CLASS }) {
                        classOwner()?.thisEscapes = true
                    }
                }
                is FunctionCallExpr -> {
                    // A lent view goes where the value goes.
                    val rc = model.calls[v] ?: return
                    val recv = rc.receiver ?: return
                    if (rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders) {
                        viewSource(recv)?.let { viewSink(it, flow) }
                    }
                }
                else -> {}
            }
        }

        private fun viewSink(sym: Symbol, flow: Flow) {
            when (flow) {
                is Flow.Store -> {
                    val local = flow.local
                    if (local != null && viewEsc.containsKey(local)) {
                        aliases.getOrPut(local) { LinkedHashSet() }.add(sym)
                    } else {
                        mark(viewEsc, sym)
                    }
                }
                else -> if (escapesBy(flow, viewParam = true)) mark(viewEsc, sym)
            }
        }

        /** The view symbol a lent view starts from: the receiver's symbol, through further lending. */
        private fun viewSource(recv: Expr): Symbol? = when (recv) {
            is Identifier -> model.refs[recv]?.takeIf { viewEsc.containsKey(it) }
            is FunctionCallExpr -> {
                val rc = model.calls[recv]
                if (rc != null && rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders && rc.receiver != null) viewSource(rc.receiver) else null
            }
            else -> null
        }

        private fun escapesBy(flow: Flow, viewParam: Boolean): Boolean = when (flow) {
            Flow.Return, is Flow.Store, Flow.Container -> true
            is Flow.Arg -> paramEscapes(flow, viewParam)
        }

        /** `this` used as a value: returned, stored, put in a container or passed on (a callee of any kind may keep it). */
        @Suppress("UNUSED_PARAMETER")
        private fun thisEscapes(flow: Flow) {
            classOwner()?.thisEscapes = true
        }

        private fun classOwner(): ClassSymbol? = (b.owner as? ClassSymbol)?.takeIf { it.kind == ClassKind.CLASS }

        private fun <K : Symbol> mark(map: IdentityHashMap<K, Boolean>, sym: K) {
            if (map[sym] == false) {
                map[sym] = true
                changed = true
            }
        }
    }

    /** `rules.escape.view-return` over one body: the provenance of every returned view. */
    private class ViewReturns(private val r: Rules, private val b: Body) {
        private val model = r.model

        /** What each view local of this body was built from, in source order. */
        private val sources = IdentityHashMap<Symbol, MutableSet<Symbol>>()

        fun check() {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is VariableDecl -> {
                        val local = model.declSyms[n] as? LocalSymbol ?: return@walk
                        if (r.isView(local.type)) {
                            n.value?.let { v -> AstScan.values(v).forEach { sources.getOrPut(local) { LinkedHashSet() }.addAll(provenance(it, HashSet())) } }
                        }
                    }
                    is AssignmentExpr -> {
                        val local = (model.places[n.target] as? Place.Local)?.sym ?: return@walk
                        if (r.isView(local.type)) {
                            AstScan.values(n.value).forEach { sources.getOrPut(local) { LinkedHashSet() }.addAll(provenance(it, HashSet())) }
                        }
                    }
                    else -> {}
                }
            }
            AstScan.walk(b.roots) { n, lambdas ->
                if (n !is ReturnStatement) {
                    return@walk
                }
                val fnName = lambdas.lastOrNull()?.let { "this lambda" } ?: b.what
                for (v in AstScan.values(n.expr)) {
                    if (!r.isView(model.types[v])) {
                        continue
                    }
                    val local = provenance(v, HashSet()).firstOrNull { it is LocalSymbol && !r.isView(it.type) } ?: continue
                    r.report(
                        "rules.escape.view-return",
                        "${KiraUnparser.text(v)} is a view of the local '${local.name}', which is destroyed when $fnName returns (D5): " +
                            "return a copy (an Arr<T, N> or a List<T>), or view a value the caller owns.",
                        v,
                    )
                }
            }
        }

        /** The storage a view expression borrows from: locals, parameters, fields and globals it can be traced to. */
        private fun provenance(e: Expr, seen: MutableSet<Symbol>): Set<Symbol> {
            model.places[e]?.let { p ->
                val root = r.rootSymbol(p) ?: return emptySet()
                if (root is LocalSymbol && r.isView(root.type)) {
                    if (!seen.add(root)) {
                        return emptySet()
                    }
                    val out = LinkedHashSet<Symbol>()
                    out.add(root)
                    sources[root]?.forEach { s -> out.addAll(if (s is LocalSymbol && r.isView(s.type)) provenance(s, seen) else setOf(s)) }
                    return out
                }
                return setOf(root)
            }
            if (e is FunctionCallExpr) {
                val rc = model.calls[e] ?: return emptySet()
                val recv = rc.receiver ?: return emptySet()
                if (rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders) {
                    return provenance(recv, seen)
                }
            }
            return emptySet()
        }

        /** Provenance of a view local by symbol (through what it was built from). */
        private fun provenance(local: LocalSymbol, seen: MutableSet<Symbol>): Set<Symbol> {
            if (!seen.add(local)) {
                return emptySet()
            }
            val out = LinkedHashSet<Symbol>()
            out.add(local)
            sources[local]?.forEach { s -> out.addAll(if (s is LocalSymbol && r.isView(s.type)) provenance(s, seen) else setOf(s)) }
            return out
        }
    }
}
