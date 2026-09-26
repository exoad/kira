package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
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
 *   templates), and so do the `Fx` parameters of a function that is itself taken as a value
 *   (`h: Fx<...> = applyTo`, [Coercion.FnRef]): a function whose `Fx` parameter is a template
 *   parameter (design 5.1) is a template, and a template converts to no `kira::Fn`. The rest
 *   is a fixpoint over the call graph, starting from "nothing escapes".
 * - `this` escapes a class when it is used as a value anywhere but as a receiver (returned,
 *   stored, passed, put in a container) or when a lambda capturing it escapes.
 * - A view (a symbol, or a `from`/`slice`/`view` of one) escapes by the same sinks; a view
 *   stored in a local of view type escapes when that local does.
 * - `rules.escape.view-return`: a returned value borrows a local of the function or a
 *   temporary (the view outlives its storage). The value is a view (`a.view()`, or `a` itself
 *   coerced to one), a call that returns one of its parameters (`keep(a.view())`, `keep2(a)`
 *   returning a view of its Arr parameter) or a view of its receiver (`b.all()` where `all`
 *   returns `data.view()`; `c.get()` returning a view field), a construction or array literal
 *   holding one (`Cursor { text = s.view() }`), a local that holds one (`c` after `c.text =
 *   s.view()`, `vs` after `vs.add(s.view())`, `p` after `p.load(s.view())` where `load`
 *   stores into `this`), or a closure that captured one (`fx() Size { return v.size() }`
 *   with `v` a view of a local); a view local's provenance is what it was built from. A
 *   temporary is any owning value that is no place: a call result (`mk().view()`), a
 *   construction, an array literal, an operator's result (`(a + b).view()`), an
 *   interpolation, a cast (`(n as Str).view()`), or a coerced one (`return a + b` as a view).
 *   What a callee returns of what it is handed is a fixpoint over the call graph ([Lends]).
 * - `rules.escape.view-store`: such a value is stored where it outlives the local: in a
 *   global, a `mut` parameter or `this` (`GV = s.view()`, `out = s.view()`, `h.v = s.view()`),
 *   passed to a parameter the callee keeps that way, or captured by a closure that leaves
 *   the call; put by a mutator into a container that outlives it (`GL.add(s.view())`,
 *   `out.set(0, s.view())`) or handed to a method that keeps it in `this` when the receiver
 *   outlives it (`GP.load(s.view())`; on a local struct the view merely joins the local's
 *   provenance); and a view of a temporary stored in a local (`v: View<T> = mk().view()`),
 *   which dangles when the statement ends.
 * - `rules.escape.view-field`: a class field whose type holds a view anywhere (a `View`, a
 *   `Maybe` of one, a struct with a view field, a `List<View<T>>`), at the declaration, or at
 *   a construction whose type arguments put one there (`K2<View<Char>> { }`). A struct field
 *   of view type is allowed (D5).
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
                if (c is Capture.Value && viewEsc.containsKey(c.symbol)) {
                    viewEsc[c.symbol] = true
                }
            }
        }
        val escapingLambdas = IdentityHashMap<LambdaExpr, Boolean>()
        var changed = true
        while (changed) {
            changed = false
            for (b in bodies) {
                if (Flows(r, b, fxEsc, viewEsc, escapingLambdas).run()) {
                    changed = true
                }
            }
        }
        r.model.fxEscapes.putAll(fxEsc)
        r.model.viewEscapes.putAll(viewEsc)
        val lends = lends(r, bodies, escapingLambdas)
        for (b in bodies) {
            Provenance(r, b, lends, escapingLambdas).check()
        }
        viewFields(r, program)
        for (b in bodies) {
            AstScan.walk(b.roots) { n, _ -> if (n is ObjectInitExpr) constructedViewFields(r, n) }
        }
    }

    /** A class constructed with type arguments that put a view in a field (`K2<View<Char>> { }`): the declaration could not see it. */
    private fun constructedViewFields(r: Rules, e: ObjectInitExpr) {
        val ri = r.model.inits[e] ?: return
        val cls = ri.cls ?: return
        if (cls.kind != ClassKind.CLASS || ri.substitution.isEmpty()) {
            return
        }
        for (f in cls.fields) {
            val t = f.type.substitute(ri.substitution)
            val viewT = r.viewInside(t) ?: continue
            val holds = if (viewT == t) "" else ", which holds a ${viewT.display()}"
            r.report(
                "rules.escape.view-field",
                "Field '${f.name}' of this ${ri.type.display()} is a ${t.display()}$holds: a view borrows storage it does not own, and an " +
                    "object outlives the call that lent it (D5). Hold an Arr<T, N> or a List<T>, or keep the view in a struct.",
                e,
            )
        }
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
                    val viewT = r.viewInside(t) ?: continue
                    val holds = if (viewT == t) "" else ", which holds a ${viewT.display()}"
                    r.report(
                        "rules.escape.view-field",
                        "Field '${f.name}' of class ${cls.name} is a ${t.display()}$holds: a view borrows storage it does not own, and an " +
                            "object outlives the call that lent it (D5). Hold an Arr<T, N> or a List<T>, or keep the view in a struct.",
                        f.decl,
                    )
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
        private val escapingLambdas: IdentityHashMap<LambdaExpr, Boolean>,
    ) {
        private val model = r.model
        private var changed = false

        /** View locals of this body and the view symbols each one aliases (from what was stored in it). */
        private val aliases = IdentityHashMap<Symbol, MutableSet<Symbol>>()

        /** The lambdas stored in each local of this body: they leave the call when the local does. */
        private val lambdasIn = IdentityHashMap<Symbol, MutableList<LambdaExpr>>()

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
                    if (leavesTheCall(flow)) {
                        lambdasIn[sym]?.forEach { escapingLambdas[it] = true }
                    }
                }
                is ThisExpr -> thisEscapes(flow)
                is LambdaExpr -> {
                    val captures = model.captures[v] ?: return
                    if (flow is Flow.Store && flow.local != null) {
                        lambdasIn.getOrPut(flow.local) { ArrayList() }.add(v)
                    }
                    if (leavesTheCall(flow)) {
                        // The closure outlives this call: what it captured is kept.
                        escapingLambdas[v] = true
                    }
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

        /** A flow that carries a closure out of this call: everything [escapesBy] does but a store into a local of this body. */
        private fun leavesTheCall(flow: Flow): Boolean = when (flow) {
            is Flow.Store -> flow.local == null || flow.local !is LocalSymbol
            else -> escapesBy(flow, viewParam = false)
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


    /** Where a view's storage is: a local, parameter, field or global; the receiver of the enclosing method; or a temporary. */
    private sealed interface Src {
        data class Sym(val sym: Symbol) : Src

        data class This(val owner: TypeSymbol) : Src

        data object Temp : Src
    }

    /**
     * What each function does with the storage it is handed, a fixpoint over the call graph
     * (the sets only grow): [returnsThis] when a returned value borrows the receiver
     * (`return data.view()`, `return text`); [returned] the parameters a returned value
     * borrows (`return v`, `return a` coerced to a view of the Arr parameter `a`); [kept] the
     * parameters a value stored outward borrows (into a global or a `mut` parameter, or into
     * the receiver of a call on one), or that an escaping lambda captures, or that are passed
     * to a kept parameter; [keptInThis] the parameters a value stored into `this` borrows
     * (`text = t` in a `mut fx`), which go where the receiver is: a call on a local struct
     * keeps them in that local, a call on a global or a `mut` parameter keeps them outward.
     */
    private class Lends {
        val returnsThis = IdentityHashMap<FnSymbol, Boolean>()
        val returned = IdentityHashMap<FnSymbol, MutableSet<ParamSymbol>>()
        val kept = IdentityHashMap<FnSymbol, MutableSet<ParamSymbol>>()
        val keptInThis = IdentityHashMap<FnSymbol, MutableSet<ParamSymbol>>()

        fun returnsThis(fn: FnSymbol): Boolean = returnsThis[fn] == true

        fun returned(fn: FnSymbol): Set<ParamSymbol> = returned[fn].orEmpty()

        fun kept(fn: FnSymbol): Set<ParamSymbol> = kept[fn].orEmpty()

        fun keptInThis(fn: FnSymbol): Set<ParamSymbol> = keptInThis[fn].orEmpty()
    }

    private fun lends(r: Rules, bodies: List<Body>, escapingLambdas: IdentityHashMap<LambdaExpr, Boolean>): Lends {
        val lends = Lends()
        val fnBodies = bodies.filter { it.fn != null }
        var changed = true
        while (changed) {
            changed = false
            for (b in fnBodies) {
                val fn = b.fn!!
                val prov = Provenance(r, b, lends, escapingLambdas)
                val out = prov.returned()
                if (out.any { it is Src.This } && lends.returnsThis[fn] != true) {
                    lends.returnsThis[fn] = true
                    changed = true
                }
                fun grow(into: IdentityHashMap<FnSymbol, MutableSet<ParamSymbol>>, srcs: Set<Src>) {
                    val set = into.getOrPut(fn) { LinkedHashSet() }
                    for (s in srcs) {
                        if (s is Src.Sym && s.sym is ParamSymbol && s.sym.fn === fn && set.add(s.sym)) {
                            changed = true
                        }
                    }
                }
                grow(lends.returned, out)
                grow(lends.kept, prov.keptOutward())
                grow(lends.keptInThis, prov.keptInThis())
            }
        }
        return lends
    }

    /**
     * The provenance of views in one body: what every value that is, or holds, a view
     * borrows ([borrowed]), and the two reports over it.
     *
     * `rules.escape.view-return`: a returned value borrows a local of the function or a
     * temporary. `rules.escape.view-store`: such a value is stored where it outlives the
     * local: in a global, a `mut` parameter, `this`, or a local that is or holds a view (a
     * temporary dies at the end of the statement), or it is passed to a parameter the callee
     * keeps.
     */
    private class Provenance(
        private val r: Rules,
        private val b: Body,
        private val lends: Lends,
        private val escapingLambdas: IdentityHashMap<LambdaExpr, Boolean>,
    ) {
        private val model = r.model

        /** What each view-typed, view-holding or `Fx`-typed local of this body was built from, in source order. */
        private val sources = IdentityHashMap<Symbol, MutableSet<Src>>()

        init {
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is VariableDecl -> {
                        val local = model.declSyms[n] as? LocalSymbol ?: return@walk
                        if (tracked(local)) {
                            n.value?.let { v -> AstScan.values(v).forEach { sources.getOrPut(local) { LinkedHashSet() }.addAll(borrowed(it, HashSet())) } }
                        }
                    }
                    is AssignmentExpr -> stored(model.places[n.target], n.value)
                    is PlaceAssignmentExpr -> if (n.operator == null) stored(model.places[n.target], n.value)
                    is FunctionCallExpr -> {
                        // A callee keeping an argument in its receiver stores it in the local the receiver is.
                        val local = receiverLocal(n) ?: return@walk
                        for (k in keptArgs(n)) {
                            if (k.intoThis) {
                                sources.getOrPut(local) { LinkedHashSet() }.addAll(k.srcs)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        /** The provenance of every value the function returns (its lambdas aside). */
        fun returned(): Set<Src> {
            val out = LinkedHashSet<Src>()
            AstScan.walk(b.roots) { n, lambdas ->
                if (n is ReturnStatement && lambdas.isEmpty()) {
                    AstScan.values(n.expr).forEach { out.addAll(borrowed(it, HashSet())) }
                }
            }
            return out
        }

        /** The provenance of every value stored outward (a global, a `mut` parameter, the receiver of a call on one), captured by an escaping lambda, or passed to a kept parameter. */
        fun keptOutward(): Set<Src> {
            val out = LinkedHashSet<Src>()
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is AssignmentExpr -> if (beyondThis(model.places[n.target])) AstScan.values(n.value).forEach { out.addAll(borrowed(it, HashSet())) }
                    is PlaceAssignmentExpr -> if (n.operator == null && beyondThis(model.places[n.target])) AstScan.values(n.value).forEach { out.addAll(borrowed(it, HashSet())) }
                    is FunctionCallExpr -> {
                        val recv = receiverPlace(n)
                        for (k in keptArgs(n)) {
                            if (!k.intoThis || (recv != null && beyondThis(recv))) {
                                out.addAll(k.srcs)
                            }
                        }
                    }
                    is LambdaExpr -> if (escapingLambdas[n] == true) {
                        out.addAll(captured(n, HashSet()))
                    }
                    else -> {}
                }
            }
            return out
        }

        /** The provenance of every value stored into `this` (a field assigned, or an argument a callee keeps in `this` when called on it). */
        fun keptInThis(): Set<Src> {
            val out = LinkedHashSet<Src>()
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is AssignmentExpr -> if (intoThis(model.places[n.target])) AstScan.values(n.value).forEach { out.addAll(borrowed(it, HashSet())) }
                    is PlaceAssignmentExpr -> if (n.operator == null && intoThis(model.places[n.target])) AstScan.values(n.value).forEach { out.addAll(borrowed(it, HashSet())) }
                    is FunctionCallExpr -> {
                        val rc = model.calls[n] ?: return@walk
                        val onThis = rc.implicitThis || receiverPlace(n)?.let { intoThis(it) } == true
                        if (onThis) {
                            keptArgs(n).filter { it.intoThis }.forEach { out.addAll(it.srcs) }
                        }
                    }
                    else -> {}
                }
            }
            return out
        }

        fun check() {
            AstScan.walk(b.roots) { n, lambdas ->
                val fnName = lambdas.lastOrNull()?.let { "this lambda" } ?: b.what
                when (n) {
                    is ReturnStatement -> for (v in AstScan.values(n.expr)) {
                        val (_, how) = dangling(v, borrowed(v, HashSet())) ?: continue
                        r.report(
                            "rules.escape.view-return",
                            "${KiraUnparser.text(v)} $how, which is destroyed when $fnName returns (D5): " +
                                "return a copy (an Arr<T, N> or a List<T>), or view a value the caller owns.",
                            v,
                        )
                    }
                    is VariableDecl -> {
                        val local = model.declSyms[n] as? LocalSymbol ?: return@walk
                        if (tracked(local)) {
                            n.value?.let { v -> AstScan.values(v).forEach { temporary(it, "'${local.name}'") } }
                        }
                    }
                    is AssignmentExpr -> store(model.places[n.target], n.target, n.value, fnName)
                    is PlaceAssignmentExpr -> if (n.operator == null) store(model.places[n.target], n.target, n.value, fnName)
                    is FunctionCallExpr -> {
                        val rc = model.calls[n] ?: return@walk
                        val callee = rc.fn?.name
                        // Where a kept-in-receiver argument goes: `this`, or the receiver when it outlives the call.
                        val into = when {
                            rc.implicitThis -> "this"
                            else -> rc.receiver?.takeIf { recv -> receiverPlace(n)?.let { outward(it) } == true }?.let { KiraUnparser.text(it) }
                        }
                        for (k in keptArgs(n)) {
                            val (_, how) = dangling(k.arg, k.srcs) ?: continue
                            if (!k.intoThis) {
                                r.report(
                                    "rules.escape.view-store",
                                    "${KiraUnparser.text(k.arg)} $how, and '$callee' keeps it beyond the call (D5): the view would outlive its " +
                                        "storage. Pass a view of a value that outlives the callee, or let the callee copy.",
                                    k.arg,
                                )
                            } else if (into != null) {
                                r.report(
                                    "rules.escape.view-store",
                                    "${KiraUnparser.text(k.arg)} $how, and '$callee' keeps it in '$into', which outlives $fnName (D5): the view " +
                                        "would outlive its storage. Pass a view of a value that outlives the store, or let the callee copy.",
                                    k.arg,
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        /** The place a call's receiver is, when it is one (a lent view stands for what it was lent from). */
        private fun receiverPlace(e: FunctionCallExpr): Place? = model.calls[e]?.receiver?.let { r.placeOf(it) }

        /** The tracked local a call's receiver starts from, when it is one. */
        private fun receiverLocal(e: FunctionCallExpr): LocalSymbol? =
            (receiverPlace(e)?.root() as? Place.Local)?.sym?.takeIf { tracked(it) }

        /** A store into a place that outlives this call (a global, a `mut` parameter, `this`): the value must not borrow a local. */
        private fun store(target: Place?, at: Expr, value: Expr, fnName: String) {
            if (outward(target)) {
                for (v in AstScan.values(value)) {
                    val (_, how) = dangling(v, borrowed(v, HashSet())) ?: continue
                    r.report(
                        "rules.escape.view-store",
                        "${KiraUnparser.text(v)} $how, and '${KiraUnparser.text(at)}' outlives $fnName (D5): the view would outlive " +
                            "its storage. Store a copy (an Arr<T, N> or a List<T>), or a view of what outlives the store.",
                        v,
                    )
                }
                return
            }
            val local = (target?.root() as? Place.Local)?.sym ?: return
            if (tracked(local)) {
                AstScan.values(value).forEach { temporary(it, "'${KiraUnparser.text(at)}'") }
            }
        }

        /** A view of a temporary stored in a local: it dangles as soon as the statement ends. */
        private fun temporary(v: Expr, into: String) {
            if (Src.Temp in borrowed(v, HashSet())) {
                r.report(
                    "rules.escape.view-store",
                    "${KiraUnparser.text(v)} is a view of a temporary, which is destroyed at the end of this statement, and $into " +
                        "would keep it (D5): store the value itself (an Arr<T, N>, a List<T> or a Str) and view that.",
                    v,
                )
            }
        }

        /** An argument the callee keeps: [intoThis] in its receiver (`text = t`; a magic mutator's `add`, `set`, `put`), else beyond the call. */
        private class Kept(val arg: Expr, val srcs: Set<Src>, val intoThis: Boolean)

        /**
         * The arguments of [e] bound to parameters the callee keeps, with their provenance.
         * A callee with a body keeps what [Lends] says; a mutator without one (a magic
         * container's `add`, `set`, `put`, `push`) called on a receiver that holds views keeps
         * every view or view-holding argument in that receiver.
         */
        private fun keptArgs(e: FunctionCallExpr): List<Kept> {
            val rc = model.calls[e] ?: return emptyList()
            val fn = rc.fn ?: return emptyList()
            val out = mutableListOf<Kept>()
            if (analysed(rc)) {
                val kept = lends.kept(fn)
                val inThis = lends.keptInThis(fn)
                if (kept.isEmpty() && inThis.isEmpty()) {
                    return emptyList()
                }
                rc.args.forEachIndexed { i, a ->
                    val given = a as? ArgBinding.Given ?: return@forEachIndexed
                    val param = fn.params.getOrNull(i) ?: return@forEachIndexed
                    if (param in kept) {
                        AstScan.values(given.expr).forEach { out.add(Kept(it, lent(it, HashSet()), false)) }
                    } else if (param in inThis) {
                        AstScan.values(given.expr).forEach { out.add(Kept(it, lent(it, HashSet()), true)) }
                    }
                }
            } else if (fn.body == null && fn.isMutMethod && rc.receiver != null && r.holdsView(model.types[rc.receiver])) {
                for (a in rc.args) {
                    val given = a as? ArgBinding.Given ?: continue
                    if (given.byRef) {
                        continue
                    }
                    AstScan.values(given.expr).forEach { v ->
                        if (traceable(v)) {
                            out.add(Kept(v, borrowed(v, HashSet()), true))
                        }
                    }
                }
            }
            return out
        }

        /** The first source in [srcs] the value must not borrow here: a local of this body, or a temporary; with the message's phrase. */
        private fun dangling(v: Expr, srcs: Set<Src>): Pair<Src, String>? {
            for (s in srcs) {
                if (s == Src.Temp) {
                    return s to "is a view of a temporary"
                }
                val local = (s as? Src.Sym)?.sym as? LocalSymbol ?: continue
                if (tracked(local)) {
                    continue
                }
                val self = v is Identifier && model.refs[v] === local
                return s to when {
                    v is LambdaExpr -> "captures a view of the local '${local.name}'"
                    self -> "is returned as a view of itself, a local"
                    else -> "is a view of the local '${local.name}'"
                }
            }
            return null
        }

        /** A place that outlives the call: rooted at a global, a `mut` parameter or `this`. */
        private fun outward(p: Place?): Boolean = beyondThis(p) || intoThis(p)

        /** A place that outlives the call and is not the receiver: rooted at a global or a `mut` parameter. */
        private fun beyondThis(p: Place?): Boolean = when (val root = p?.root()) {
            is Place.Global -> true
            is Place.Param -> root.sym.byRef
            else -> false
        }

        /** A place rooted at the enclosing receiver. */
        private fun intoThis(p: Place?): Boolean = p?.root() is Place.This

        /**
         * A local whose value is a view, holds one, or is a closure: its provenance is what
         * was stored in it (a closure's, what it captured), never its own storage.
         */
        private fun tracked(s: Symbol): Boolean = s is LocalSymbol && (r.isView(s.type) || r.holdsView(s.type) || s.type is KType.Fn)

        /** A store into (a part of) a tracked local: the value's provenance joins the local's. */
        private fun stored(target: Place?, value: Expr) {
            val local = (target?.root() as? Place.Local)?.sym ?: return
            if (!tracked(local)) {
                return
            }
            AstScan.values(value).forEach { sources.getOrPut(local) { LinkedHashSet() }.addAll(borrowed(it, HashSet())) }
        }

        /** A value that is a view: typed as one, or coerced to one (`return a` on an `Arr`, `List` or `Str` where a `View` is expected). */
        private fun isViewValue(e: Expr): Boolean = r.isView(model.types[e]) || model.coercions[e] is Coercion.ToView

        /** A value whose provenance can be traced: a view, something holding one, or a closure (which holds what it captured). */
        private fun traceable(e: Expr): Boolean = isViewValue(e) || r.holdsView(model.types[e]) || e is LambdaExpr || model.types[e] is KType.Fn

        private fun analysed(rc: ResolvedCall): Boolean =
            rc.fn?.body != null && (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD || rc.kind == CallKind.OP_OVERLOAD || rc.kind == CallKind.CTOR)

        /** The storage a place is: the symbol it starts from, the enclosing receiver, or (a field of a call result) a temporary. */
        private fun own(p: Place, seen: MutableSet<Symbol>): Set<Src> = when (val root = p.root()) {
            is Place.Local -> if (tracked(root.sym)) ofLocal(root.sym, seen) else setOf(Src.Sym(root.sym))
            is Place.Param -> setOf(Src.Sym(root.sym))
            is Place.Global -> setOf(Src.Sym(root.sym))
            is Place.This -> setOf(Src.This(root.owner))
            is Place.Field -> setOf(Src.Temp)
            is Place.Index -> emptySet()
        }

        /**
         * The storage a value borrows, when it is or holds a view, or is a closure: the
         * locals, parameters, fields and globals it can be traced to, the receiver, or a
         * temporary (an owning value that is no place, coerced to a view: `return a + b`).
         * A value that is none of these borrows nothing.
         */
        private fun borrowed(e: Expr, seen: MutableSet<Symbol>): Set<Src> {
            if (e is LambdaExpr) {
                return captured(e, seen)
            }
            if (!traceable(e)) {
                return emptySet()
            }
            model.places[e]?.let { return own(it, seen) }
            return when (e) {
                is FunctionCallExpr -> call(e, seen)
                is ObjectInitExpr -> {
                    val out = LinkedHashSet<Src>()
                    e.positionalArgs.forEach { a -> AstScan.values(a).forEach { out.addAll(borrowed(it, seen)) } }
                    e.namedArgs.forEach { a -> AstScan.values(a.value).forEach { out.addAll(borrowed(it, seen)) } }
                    out
                }
                is ArrayLiteral -> {
                    val out = LinkedHashSet<Src>()
                    e.value.forEach { a -> AstScan.values(a).forEach { out.addAll(borrowed(it, seen)) } }
                    out
                }
                is IfExpr -> AstScan.values(e).flatMapTo(LinkedHashSet()) { borrowed(it, seen) }
                is StringLiteral -> emptySet()
                else -> if (model.coercions[e] is Coercion.ToView) setOf(Src.Temp) else emptySet()
            }
        }

        /**
         * What a closure holds: the provenance of every view, view-holding or closure local it
         * captured, and every such parameter (the caller's storage). A captured scalar, `Str`
         * or container is a copy and borrows nothing.
         */
        private fun captured(l: LambdaExpr, seen: MutableSet<Symbol>): Set<Src> {
            val out = LinkedHashSet<Src>()
            for (c in model.captures[l].orEmpty()) {
                val sym = (c as? Capture.Value)?.symbol ?: continue
                when {
                    sym is LocalSymbol && tracked(sym) -> out.addAll(ofLocal(sym, seen))
                    sym is ParamSymbol && (r.isView(sym.type) || r.holdsView(sym.type) || sym.type is KType.Fn) -> out.add(Src.Sym(sym))
                    else -> {}
                }
            }
            return out
        }

        /**
         * What a value handed to a lender or a keeping callee is: a view, holder or closure
         * (traced), storage of its own (the symbol, the receiver), or a temporary: a call
         * result, a construction, an array literal, an operator's result (`a + b`), an
         * interpolation, a cast (`n as Str`); anything that owns its value and is no place.
         * A string literal is static and borrows nothing; a name without a place (a function,
         * kira:core's `true`/`false`/`null`) owns nothing to view.
         */
        private fun lent(v: Expr, seen: MutableSet<Symbol>): Set<Src> {
            if (traceable(v)) {
                return borrowed(v, seen)
            }
            model.places[v]?.let { return own(it, seen) }
            return when (v) {
                is StringLiteral, is Identifier -> emptySet()
                is ThisExpr -> b.owner?.let { setOf<Src>(Src.This(it)) } ?: emptySet()
                is IfExpr -> AstScan.values(v).flatMapTo(LinkedHashSet()) { lent(it, seen) }
                else -> setOf(Src.Temp)
            }
        }

        /**
         * What a call's view result borrows: the receiver of a lending magic method
         * (`xs.view()`, `v.from(1)`); for a callee with a body, the arguments bound to the
         * parameters it returns and, when it returns a view of `this`, its receiver; for a
         * callee this pass never analysed, every view or view-holding argument and such a
         * receiver.
         */
        private fun call(e: FunctionCallExpr, seen: MutableSet<Symbol>): Set<Src> {
            val rc = model.calls[e] ?: return emptySet()
            if (rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders && rc.receiver != null) {
                return lent(rc.receiver, seen)
            }
            val fn = rc.fn
            val analysed = fn != null && analysed(rc)
            val out = LinkedHashSet<Src>()
            rc.args.forEachIndexed { i, a ->
                val given = a as? ArgBinding.Given ?: return@forEachIndexed
                val param = fn?.params?.getOrNull(i)
                if (analysed) {
                    if (param != null && param in lends.returned(fn!!)) {
                        AstScan.values(given.expr).forEach { out.addAll(lent(it, seen)) }
                    }
                } else if (traceable(given.expr)) {
                    AstScan.values(given.expr).forEach { out.addAll(borrowed(it, seen)) }
                }
            }
            if (analysed) {
                if (lends.returnsThis(fn!!)) {
                    if (rc.implicitThis) {
                        b.owner?.let { out.add(Src.This(it)) }
                    } else {
                        rc.receiver?.let { out.addAll(lent(it, seen)) }
                    }
                }
            } else {
                rc.receiver?.takeIf { traceable(it) }?.let { out.addAll(borrowed(it, seen)) }
            }
            return out
        }

        /** Provenance of a tracked local: itself (filtered out by the report), and what was stored in it. */
        private fun ofLocal(local: LocalSymbol, seen: MutableSet<Symbol>): Set<Src> {
            if (!seen.add(local)) {
                return emptySet()
            }
            val out = LinkedHashSet<Src>()
            out.add(Src.Sym(local))
            sources[local]?.forEach { s ->
                val sym = (s as? Src.Sym)?.sym
                if (sym is LocalSymbol && tracked(sym)) out.addAll(ofLocal(sym, seen)) else out.add(s)
            }
            return out
        }
    }
}
