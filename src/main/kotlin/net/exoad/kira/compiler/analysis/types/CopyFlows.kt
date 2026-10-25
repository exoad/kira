package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The copy-then-change sites of [p] for a value [turns] names: where a copy went on and one name then sees a change in
 * place the other makes. Every body is read (a callee in another file counts); what is in [reportedIn] is listed.
 */
abstract class CopyFlows(protected val p: TypedProgram, private val reportedIn: Set<SourceContext>) {
    /** Each site: where, and what to look at. */
    val hand = mutableListOf<Pair<ASTNode, String>>()

    /** What a site is: a copy gone on, a changing place returned, a lambda's capture, a Map's element changed through `get`. */
    enum class Kind { COPY, RETURN, CAPTURE, ELEMENT }

    /** Whether a value of [t] is one this check follows. */
    protected abstract fun turns(t: KType?): Boolean

    /** The sentence a site of [kind] is listed with, from what [what] says. */
    protected abstract fun says(kind: Kind, what: String): String

    /** The variables and fields whose value changes in place somewhere: a field only assigned is not one (its value is replaced). */
    private val inPlace: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

    /** The variables and fields whose elements change in place (`xs[0].add(v)`), not only the collection itself. */
    private val inPlaceElements: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

    /** Each local's and parameter's changes (where, the place's path, an assignment or in place), and each local's reads. */
    private val changesOf = IdentityHashMap<Symbol, MutableList<Change>>()
    private val readsOf = IdentityHashMap<Symbol, MutableList<Pos>>()

    private data class Change(val at: Pos, val path: List<PathStep>, val assign: Boolean)

    /** Each loop's span: a use inside one happens again after any point of it. */
    private val loops = mutableListOf<Pair<Pos, Pos>>()

    /** A `for` variable over a collection's elements, by the place it iterates: each value is that place's element. */
    private val loopOf = IdentityHashMap<Symbol, Place>()

    /** Functions whose result is (or may be) a place another name holds: a call to one is no fresh value. */
    private val aliasing: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /** The parameters a function keeps past its call, and whether what keeps one changes. */
    private val escaping = IdentityHashMap<ParamSymbol, Boolean>()

    private val noted: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())

    /** False on the passes that only find what each function keeps; the last pass lists. */
    protected var reporting = true
        private set

    /** Each source's number, so positions in two files never compare as one file's. */
    private val fileNo = IdentityHashMap<SourceContext, Int>()

    private data class Pos(val file: Int, val line: Int, val col: Int) : Comparable<Pos> {
        override fun compareTo(other: Pos): Int = compareValuesBy(this, other, { it.file }, { it.line }, { it.col })
    }

    fun run(): CopyFlows {
        val all = bodies(p)
        val reported = all.filter { b -> p.locate(b)?.first?.let { it in reportedIn } == true }
        all.forEach { b -> AstTree.walk(b) { n -> scan(n) } }
        // A function returning a place another name holds is aliasing, and so is one returning a call to one.
        var grew = true
        while (grew) {
            grew = false
            for (b in all) {
                val fd = b as? FunctionDecl ?: continue
                val fn = p.model.declSyms[fd] as? FnSymbol ?: continue
                if (fn in aliasing) continue
                if (results(fd).any { turns(type(it)) && (outlives(placeRead(it)) || aliasingCall(it)) }) {
                    aliasing += fn
                    grew = true
                }
            }
        }
        // The parameters a function keeps, to a fixed point over every body; then the report.
        reporting = false
        repeat(3) { all.forEach { b -> AstTree.walk(b) { n -> flows(n) } } }
        reporting = true
        reported.forEach { b -> AstTree.walk(b) { n -> flows(n) } }
        return this
    }

    // ---- what changes in place, and where each local is used --------------------------------------

    private fun scan(n: ASTNode) {
        when (n) {
            is AssignmentExpr -> place(n.target)?.takeIf { it !is Place.Local && it !is Place.Global && it !is Place.Param }?.let { mark(it, n, assign = true) }
            is CompoundAssignmentExpr -> place(n.left)?.takeIf { it !is Place.Local && it !is Place.Global && it !is Place.Param }?.let { mark(it, n, assign = true) }
            is PlaceAssignmentExpr -> place(n.target)?.let { mark(it, n, assign = true) }
            is FunctionCallExpr -> {
                val rc = p.model.calls[n] ?: return
                val fn = rc.fn
                if (fn?.isMutMethod == true) rc.receiver?.let { r -> (place(r) ?: elementRead(r))?.let { mark(it, n) } }
                rc.args.forEach { a -> if (a is ArgBinding.Given && a.byRef) place(a.expr)?.let { mark(it, n) } }
                if (fn?.name in LENDERS && (p.model.types[n] as? KType.Nominal)?.sym?.name == "MutView") rc.receiver?.let { r -> place(r)?.let { mark(it, n) } }
            }
            is Identifier -> (p.model.refs[n] as? LocalSymbol)?.let { s -> pos(n)?.let { readsOf.getOrPut(s) { mutableListOf() } += it } }
            is WhileIterationStatement -> span(n)?.let { loops += it }
            is ForIterationStatement -> {
                span(n)?.let { loops += it }
                val plan = p.model.loops[n]
                val v = plan?.variable
                if (v != null && turns(plan.element)) iterated(n.forIterationExpr.target)?.let { loopOf[v] = it }
            }
            else -> {}
        }
    }

    protected fun place(e: Expr): Place? = p.model.readPlace(e)

    /** [pl] changes, at [at]: in place, or ([assign]) by an assignment, which changes only what holds it in place. */
    private fun mark(pl: Place, at: ASTNode, assign: Boolean = false) {
        // A `with`'s copy is a new value, which nothing else holds.
        if ((pl.root() as? Place.Local)?.sym?.builds == true) return
        val root = rootSymbol(pl)
        if (root is LocalSymbol || root is ParamSymbol) pos(at)?.let { changesOf.getOrPut(root) { mutableListOf() } += Change(it, pl.path(), assign) }
        val held = (if (assign) container(pl) else pl) ?: return
        val path = held.path()
        rootSymbol(held)?.let { r ->
            inPlace += r
            if (path.any { it is PathStep.IndexStep }) inPlaceElements += r
        }
        path.forEachIndexed { i, s ->
            val f = (s as? PathStep.FieldStep)?.sym ?: return@forEachIndexed
            inPlace += f
            if (path.drop(i + 1).any { it is PathStep.IndexStep }) inPlaceElements += f
        }
    }

    /** What an assignment to [pl] changes in place: the value holding the field or element. */
    private fun container(pl: Place): Place? = when (pl) {
        is Place.Field -> pl.receiver
        is Place.Index -> pl.container
        else -> null
    }

    private fun rootSymbol(pl: Place): Symbol? = when (val r = pl.root()) {
        is Place.Local -> r.sym
        is Place.Param -> r.sym
        is Place.Global -> r.sym
        is Place.Field -> r.sym
        else -> null
    }

    private fun pos(n: ASTNode): Pos? = p.locate(n)?.let { (ctx, at) -> Pos(fileNo.getOrPut(ctx) { fileNo.size }, at.lineNumber, at.column) }

    /** A node's span: its own start to the last start inside it. */
    private fun span(n: ASTNode): Pair<Pos, Pos>? {
        val start = pos(n) ?: return null
        var end = start
        AstTree.walk(n) { k -> pos(k)?.let { if (it > end) end = it } }
        return start to end
    }

    /**
     * Whether [uses] of [sym] holds one after [at], or one in a loop around [at] (which runs again after it) that [sym] is
     * declared outside of: one declared in the loop is a new value each time round.
     */
    private fun after(uses: List<Pos>?, at: Pos, sym: Symbol): Boolean {
        if (uses.isNullOrEmpty()) return false
        if (uses.any { it > at }) return true
        val declared = sym.decl?.let { pos(it) }
        return loops.any { (s, e) -> at in s..e && (declared == null || declared !in s..e) && uses.any { it in s..e } }
    }

    /** Whether the value at [path] of [s] changes after [at]: in place at or around it, or a place inside it assigned. */
    private fun changedAfter(s: Symbol, at: Pos, path: List<PathStep> = emptyList()): Boolean =
        after(changesOf[s]?.filter { c -> if (c.assign) c.path.size > path.size && prefix(path, c.path) else prefix(path, c.path) || prefix(c.path, path) }?.map { it.at }, at, s)

    private fun prefix(a: List<PathStep>, b: List<PathStep>): Boolean = a.size <= b.size && a.indices.all { a[it].matches(b[it]) }

    private fun usedAfter(s: Symbol, at: Pos): Boolean = after(changesOf[s]?.map { it.at }, at, s) || after(readsOf[s], at, s)

    /**
     * Whether the value at [pl] changes in place somewhere: a field's by its symbol, an element's by its collection's.
     * A field of a call's result is a new value's, which nothing else holds.
     */
    private fun changesInPlace(pl: Place?): Boolean = when (pl) {
        null -> false
        is Place.Field -> pl.root().let { !(it is Place.Field && it.receiver == null) } && pl.sym in inPlace
        is Place.Index -> elementsChange(pl.container)
        else -> rootSymbol(pl)?.let { it in inPlace } == true
    }

    /** Whether an element of the collection at [pl] changes in place. */
    private fun elementsChange(pl: Place?): Boolean {
        if (pl == null) return false
        val last = pl.path().lastOrNull()
        return if (last is PathStep.FieldStep) last.sym in inPlaceElements else rootSymbol(pl)?.let { it in inPlaceElements } == true
    }

    /** The place a loop's [target] iterates the elements of: a collection's place, or the place a view, `entries()` or `toArr()` reads. */
    private fun iterated(target: Expr): Place? {
        place(target)?.let { return it }
        val c = target as? FunctionCallExpr ?: (target as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return null
        val rc = p.model.calls[c] ?: return null
        if (rc.fn?.name !in ITERATED || (rc.fn?.owner as? ClassSymbol)?.kind != ClassKind.MAGIC) return null
        return rc.receiver?.let { place(it) ?: iterated(it) }
    }

    /**
     * An element read through a call that is no place to the typer: `m.get(k)` of a Map (and `.unwrap()` of it), or a
     * queue's `peek()`: a copy of the element where it is copied, the element itself where it is shared.
     */
    protected fun elementRead(e: Expr): Place? {
        val c = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return null
        val rc = p.model.calls[c] ?: return null
        val fn = rc.fn ?: return null
        val owner = fn.owner as? ClassSymbol ?: return null
        if (owner.kind != ClassKind.MAGIC) return null
        val recv = rc.receiver ?: return null
        return when {
            fn.name in UNWRAPS && (owner.name == "Maybe" || owner.name == "Result") -> elementRead(recv)
            fn.name == "get" && owner.name == "Map" -> place(recv)?.let { Place.Index(it, IndexKind.MAP) }
            fn.name == "peek" -> place(recv)?.let { Place.Index(it, IndexKind.OTHER) }
            else -> null
        }
    }

    // ---- where a value goes on ------------------------------------------------------------------

    /** Where a value goes: a local, somewhere that outlives the function ([Held.changed]: it changes in place), or a parameter. */
    private sealed interface Dest {
        data class Local(val sym: Symbol, val what: String) : Dest
        data class Held(val changed: Boolean, val what: String) : Dest
        data class Param(val param: ParamSymbol, val fn: FnSymbol) : Dest
    }

    private fun flows(n: ASTNode) {
        when (n) {
            is VariableDecl -> n.value?.let { v ->
                val sym = p.model.declSyms[n]
                val into = "into '${n.name.value}'"
                flow(v, if (sym is LocalSymbol) Dest.Local(sym, into) else Dest.Held(sym?.let { it in inPlace } == true, into))
            }
            is AssignmentExpr -> {
                val target = place(n.target)
                val into = "into ${KiraUnparser.text(n.target)}"
                flow(n.value, if (target is Place.Local) Dest.Local(target.sym, into) else Dest.Held(changesInPlace(target), into))
            }
            // `a.b = v`, `a[i] = v`, `this.x = v`: into a field or an element, which may change in place later.
            is PlaceAssignmentExpr -> if (n.operator == null) {
                flow(n.value, Dest.Held(changesInPlace(place(n.target)), "into ${KiraUnparser.text(n.target)}"))
            }
            is ObjectInitExpr -> p.model.inits[n]?.fields?.forEach { f ->
                if (f is FieldInit.Given) flow(f.expr, Dest.Held(f.field in inPlace, "into the field ${f.field.name}"))
            }
            is FunctionCallExpr -> call(n)
            is ReturnStatement -> results(n.expr).forEach { v -> returned(v) }
            is BuilderExpr -> n.elements.forEach { flow(it, Dest.Held(false, "into a new collection")) }
            is LambdaExpr -> captures(n)
            else -> {}
        }
    }

    private fun call(n: FunctionCallExpr) {
        val rc = p.model.calls[n] ?: return
        val fn = rc.fn ?: return
        val magic = (fn.owner as? ClassSymbol)?.kind == ClassKind.MAGIC
        // A changing function on a Map's element read by `get`: a copy of it, or the element itself.
        val r = rc.receiver
        if (reporting && magic && fn.isMutMethod && r != null && place(r) == null && elementRead(r) != null) {
            note(n, Kind.ELEMENT, "'${KiraUnparser.text(r)}'")
        }
        rc.args.forEachIndexed { i, a ->
            if (a !is ArgBinding.Given || a.byRef) return@forEachIndexed
            val param = fn.params.getOrNull(i)
            when {
                // A mutator stores what it is given (`xs.add(v)`): into its receiver.
                fn.isMutMethod && magic ->
                    flow(a.expr, Dest.Held(elementsChange(rc.receiver?.let { place(it) }), "into ${rc.receiver?.let { KiraUnparser.text(it) } ?: "the receiver"}"))
                fn.body != null && param != null -> flow(a.expr, Dest.Param(param, fn))
                else -> {}
            }
        }
    }

    private fun captures(l: LambdaExpr) {
        if (!reporting) return
        val at = pos(l) ?: return
        for (c in p.model.captures[l].orEmpty()) {
            val (sym, t) = when (c) {
                is Capture.Value -> c.symbol to when (val s = c.symbol) {
                    is LocalSymbol -> s.type
                    is ParamSymbol -> s.type
                    else -> null
                }
                is Capture.Field -> c.field to c.field.type
                is Capture.This -> continue
            }
            val later = if (sym is LocalSymbol) changedAfter(sym, at) else sym in inPlace
            if (turns(t) && later) note(l, Kind.CAPTURE, "a lambda captures '${sym.name}', ${t!!.display()}")
        }
    }

    /**
     * [v] flows on into [dest]: a site when one name can see a change the other makes. A local that nothing uses after
     * the flow is moved; a parameter a callee keeps shares the caller's value with what keeps it ([escaping]).
     */
    private fun flow(v: Expr, dest: Dest) {
        val t = type(v)
        if (!turns(t)) return
        val src = placeRead(v) ?: elementRead(v)
        val fromCall = aliasingCall(v)
        if (src == null && !fromCall) return
        val at = pos(v) ?: return
        val root = src?.let { rootSymbol(it) }
        val srcParam = (src?.root() as? Place.Param)?.sym?.takeIf { src.path().isEmpty() }
        // A `for` variable is an element of what the loop iterates, which still holds it.
        val element = src?.takeIf { it.path().isEmpty() }?.let { root?.let { r -> loopOf[r] } }
        // What keeps the value: does it change later?
        val destChanged = when (dest) {
            is Dest.Local -> changedAfter(dest.sym, at)
            is Dest.Held -> dest.changed
            is Dest.Param -> escaping[dest.param] ?: return
        }
        // A parameter kept where it outlives the call: its callers are checked instead.
        if (srcParam != null && dest !is Dest.Local) {
            val keeps = dest is Dest.Held || dest is Dest.Param
            if (keeps) escaping[srcParam] = (escaping[srcParam] ?: false) || destChanged
            return
        }
        if (!reporting) return
        val srcChanged = when {
            src == null -> callArgsChange(v, at)
            element != null -> elementsChange(element)
            root is LocalSymbol -> {
                // Nothing reads or changes the local after it went on: moved, not shared.
                if (!usedAfter(root, at)) return
                changedAfter(root, at, src.path())
            }
            // A parameter changes after; a field read off one may change anywhere (a callee may change it).
            root is ParamSymbol && src.path().isEmpty() -> changedAfter(root, at)
            else -> changesInPlace(src)
        }
        if (!srcChanged && !destChanged) return
        // A change to the copy matters only while the source is still read.
        if (!srcChanged && element == null && root is LocalSymbol && !usedAfter(root, at)) return
        val into = when (dest) {
            is Dest.Local -> dest.what
            is Dest.Held -> dest.what
            is Dest.Param -> "into the parameter '${dest.param.name}' of ${dest.fn.name}, which keeps it"
        }
        val what = if (src != null) "'${KiraUnparser.text(v)}'" else "the result of '${KiraUnparser.text(v)}', a place of the callee's"
        note(v, Kind.COPY, "${article(t!!.display())} copied from $what $into, then changed${if (srcChanged) " (the source changes)" else " (the copy changes)"}")
    }

    /** Whether a followed argument of the aliasing call [v] changes after it: the result may be that argument. */
    private fun callArgsChange(v: Expr, at: Pos): Boolean {
        val c = v as? FunctionCallExpr ?: (v as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return false
        val rc = p.model.calls[c] ?: return false
        return rc.args.any { a ->
            if (a !is ArgBinding.Given || !turns(type(a.expr))) return@any false
            val pl = place(a.expr) ?: return@any false
            val r = rootSymbol(pl)
            if (r is LocalSymbol) changedAfter(r, at, pl.path()) else changesInPlace(pl)
        }
    }

    /** A returned field, global or a parameter's field that changes: the caller's value is that place's, or a copy of it. */
    private fun returned(v: Expr) {
        if (!reporting) return
        val t = type(v)
        if (!turns(t)) return
        val src = placeRead(v) ?: return
        if (outlives(src) && (src.root() !is Place.Param || src.path().isNotEmpty()) && changesInPlace(src)) {
            note(v, Kind.RETURN, "${article(t!!.display())} returned from '${KiraUnparser.text(v)}', which changes")
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A place that outlives a call: one that is no local of the function (a field, a global, a parameter's value). */
    private fun outlives(pl: Place?): Boolean = pl != null && pl.root() !is Place.Local

    /** The values a returned or last expression gives: an if value's arms. */
    private fun results(e: Expr): List<Expr> = when (e) {
        is IfExpr -> listOfNotNull(last(e.thenBranch), last(e.elseBranch)).flatMap { results(it) }
        else -> listOf(e)
    }

    private fun results(fd: FunctionDecl): List<Expr> {
        val inner: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
        fd.def.body?.forEach { s ->
            AstTree.walk(s) { n -> if (n is LambdaExpr) n.def.body?.forEach { b -> AstTree.walk(b) { r -> if (r is ReturnStatement) inner += r } } }
        }
        val out = mutableListOf<Expr>()
        fd.def.body?.forEach { s -> AstTree.walk(s) { n -> if (n is ReturnStatement && n !in inner) out.addAll(results(n.expr)) } }
        return out
    }

    private fun last(b: List<Statement>): Expr? = b.lastOrNull()?.expr

    private fun placeRead(e: Expr): Place? = p.model.readPlace(e)

    private fun aliasingCall(e: Expr): Boolean {
        val c = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return false
        return p.model.calls[c]?.fn?.let { it in aliasing } == true
    }

    protected fun type(e: Expr): KType? = p.model.types[e]

    /** Lists [at] once, as [says] words a site of [kind]. */
    protected fun note(at: ASTNode, kind: Kind, what: String) = list(at, says(kind, what))

    /** Lists [at] once, saying [why]. */
    protected fun list(at: ASTNode, why: String) {
        if (noted.add(at)) hand += at to why
    }

    private fun article(name: String): String = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name

    companion object {
        private val LENDERS = setOf("from", "slice", "view")

        /** Calls whose result iterates the receiver's own elements. */
        private val ITERATED = setOf("view", "from", "slice", "toArr", "entries", "valuesArr")

        private val UNWRAPS = setOf("unwrap", "unwrapOr")

        /** Every body of [p] a change or a flow can be in: functions, methods, initially blocks, field defaults, globals. */
        fun bodies(p: TypedProgram): List<ASTNode> {
            val out = mutableListOf<ASTNode>()
            for (m in p.modules) {
                for (s in m.declarations) {
                    when (s) {
                        is FnSymbol -> s.decl?.let { out += it }
                        is ClassSymbol -> {
                            s.methods.forEach { f -> f.decl?.let { out += it } }
                            s.initially?.let { out.addAll(it) }
                            s.fields.forEach { f -> f.default?.let { out += it } }
                        }
                        is TraitSymbol -> s.methods.forEach { f -> f.decl?.let { out += it } }
                        is GlobalSymbol -> s.init?.let { out += it }
                        else -> {}
                    }
                }
            }
            return out
        }
    }
}
