package net.exoad.kira.dialect

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stage 4's semantic change, found in the old program (typed with the old rules): a List, a Map, a Set (and a value
 * type that becomes Mutable) was a value, copied wherever it went on, and is now shared. A copy is a hand check when
 * both names can see a change after it: the place it came from, or the one it went to, changes in place (a mutator,
 * an element or field written, a `mut` argument, a MutView lent) while the other is still read. A local is followed in
 * its function's order (a loop counts its whole body): one built up and then stored or returned, never used again, is
 * moved, which sharing cannot tell from a copy. A `with` on a value type that becomes Mutable copied it and now edits it
 * in place, and `==` on one compared fields and now compares identity: each is a hand check too.
 */
class Stage4Copies(private val p: TypedProgram, private val facts: Stage4Facts, private val sources: Set<SourceContext>) {
    /** Each hand check: where, and what to look at. */
    val hand = mutableListOf<Pair<ASTNode, String>>()

    /** The variables and fields that change in place somewhere. */
    private val changed: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

    /** The variables and fields whose elements change in place (`xs[0].add(v)`), not only the collection itself. */
    private val changedElements: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

    /** Each local's changes and reads, by where they are in its file. */
    private val changesOf = IdentityHashMap<Symbol, MutableList<Pos>>()
    private val readsOf = IdentityHashMap<Symbol, MutableList<Pos>>()

    /** Each loop's span: a use inside one happens again after any point of it. */
    private val loops = mutableListOf<Pair<Pos, Pos>>()

    /** Functions whose result is (or may be) a place another name holds: a call to one is no fresh value now. */
    private val aliasing: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /** The parameters a function keeps past its call, and whether what keeps one changes. */
    private val escaping = IdentityHashMap<ParamSymbol, Boolean>()

    private val reported: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
    private var reporting = true

    data class Pos(val line: Int, val col: Int) : Comparable<Pos> {
        override fun compareTo(other: Pos): Int = compareValuesBy(this, other, { it.line }, { it.col })
    }

    fun run() {
        val bodies = Stage4Facts.bodies(p).filter { b -> p.locate(b)?.first?.let { it in sources } == true }
        bodies.forEach { b -> AstTree.walk(b) { n -> scan(n) } }
        // A function returning a place another name holds is aliasing, and so is one returning a call to one.
        var grew = true
        while (grew) {
            grew = false
            for (b in bodies) {
                val fd = b as? FunctionDecl ?: continue
                val fn = p.model.declSyms[fd] as? FnSymbol ?: continue
                if (fn in aliasing) continue
                if (results(fd).any { turns(type(it)) && (outlives(placeRead(it)) || aliasingCall(it)) }) {
                    aliasing += fn
                    grew = true
                }
            }
        }
        // The parameters a function keeps, to a fixed point; then the report.
        reporting = false
        repeat(3) { bodies.forEach { b -> AstTree.walk(b) { n -> flows(b, n) } } }
        reporting = true
        bodies.forEach { b -> AstTree.walk(b) { n -> flows(b, n) } }
    }

    // ---- what changes in place, and where each local is used --------------------------------------

    private fun scan(n: ASTNode) {
        when (n) {
            is AssignmentExpr -> place(n.target)?.takeIf { it !is Place.Local && it !is Place.Global && it !is Place.Param }?.let { mark(it, n) }
            is CompoundAssignmentExpr -> place(n.left)?.takeIf { it !is Place.Local && it !is Place.Global && it !is Place.Param }?.let { mark(it, n) }
            is PlaceAssignmentExpr -> place(n.target)?.let { mark(it, n) }
            is FunctionCallExpr -> {
                val rc = p.model.calls[n] ?: return
                val fn = rc.fn
                if (fn?.isMutMethod == true) rc.receiver?.let { r -> place(r)?.let { mark(it, n) } }
                rc.args.forEach { a -> if (a is ArgBinding.Given && a.byRef) place(a.expr)?.let { mark(it, n) } }
                if (fn?.name in LENDERS && (p.model.types[n] as? KType.Nominal)?.sym?.name == "MutView") rc.receiver?.let { r -> place(r)?.let { mark(it, n) } }
            }
            is Identifier -> (p.model.refs[n] as? LocalSymbol)?.let { s -> pos(n)?.let { readsOf.getOrPut(s) { mutableListOf() } += it } }
            is WhileIterationStatement, is ForIterationStatement -> span(n)?.let { loops += it }
            else -> {}
        }
    }

    private fun place(e: Expr): Place? = p.model.readPlace(e)

    private fun mark(pl: Place, at: ASTNode) {
        // A `with`'s copy is a new value, which nothing else holds.
        if ((pl.root() as? Place.Local)?.sym?.builds == true) return
        val path = pl.path()
        val root = rootSymbol(pl)
        root?.let { changed += it }
        if (root is LocalSymbol) pos(at)?.let { changesOf.getOrPut(root) { mutableListOf() } += it }
        path.forEachIndexed { i, s ->
            val f = (s as? PathStep.FieldStep)?.sym ?: return@forEachIndexed
            changed += f
            if (path.drop(i + 1).any { it is PathStep.IndexStep }) changedElements += f
        }
        if (root != null && path.any { it is PathStep.IndexStep }) changedElements += root
    }

    private fun rootSymbol(pl: Place): Symbol? = when (val r = pl.root()) {
        is Place.Local -> r.sym
        is Place.Param -> r.sym
        is Place.Global -> r.sym
        is Place.Field -> r.sym
        else -> null
    }

    private fun pos(n: ASTNode): Pos? = p.locate(n)?.second?.let { Pos(it.lineNumber, it.column) }

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

    private fun changedAfter(s: Symbol, at: Pos): Boolean = after(changesOf[s], at, s)

    private fun usedAfter(s: Symbol, at: Pos): Boolean = after(changesOf[s], at, s) || after(readsOf[s], at, s)

    /** Whether the value at [pl] changes in place somewhere; a field of a call's result is a new value's, which nothing else holds. */
    private fun isChanged(pl: Place?): Boolean {
        if (pl == null) return false
        val root = pl.root()
        if (root is Place.Field && root.receiver == null) return false
        return rootSymbol(pl)?.let { it in changed } == true || pl.path().any { s -> (s as? PathStep.FieldStep)?.sym?.let { it in changed } == true }
    }

    /** Whether an element of the collection at [pl] changes in place. */
    private fun elementsChange(pl: Place?): Boolean {
        if (pl == null) return false
        val last = pl.path().lastOrNull()
        return if (last is PathStep.FieldStep) last.sym in changedElements else rootSymbol(pl)?.let { it in changedElements } == true
    }

    // ---- where a value goes on ------------------------------------------------------------------

    /** Where a value goes: a local, somewhere that outlives the function ([Held.changed]: it changes in place), or a parameter. */
    private sealed interface Dest {
        data class Local(val sym: Symbol, val what: String) : Dest
        data class Held(val changed: Boolean, val what: String) : Dest
        data class Param(val param: ParamSymbol, val fn: FnSymbol) : Dest
    }

    private fun flows(body: ASTNode, n: ASTNode) {
        when (n) {
            is VariableDecl -> n.value?.let { v ->
                val sym = p.model.declSyms[n]
                val into = "into '${n.name.value}'"
                flow(v, if (sym is LocalSymbol) Dest.Local(sym, into) else Dest.Held(sym?.let { it in changed } == true, into))
            }
            is AssignmentExpr -> {
                val target = place(n.target)
                val into = "into ${KiraUnparser.text(n.target)}"
                flow(n.value, if (target is Place.Local) Dest.Local(target.sym, into) else Dest.Held(isChanged(target), into))
            }
            is ObjectInitExpr -> p.model.inits[n]?.fields?.forEach { f ->
                if (f is FieldInit.Given) flow(f.expr, Dest.Held(f.field in changed, "into the field ${f.field.name}"))
            }
            is FunctionCallExpr -> call(n)
            is ReturnStatement -> results(n.expr).forEach { v -> returned(v) }
            is BuilderExpr -> n.elements.forEach { flow(it, Dest.Held(false, "into a new collection")) }
            is LambdaExpr -> captures(n)
            is WithExpr -> {
                val k = facts.key(type(n.receiver))
                if (facts.valueTypeTurned(k) && reporting) note(n, "a `with` on ${k!!.name}: it copied the value and now edits it in place, since ${k.name} is Mutable")
            }
            is BinaryExpr -> if (n.operator == BinaryOp.EQUALS || n.operator == BinaryOp.NOT_EQUAL) {
                val k = facts.key(type(n.leftExpr))
                if (facts.valueTypeTurned(k) && reporting) note(n, "'${KiraUnparser.text(n)}' compared ${k!!.name}'s fields and now compares identity, since ${k.name} is Mutable")
            }
            else -> {}
        }
    }

    private fun call(n: FunctionCallExpr) {
        val rc = p.model.calls[n] ?: return
        val fn = rc.fn ?: return
        rc.args.forEachIndexed { i, a ->
            if (a !is ArgBinding.Given || a.byRef) return@forEachIndexed
            val param = fn.params.getOrNull(i)
            when {
                // A mutator stores what it is given (`xs.add(v)`): into its receiver.
                fn.isMutMethod && (fn.owner as? ClassSymbol)?.kind == ClassKind.MAGIC ->
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
            val later = if (sym is LocalSymbol) changedAfter(sym, at) else sym in changed
            if (turns(t) && later) note(l, "a lambda captures '${sym.name}', ${t!!.display()}: it held a copy, and now shares it with what changes it")
        }
    }

    /**
     * [v] flows on into [dest]: a hand check when one name can see a change the other makes. A local that nothing uses
     * after the flow is moved; a parameter a callee keeps shares the caller's value with what keeps it ([escaping]).
     */
    private fun flow(v: Expr, dest: Dest) {
        val t = type(v)
        if (!turns(t)) return
        val src = placeRead(v)
        val fromCall = aliasingCall(v)
        if (src == null && !fromCall) return
        val at = pos(v) ?: return
        val root = src?.let { rootSymbol(it) }
        val srcParam = (src?.root() as? Place.Param)?.sym?.takeIf { src.path().isEmpty() }
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
            root is LocalSymbol -> {
                // Nothing reads or changes the local after it went on: moved, not shared.
                if (!usedAfter(root, at)) return
                changedAfter(root, at)
            }
            root is ParamSymbol -> false
            else -> isChanged(src)
        }
        if (!srcChanged && !destChanged) return
        // A change to the copy matters only while the source is still read.
        if (!srcChanged && root is LocalSymbol && !usedAfter(root, at)) return
        val into = when (dest) {
            is Dest.Local -> dest.what
            is Dest.Held -> dest.what
            is Dest.Param -> "into the parameter '${dest.param.name}' of ${dest.fn.name}, which keeps it"
        }
        val what = if (src != null) "'${KiraUnparser.text(v)}'" else "the result of '${KiraUnparser.text(v)}', a place of the callee's"
        note(v, "${article(t!!.display())} copied from $what $into, then changed${if (srcChanged) " (the source changes)" else " (the copy changes)"}: it is shared now")
    }

    /** Whether a turned argument of the aliasing call [v] changes after it: the result may be that argument. */
    private fun callArgsChange(v: Expr, at: Pos): Boolean {
        val c = v as? FunctionCallExpr ?: (v as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return false
        val rc = p.model.calls[c] ?: return false
        return rc.args.any { a ->
            if (a !is ArgBinding.Given || !turns(type(a.expr))) return@any false
            val pl = place(a.expr) ?: return@any false
            val r = rootSymbol(pl)
            if (r is LocalSymbol) changedAfter(r, at) else isChanged(pl)
        }
    }

    /** A returned field, global or parameter's element that changes: the caller's copy is now that place. */
    private fun returned(v: Expr) {
        if (!reporting) return
        val t = type(v)
        if (!turns(t)) return
        val src = placeRead(v) ?: return
        if (outlives(src) && src.root() !is Place.Param && isChanged(src)) {
            note(v, "${article(t!!.display())} returned from '${KiraUnparser.text(v)}', which changes: the caller held a copy and now shares it")
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

    private fun type(e: Expr): KType? = p.model.types[e]

    /** A value whose copies were copies and are now shared: a stdlib collection, a value type that becomes Mutable, or one holding either. */
    private fun turns(t: KType?, seen: MutableSet<KType> = HashSet()): Boolean {
        val n = t as? KType.Nominal ?: return false
        if (!seen.add(n)) return false
        val k = facts.key(n)
        if (k != null && (k in COLLECTIONS || facts.valueTypeTurned(k))) return true
        val name = (n.sym as? ClassSymbol)?.takeIf { it.kind == ClassKind.MAGIC }?.name ?: return false
        return (name == "Maybe" || name == "Result" || name.startsWith("Tuple")) && n.typeArgs().any { turns(it, seen) }
    }

    private fun note(at: ASTNode, why: String) {
        if (reported.add(at)) hand += at to why
    }

    private fun article(name: String): String = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name

    companion object {
        private val LENDERS = setOf("from", "slice", "view")

        /** The stdlib's collections, copied as values until stage 4. */
        val COLLECTIONS = setOf(
            TypeKey("kira:collections", "List"), TypeKey("kira:collections", "Map"), TypeKey("kira:collections", "Set"),
            TypeKey("kira:collections", "Stack"), TypeKey("kira:collections", "Queue"),
            TypeKey("kira:collections", "Deque"), TypeKey("kira:core", "StrBuf"),
        )
    }
}
