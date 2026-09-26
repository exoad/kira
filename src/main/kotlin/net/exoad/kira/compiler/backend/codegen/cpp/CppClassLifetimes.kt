package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Where the C++ lowering keeps a reference across code that may overwrite or free what it
 * refers to, decided once per typed program. Convergence policy rule 1: what the lowering cannot
 * prove safe is guarded or refused, and never emitted as it is.
 *
 * A class is a shared object (D11). Any number of handles reach it, and a callee may reach it
 * through a handle the caller cannot see (`b: Log = a`, then `a.append(b.lines[0])`). The
 * lowering holds three kinds of reference into such storage.
 *
 * 1. A parameter C++ takes by `const&` (design table 5.1). Bound to a field or an element of an
 *    object, it reads whatever the callee's own effects leave there, or freed memory:
 *    `lines.add(...)` reallocates the List that `s` points into, and `h.clear()` frees the Item
 *    whose label `s` is (measured on g++: `std::bad_alloc`, freed heap bytes). A parameter that
 *    some effect of the body may reach before the body reads it is copied at entry
 *    ([Guard.snapshots]). The copy is the value the caller passed, as Kira's parameters are
 *    values (`c.set(d.name)` with `set` renaming `c` gives `a`, as the C and JS backends do).
 *    A template `Fx` parameter is copied the same way. One a view the call hands back may
 *    point into ([Guard.lent]) stays the caller's reference, since the copy would die at
 *    return under the view, and a read of it that the reference cannot make right is refused.
 * 2. `this` in a method. Reached through a field, an element or `unwrap()`, the object can lose
 *    its last owner while its method runs (`t.kids[0].leave()` with `leave` calling
 *    `tree.clear()`). A class method that reads its receiver after an effect that may free an
 *    object, or keeps a reference into it while such an effect runs (its field passed `mut`,
 *    the receiver of a call whose arguments free it: `labels.add(h.take())`), holds itself for
 *    the call (`weak_from_this().lock()`, [Guard.holdsThis]), and the root of its chain then
 *    derives `kira::Shared`. A trait's default body cannot hold itself (a trait has no
 *    `shared_from_this`), so it is refused. A parameter whose object is used the same way is
 *    copied at entry, which holds that object.
 * 3. A range-for over a field or an element of an object: an effect in the loop body that may
 *    change that storage invalidates the iterators (`for s in b.items { c.grow() }` with
 *    `c = b`, measured: freed heap bytes), and a range reached from a temporary through a
 *    handle is freed before the first iteration (`for s in makeItem().labels`). Refused
 *    ([refusals]), as are a `mut` argument that names such storage when the call may change
 *    it, or lies inside another `mut` argument of the call, a call through an `Fx` such storage
 *    holds while the code it runs may replace it, and a lambda's `const&` parameter read after
 *    an effect that may reach it: each of those lowerings is the statement part's, and each
 *    diagnostic names the local copy that makes the program safe. A field's default is checked
 *    as a body is.
 *
 * Effects are decided over the whole program ([summaries]): the fields, globals and `mut`
 * parameters each body may overwrite, with the types written there, and whether it may free an
 * object (drop a handle, or run a `finally`). A call's effects are its callee's: every body of
 * the name for a virtual or trait call, and every lambda and function value for a call through
 * an `Fx`. What C++ supplies (a C++ override of a trait method, an `Fx` built in C++, an
 * `@_extern`, a bodyless `pub` prototype a C++ file defines) is taken to leave Kira's objects
 * alone during the call: the FFI contract, recorded as an open decision in
 * `docs/cpp-known-issues/w2-4-emit-oop.md`.
 */
class CppClassLifetimes(private val program: TypedProgram) {
    private val model = program.model

    // ---- effects ------------------------------------------------------------------------------------

    /**
     * One write. [at] is the field, global or `mut` parameter whose storage it changes, or null
     * where that is unknown (an element reached through a view). [type] is the type of what
     * was written. [inside] is true when the write lands inside [at]'s storage (an element of a
     * container field) rather than replacing all of it.
     */
    internal data class Write(val at: Symbol?, val type: KType, val inside: Boolean)

    /** What running some code may do to storage it does not own. */
    class Effects {
        /** It may free an object: drop what may be the last handle to one, or run code that may. */
        var releases = false

        /** It may overwrite anything (a call the analysis cannot see into). */
        var any = false

        internal val writes = LinkedHashSet<Write>()

        val isEmpty: Boolean get() = !releases && !any && writes.isEmpty()

        /** Adds [other]; true when this grew. */
        fun addAll(other: Effects): Boolean {
            var changed = false
            if (other.releases && !releases) {
                releases = true
                changed = true
            }
            if (other.any && !any) {
                any = true
                changed = true
            }
            if (writes.addAll(other.writes)) {
                changed = true
            }
            return changed
        }

        fun copy(): Effects = Effects().also { it.addAll(this) }

        companion object {
            fun unknown(): Effects = Effects().also {
                it.releases = true
                it.any = true
            }

            fun release(): Effects = Effects().also { it.releases = true }
        }
    }

    // ---- the program ----------------------------------------------------------------------------------

    private val declarations: List<Symbol> = program.modules.flatMap { it.declarations }

    /** The classes this program defines itself: shared objects held by `kira::Rc`. */
    private val kiraClasses: List<ClassSymbol> = declarations.filterIsInstance<ClassSymbol>().filter { it.kind == ClassKind.CLASS && it.foreign == null }

    /** Every body-carrying method of the program's classes, structs and traits, by name: what a virtual or trait call may run. */
    private val methodsNamed: Map<String, List<FnSymbol>> = declarations.flatMap { sym ->
        when (sym) {
            is ClassSymbol -> sym.methods
            is TraitSymbol -> sym.methods
            else -> emptyList()
        }
    }.filter { it.body != null }.groupBy { it.name }

    /**
     * Every type an expression or a written type of the program's own modules has. The
     * standard library's declarations are not the program's use: kira:sync declares Thread and
     * kira:core `Ref<T>` whether or not anything holds one, and read as uses they made every
     * program look like it drops threads and holds any type in a `Ref` (the proto golden's
     * `Str` parameters were copied for it).
     */
    private val typesInUse: List<KType> = program.modules.filter { !it.isStdlib }.flatMap { m ->
        val out = mutableListOf<KType>()
        runCatching { m.source.ast }.getOrNull()?.let { ast ->
            AstTree.walk(ast) { node ->
                when (node) {
                    is Type -> model.typeOf(node)?.let { out += it }
                    is Expr -> model.typeOrNull(node)?.let { out += it }
                    else -> {}
                }
            }
        }
        out
    }.distinct()

    /** A system class (kira:sync's Thread, Mutex, ...) is in use: dropping one may join a thread, which runs any code. */
    private val systemInUse: Boolean = typesInUse.any { t -> mentions(t) { isSystem(it) } }

    /** Some object's destruction runs code: a class with a `finally`, or a system class. */
    val hasFinally: Boolean = systemInUse || kiraClasses.any { it.finally != null }

    /** One body the summaries cover: a function or method, a lambda, an `initially` or a `finally`. */
    private class Body(val statements: List<Statement>, val fn: FnSymbol?, val owner: TypeSymbol?)

    private val bodies = mutableListOf<Body>()
    private val lambdas = mutableListOf<LambdaExpr>()
    private val fnValues: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /** Each body's effects, keyed by its statement list (identity): a fixpoint over the call graph. */
    private val summaries = IdentityHashMap<List<Statement>, Effects>()

    /** What a call through an `Fx` value may run: every lambda and every function used as a value. */
    private var fxPool = Effects()

    /**
     * What freeing an object may run: every class's `finally` (and anything, when a system
     * class is in use: dropping a thread joins it). A release carries it, so a `finally` that
     * clears a list is seen by a loop over that list.
     */
    private var finallyPool = Effects()

    /** The types a class object, a `Ref`, or an `Fx` held by one can hold by value: what a `const&` parameter can point into. */
    private val heldTypes = HashSet<KType>()
    private var heldAll = false

    private fun collectBodies() {
        for (sym in declarations) {
            when (sym) {
                is FnSymbol -> sym.body?.let { bodies += Body(it, sym, sym.owner) }
                is ClassSymbol -> {
                    sym.methods.forEach { m -> m.body?.let { bodies += Body(it, m, sym) } }
                    sym.initially?.let { bodies += Body(it, null, sym) }
                    sym.finally?.let { bodies += Body(it, null, sym) }
                }
                is TraitSymbol -> sym.methods.forEach { m -> m.body?.let { bodies += Body(it, m, sym) } }
                else -> {}
            }
        }
        for (m in program.modules) {
            val ast = runCatching { m.source.ast }.getOrNull() ?: continue
            val callees: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
            AstTree.walk(ast) { node ->
                if (node is FunctionCallExpr) {
                    callees.add(node.name)
                    (node.name as? MemberAccessExpr)?.let { callees.add(it.member) }
                }
                if (node is MemberAccessExpr && node.member is FunctionCallExpr) {
                    callees.add((node.member as FunctionCallExpr).name)
                }
            }
            AstTree.walk(ast) { node ->
                when (node) {
                    is LambdaExpr -> {
                        lambdas += node
                        node.def.body?.let { bodies += Body(it, null, null) }
                    }
                    is Identifier -> if (node !in callees) {
                        (model.symbolOf(node) as? FnSymbol)?.let { fnValues.add(it) }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectHeld() {
        val roots = mutableListOf<KType>()
        kiraClasses.forEach { c -> c.fields.forEach { roots += it.type } }
        typesInUse.forEach { t -> collectRefArgs(t, roots) }
        if (systemInUse) {
            heldAll = true
        }
        for (r in roots) {
            addHeld(r)
        }
        // An Fx an object holds holds its captures, of any type, and the lambda's body can hand
        // one on by const&; a type parameter can be any type.
        if (heldTypes.any { it is KType.Fn || hasParam(it) }) {
            heldAll = true
        }
    }

    private fun collectRefArgs(t: KType, out: MutableList<KType>) {
        when (t) {
            is KType.Nominal -> {
                val sym = t.sym
                if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC && sym.name == "Ref") {
                    t.typeArgs().firstOrNull()?.let { out += it }
                }
                t.typeArgs().forEach { collectRefArgs(it, out) }
            }
            is KType.Fn -> {
                t.params.forEach { collectRefArgs(it.type, out) }
                collectRefArgs(t.ret, out)
            }
            else -> {}
        }
    }

    private fun addHeld(t: KType) {
        if (heldTypes.add(t)) {
            nested(t).forEach { addHeld(it) }
        }
    }

    /** The summaries, to a fixpoint: each body's effects with its callees' as they stand, until nothing grows. */
    private fun solve() {
        bodies.forEach { summaries[it.statements] = Effects() }
        var changed = true
        while (changed) {
            changed = false
            for (b in bodies) {
                val found = Walker(summary = true, fn = b.fn, owner = b.owner).run(b.statements).all
                if (summaries.getValue(b.statements).addAll(found)) {
                    changed = true
                }
            }
            val pool = Effects()
            lambdas.forEach { l -> l.def.body?.let { summaries[it]?.let(pool::addAll) } }
            fnValues.forEach { f -> f.body?.let { summaries[it]?.let(pool::addAll) } }
            if (fxPool.addAll(pool)) {
                changed = true
            }
            val finals = if (systemInUse) Effects.unknown() else Effects()
            kiraClasses.forEach { c -> c.finally?.let { summaries[it]?.let(finals::addAll) } }
            if (finallyPool.addAll(finals)) {
                changed = true
            }
        }
    }

    /** A release: an object may be freed, and what its `finally` does may run ([finallyPool]). */
    private fun release(): Effects = Effects.release().also { it.addAll(finallyPool) }

    // ---- queries ---------------------------------------------------------------------------------------

    /**
     * What a definition of [fn] guards before its body. [snapshots] are the parameters some
     * effect of the body may reach before the body reads them, of a type an object can hold,
     * whichever way C++ passes them (the caller keeps those it passes by reference), less the
     * [lent] ones. [holdsThis] is true when the body reads its receiver after an effect that
     * may free an object, or keeps a reference into it while one runs (a `mut` argument, the
     * receiver of a call whose arguments do); [releasedBy] is the first such effect.
     *
     * [lent] are the parameters a view the call may hand back points into ([lentParams]): a
     * copy at entry would be what the view points into, freed at return (`return xs.view()`
     * after `b.grow()` printed freed heap bytes on g++ and was a heap-use-after-free under
     * MSVC ASan), so each stays the caller's reference, and what that cannot make safe is in
     * [lentRefusals].
     */
    class Guard(
        val snapshots: List<ParamSymbol>,
        val holdsThis: Boolean,
        val releasedBy: ASTNode?,
        val lent: List<ParamSymbol> = emptyList(),
        internal val lentRefusals: List<Pair<ASTNode, String>> = emptyList(),
    ) {
        companion object {
            val NONE = Guard(emptyList(), false, null)
        }
    }

    private val guards = IdentityHashMap<FnSymbol, Guard>()

    fun guard(fn: FnSymbol): Guard = guards.getOrPut(fn) {
        val body = fn.body ?: return@getOrPut Guard.NONE
        val (lent, viewReads) = lentParams(fn, body)
        val w = Walker(summary = false, fn = fn, owner = fn.owner, tracked = fn.params, viewReads = viewReads).run(body)
        val snapshots = fn.params.filter { p -> !p.byRef && p in w.paramHits && holdable(p.type) && p !in lent }
        val refused = lent.filter { holdable(it.type) }.mapNotNull { p -> lentRefusal(p, w) }
        Guard(snapshots, w.thisReleasedBy != null, w.thisReleasedBy, lent.toList(), refused)
    }

    /**
     * Why the lent parameter [p] cannot stay the caller's reference, when it cannot: the body
     * reads it after an effect that may free or move what it names (the reference would read
     * freed memory), or reads its value, not only a view of it, after an effect that may change
     * it (Kira's parameter is the value passed, and the reference would read the change).
     */
    private fun lentRefusal(p: ParamSymbol, w: Walker): Pair<ASTNode, String>? {
        val t = p.type.display()
        val lends = "a view the function hands back may point into it, so C++ keeps ${p.name} as a reference to what the caller passed rather than a copy"
        w.invalidHits[p]?.let { (read, cause) ->
            return read to "the parameter ${p.name}, read after ${describe(cause)}, which may free or move what it names: $lends, and the read would see freed memory. " +
                "Read ${p.name} before ${describe(cause)}, or lend nothing from it (copy it into a local, `v: $t = ${p.name}`, and use v)"
        }
        w.valueHits[p]?.let { (read, cause) ->
            return read to "the parameter ${p.name}, read after ${describe(cause)}, which may change it: $lends, and the read would see the change where Kira's parameter is the value passed. " +
                "Read what you need from ${p.name} before ${describe(cause)}, or lend nothing from it (copy it into a local, `v: $t = ${p.name}`, and use v)"
        }
        return null
    }

    /**
     * The parameters of [fn] a view the call may hand back can point into, with the reads of
     * them that take such a view: one C++ takes by `const&`, of a type a view can point into
     * (it owns a buffer: a `Str`, a container, a struct holding one), that the body takes a
     * view of in place (a conversion to a `View`, the receiver or an argument of a call whose
     * result or `mut` argument can hold a view), when a view may leave the call: its return
     * type or a `mut` parameter can hold one, the body throws, builds a lambda or hands a view
     * to C++, or the program has storage that may keep one (a global, a class field of a view,
     * an `Fx` or a type parameter). W2.3 lends such an argument from the caller's own storage;
     * a copy would be a local that dies at return.
     */
    private fun lentParams(fn: FnSymbol, body: List<Statement>): Pair<Set<ParamSymbol>, Set<ASTNode>> {
        val candidates = identitySet<ParamSymbol>()
        fn.params.filter { !it.byRef && passedByReference(it.type) && ownsBuffer(it.type) }.forEach { candidates.add(it) }
        if (candidates.isEmpty()) {
            return emptySet<ParamSymbol>() to emptySet()
        }
        val viewReads = identitySet<ASTNode>()
        var escapes = programKeepsViews || pointsInto(fn.ret) || fn.params.any { it.byRef && pointsInto(it.type) }
        fun mark(e: Expr?) {
            val root = e?.let(::rootIdentifier) ?: return
            val p = model.symbolOf(root) as? ParamSymbol ?: return
            val place = model.place(e)
            if (p in candidates && place != null && !throughReference(place)) {
                viewReads.add(root)
            }
        }
        body.forEach { s ->
            AstTree.walk(s) { node ->
                when (node) {
                    is LambdaExpr, is ThrowExpr -> escapes = true
                    is FunctionCallExpr -> model.call(node)?.let { rc ->
                        val given = rc.args.filterIsInstance<ArgBinding.Given>()
                        if (pointsInto(rc.returnType) || given.any { it.byRef && pointsInto(model.typeOrNull(it.expr) ?: KType.Error) }) {
                            mark(rc.receiver)
                            given.forEach { mark(it.expr) }
                        }
                        if (rc.kind == CallKind.EXTERN && given.any { pointsInto(model.typeOrNull(it.expr) ?: KType.Error) }) {
                            escapes = true
                        }
                    }
                    else -> {}
                }
                if (node is Expr && model.coercion(node) is Coercion.ToView) {
                    mark(node)
                }
            }
        }
        if (!escapes || viewReads.isEmpty()) {
            return emptySet<ParamSymbol>() to emptySet()
        }
        val lent = identitySet<ParamSymbol>()
        viewReads.forEach { r -> (model.symbolOf(r as Identifier) as? ParamSymbol)?.let { lent.add(it) } }
        return lent to viewReads
    }

    /** The variable an expression reads storage from: `xs` of `xs`, `xs.a`, `xs[0].a`; null for anything else (a call's result, a construction). */
    private fun rootIdentifier(e: Expr): Identifier? = when (e) {
        is MemberAccessExpr -> if (e.member is FunctionCallExpr) null else rootIdentifier(e.origin)
        is ArrayIndexExpr -> rootIdentifier(e.originExpr)
        is Identifier -> e
        else -> null
    }

    /**
     * The program's own modules have storage that may keep a view past the call that made it:
     * a global or a class field whose type can hold a view, an `Fx` (whose lambda may capture
     * one), or a type parameter (a `Box<View<Int32>>`). D5 refuses a view in a class field,
     * and W2.5 checks that; this does not rely on it.
     */
    private val programKeepsViews: Boolean = program.modules.filter { !it.isStdlib }.flatMap { it.declarations }.any { sym ->
        when (sym) {
            is GlobalSymbol -> keepsViews(sym.type)
            is ClassSymbol -> sym.fields.any { keepsViews(it.type) }
            else -> false
        }
    }

    private fun keepsViews(t: KType): Boolean = pointsInto(t) || hasParam(t) || mentionsFn(t)

    private fun mentionsFn(t: KType): Boolean = when (t) {
        is KType.Fn -> true
        is KType.Nominal -> t.typeArgs().any { mentionsFn(it) }
        else -> false
    }

    /** Whether a `const&` parameter of type [t] can be bound to storage an object holds: some class, `Ref` or held `Fx` holds a [t] by value. */
    fun holdable(t: KType): Boolean = heldAll || hasParam(t) || t in heldTypes

    /**
     * What keeps an object alive while a body runs, for [releasable]: `this` ([thisKept]: a
     * class method that holds itself, an `initially` or `finally`, whose object no release can
     * free, or a struct's method, KI-7), and the parameters copied at entry ([keptParams]),
     * each a handle of the body's own.
     */
    private class Keeps(val thisKept: Boolean, val keptParams: Set<ParamSymbol>)

    /** Storage a view may point into: its place, its type, and the expression that names it. */
    private class ViewSource(val place: Place, val type: KType, val expr: Expr)

    /**
     * A view in use while an effect may move or free what it points into: the view local [view]
     * read at [at] after [cause], or (with [callee]) the view [at] handed to a call that may.
     */
    private class ViewHit(val at: ASTNode, val view: String?, val of: Expr, val cause: ASTNode, val callee: String?)

    private fun keepsOf(fn: FnSymbol?, owner: TypeSymbol?): Keeps {
        if (fn == null) {
            return Keeps(thisKept = true, keptParams = emptySet())
        }
        val g = guard(fn)
        val kept = identitySet<ParamSymbol>()
        g.snapshots.forEach { kept.add(it) }
        return Keeps(thisKept = g.holdsThis || isStruct(owner), keptParams = kept)
    }

    /**
     * What [nodes] (a function's, method's, `initially`'s or `finally`'s body, or a field's
     * default, with every lambda inside it) do that no guard makes safe, each with the message
     * that refuses it: a range-for over storage an object holds whose body may change that
     * storage, or over storage only a temporary keeps alive; a `mut` argument naming such
     * storage that the call may change or free; a call through an `Fx` such storage holds while
     * the code it runs may replace or free it; a lambda's `const&` parameter read after an
     * effect that may reach it (a lambda is the statement part's, and copies nothing at entry);
     * and a lent parameter the body reads after an effect that may reach it ([Guard.lent]).
     * [fn] and [owner] are the body's function (null for `initially`, `finally` and a field's
     * default) and type.
     */
    fun refusals(nodes: List<ASTNode>, fn: FnSymbol?, owner: TypeSymbol?): List<Pair<ASTNode, String>> {
        val out = mutableListOf<Pair<ASTNode, String>>()
        val keeps = keepsOf(fn, owner)
        if (fn != null && fn.body === nodes) {
            out += guard(fn).lentRefusals
        }
        val bodies = mutableListOf(nodes)
        nodes.forEach { s ->
            AstTree.walk(s) { node ->
                when (node) {
                    is ForIterationStatement -> loopRefusal(node, fn, owner, keeps)?.let { out += node to it }
                    is FunctionCallExpr -> {
                        out += mutArgRefusals(node, keeps).map { node to it }
                        fxCallRefusal(node, keeps)?.let { out += node to it }
                    }
                    is LambdaExpr -> {
                        out += lambdaParamRefusals(node, owner)
                        node.def.body?.let { bodies += it }
                    }
                    else -> {}
                }
            }
        }
        // The views each body keeps, a lambda's own among them (it runs later, and its parameters are the statement part's references).
        val seen: MutableSet<ASTNode> = identitySet()
        bodies.forEachIndexed { i, b ->
            val w = Walker(summary = false, fn = if (i == 0) fn else null, owner = owner, views = if (i == 0) keeps else Keeps(keeps.thisKept, emptySet())).run(b)
            w.viewHits.filter { seen.add(it.at) }.forEach { h -> out += h.at to viewRefusal(h) }
        }
        return out
    }

    private fun viewRefusal(h: ViewHit): String {
        val of = KiraUnparser.text(h.of)
        val type = model.typeOrNull(h.of)?.display() ?: "its type"
        // The body's own local is copied by taking the view later; storage outside the body by viewing a local copy of it.
        val own = model.place(h.of)?.root() is Place.Local
        val copy = "a view of a local copy (`items: $type = $of`, then a view of items)"
        return if (h.callee != null) {
            "the view of $of handed to ${h.callee}, which may change or free $of while the view is in use: C++ passes a pointer into that storage, which the call may move. " +
                (if (own) "Take the view after the call, or copy $of first" else "Pass $copy")
        } else {
            "the view ${h.view}, taken of $of, read after ${describe(h.cause)}, which may change or free $of: C++ keeps the view pointing into that storage, which the effect may move. " +
                "Take the view after ${describe(h.cause)}" + (if (own) "" else ", or take $copy")
        }
    }

    /**
     * A call through an `Fx` a field or an element holds (`f()`, `b.f()`), while what a call
     * through an `Fx` may run may replace or free that storage: the C++ call runs the
     * `kira::Fn` where it lives, and the closure it replaces is destroyed under its own body
     * (a lambda assigning `b.f` printed freed heap bytes for its capture on g++). A parameter
     * bound to such an `Fx` is copied at entry instead ([guard]).
     */
    private fun fxCallRefusal(n: FunctionCallExpr, keeps: Keeps): String? {
        val rc = model.call(n) ?: return null
        if (rc.kind != CallKind.FN_VALUE) {
            return null
        }
        val callee = n.name
        val place = model.place(callee) ?: return null
        if (kindOf(place) != PlaceKind.SHARED) {
            return null
        }
        val type = model.typeOrNull(callee) ?: return null
        if (!reaches(fxPool, place, type, keeps)) {
            return null
        }
        val text = KiraUnparser.text(callee)
        return "the call through $text, an Fx an object holds, while the function it runs may replace or free it: C++ runs the function where it is stored. " +
            "Copy it into a local first (`g: ${type.display()} = $text`, then g(...))"
    }

    /** A lambda's parameters C++ takes by `const&` (the statement part spells them in the parameter column) read after an effect that may reach them. */
    private fun lambdaParamRefusals(l: LambdaExpr, owner: TypeSymbol?): List<Pair<ASTNode, String>> {
        val body = l.def.body ?: return emptyList()
        val params = l.def.parameters.mapNotNull { model.declSymbol(it) as? ParamSymbol }.filter { !it.byRef && passedByReference(it.type) && holdable(it.type) }
        if (params.isEmpty()) {
            return emptyList()
        }
        val w = Walker(summary = false, fn = null, owner = owner, tracked = params).run(body)
        return params.filter { it in w.paramHits }.map { p ->
            val decl = l.def.parameters.firstOrNull { model.declSymbol(it) === p } ?: l
            decl to "the lambda parameter ${p.name}, which C++ takes by const&, read after an effect of the lambda's body that may change or free what it names: " +
                "copy it into a local at the top of the lambda (`v: ${p.type.display()} = ${p.name}`) and read that"
        }
    }

    /** Whether the parameter column passes a [t] by `const&`: anything but a scalar, an enum, a view and a pointer (CppTypeSpeller.byValue). */
    private fun passedByReference(t: KType): Boolean = when (t) {
        is KType.Scalar, KType.Void, KType.Never, KType.NullT, KType.Error -> false
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> false
            is ClassSymbol -> !(sym.kind == ClassKind.OPAQUE || (sym.kind == ClassKind.MAGIC && sym.name in BY_VALUE_MAGIC))
            else -> true
        }
        else -> true
    }

    private fun loopRefusal(s: ForIterationStatement, fn: FnSymbol?, owner: TypeSymbol?, keeps: Keeps): String? {
        val target = s.forIterationExpr.target
        temporaryRange(target)?.let { base ->
            val text = KiraUnparser.text(target)
            val baseText = KiraUnparser.text(base)
            val baseType = model.typeOrNull(base)?.display() ?: "the type it has"
            return "the loop over $text, storage only the temporary $baseText keeps alive: a C++ range-for keeps its range alive, not what the range is reached through, " +
                "so that temporary is destroyed before the first iteration. Store it in a local first (`v: $baseType = $baseText`, then loop over v's storage)"
        }
        val place = model.place(target) ?: return null
        if (kindOf(place) != PlaceKind.SHARED) {
            return null
        }
        val type = model.typeOrNull(target) ?: return null
        val w = Walker(summary = false, fn = fn, owner = owner).run(s.body)
        val cause = w.events.firstOrNull { (e, _) -> reaches(e, place, type, keeps) }?.second ?: return null
        val text = KiraUnparser.text(target)
        return "the loop over $text, storage an object holds, while its body may change or free it (${describe(cause)}): " +
            "a C++ range-for keeps iterators into that storage. Iterate over a local copy instead (`items: ${type.display()} = $text`, then `for ... in items`)"
    }

    /**
     * The temporary a loop's range lies inside, when only that temporary keeps the range alive:
     * the range is reached from a call's result or a construction through a handle (a field of
     * a class object), an element, or a runtime accessor (`unwrap()`, `get(i)`), each of which
     * C++ spells as a reference into what it is reached from. `for s in makeItem().labels` is
     * `for(const kira::Str& s : makeItem()->labels)`, and the `kira::Rc` temporary dies at the
     * end of the range's initializer, freeing the Item before the first iteration (g++ printed
     * freed heap bytes; MSVC ASan: heap-use-after-free). A temporary that is the range itself,
     * or a struct's field reached by value, is kept alive by the range-for's reference.
     */
    private fun temporaryRange(target: Expr): Expr? {
        var e: Expr = target
        var intoIt = false
        while (true) {
            val call = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr
            if (call != null) {
                // A runtime accessor answers with one of its receiver's type arguments (`unwrap()`, `get(i)`), a reference into
                // the receiver; anything else (`toArr()`, a Kira method's result) is a fresh value the range-for keeps alive.
                val rc = model.call(call)
                val receiver = rc?.receiver
                val receiverType = receiver?.let { model.typeOrNull(it) } as? KType.Nominal
                val result = model.typeOrNull(e) ?: rc?.returnType
                if (rc?.kind != CallKind.MAGIC || receiver == null || receiverType == null || receiverType.typeArgs().none { it == result }) {
                    break
                }
                intoIt = true
                e = receiver
            } else if (e is MemberAccessExpr) {
                val origin = model.typeOrNull(e.origin)
                if (origin is KType.Nominal && isReferenceOwner(origin.sym)) {
                    intoIt = true
                }
                e = e.origin
            } else if (e is ArrayIndexExpr) {
                intoIt = true
                e = e.originExpr
            } else {
                break
            }
        }
        if (!intoIt || e is Identifier || e is ThisExpr || model.place(e) != null) {
            return null
        }
        return e
    }

    private fun mutArgRefusals(n: FunctionCallExpr, keeps: Keeps): List<String> {
        val rc = model.call(n) ?: return emptyList()
        val given = rc.args.filterIsInstance<ArgBinding.Given>()
        val name = rc.fn?.name ?: KiraUnparser.text(n.name)
        val out = mutableListOf<String>()
        overlappingMutArgs(n, rc)?.let { out += it }
        val shared = given.filter { a -> a.byRef && model.place(a.expr)?.let { kindOf(it) == PlaceKind.SHARED } == true }
        if (shared.isEmpty()) {
            return out
        }
        // What runs while the callee writes through the argument: the callee. A sibling argument's effect comes before the
        // binding, since the statement part locates a mut argument's place after its impure siblings (D33).
        val during = Walker(summary = true, fn = null, owner = null).calleeEffects(rc)
        shared.forEachIndexed { i, a ->
            val place = model.place(a.expr)!!
            val type = model.typeOrNull(a.expr) ?: KType.Error
            val text = KiraUnparser.text(a.expr)
            val other = shared.drop(i + 1).firstOrNull { b -> overlaps(type, model.typeOrNull(b.expr) ?: KType.Error) }
            when {
                reaches(during, place, type, keeps) -> out += "the mut argument $text, storage an object holds, passed to $name, which may change or free that object while it writes " +
                    "through the argument: pass a local and store it back (`v: ${type.display()} = $text`, then $name(mut v), then `$text = v`)"
                other != null -> out += "the mut arguments $text and ${KiraUnparser.text(other.expr)} of $name, storage objects hold that may be one place through two handles: " +
                    "pass locals and store them back"
            }
        }
        return out
    }

    /**
     * A `mut` argument that lies inside what another `mut` argument of the same call names
     * (`f(mut x, mut x.label)`, `f(mut xs, mut xs[0])`): the callee may replace or resize the
     * outer one while C++ keeps a reference into it, so the inner reference would point into a
     * freed object or a moved buffer. Exclusivity (D37, W2.5) refuses the same pair; this does
     * not rely on it. A `mut` argument of a call among the arguments runs before the binding
     * (the statement part locates a `mut` argument's place after its impure siblings, D33).
     */
    private fun overlappingMutArgs(n: FunctionCallExpr, rc: ResolvedCall): String? {
        val kept = rc.args.filterIsInstance<ArgBinding.Given>().filter { it.byRef }.mapNotNull { a -> model.place(a.expr)?.let { a.expr to it } }
        for ((ke, kp) in kept) {
            val outer = kept.firstOrNull { (we, wp) -> we !== ke && wp.overlaps(kp) && wp.path().size < kp.path().size } ?: continue
            val name = rc.fn?.name ?: KiraUnparser.text(n.name)
            return "the mut argument ${KiraUnparser.text(ke)} of $name lies inside ${KiraUnparser.text(outer.first)}, which is passed mut in the same call: " +
                "the callee may replace or resize it while C++ keeps a reference into it. Pass a local and store it back"
        }
        return null
    }

    /** [cause] as a diagnostic names it: `the call to clear`, `the assignment `next = null``. */
    fun describe(cause: ASTNode): String = when (cause) {
        is FunctionCallExpr -> "the call to ${model.call(cause)?.fn?.name ?: KiraUnparser.text(cause.name)}"
        is ObjectInitExpr -> "the construction of ${model.init(cause)?.cls?.name ?: "an object"}"
        is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr -> "the assignment `${KiraUnparser.text(cause)}`"
        else -> "`${KiraUnparser.text(cause)}`"
    }

    // ---- places -----------------------------------------------------------------------------------------

    private enum class PlaceKind {
        /** A local of the body, or storage inside one by value: nothing else reaches it. */
        LOCAL,

        /** A `mut` parameter's place, or storage inside it by value: the caller's. */
        MUT_PARAM,

        /** A field of an object, a global, or anything reached through a handle or a view. */
        SHARED,
    }

    private fun kindOf(p: Place): PlaceKind {
        if (throughReference(p)) {
            return PlaceKind.SHARED
        }
        return when (val r = p.root()) {
            is Place.Local -> PlaceKind.LOCAL
            // A value parameter is never written; read in place, it is the caller's (a snapshot, when a guard copies it).
            is Place.Param -> if (r.sym.byRef) PlaceKind.MUT_PARAM else PlaceKind.LOCAL
            else -> PlaceKind.SHARED
        }
    }

    /** A step of [p] goes through a handle (a class, trait, `Ref`, pointer) or a view, or starts from a temporary object. */
    private fun throughReference(p: Place): Boolean = when (p) {
        is Place.Field -> isReferenceOwner(p.sym.owner) || (p.receiver?.let(::throughReference) ?: true)
        is Place.Index -> p.kind == IndexKind.VIEW || p.kind == IndexKind.MUT_VIEW || throughReference(p.container)
        is Place.This -> true
        else -> false
    }

    /** The field, global or parameter whose storage [p] lies in; null where that is unknown (through a view, or the whole receiver). */
    private fun location(p: Place): Symbol? = when (p) {
        is Place.Field -> p.sym
        is Place.Index -> if (p.kind == IndexKind.VIEW || p.kind == IndexKind.MUT_VIEW) null else location(p.container)
        is Place.Global -> p.sym
        is Place.Param -> p.sym
        is Place.Local -> p.sym
        is Place.This -> null
    }

    /**
     * Whether the storage [place] names may be freed by a release: it lies in an object reached
     * through a field, an element or a global, not one field away from a handle the body itself
     * keeps for as long as it runs ([keeps]): a local (another `mut` argument that could
     * replace it is [overlappingMutArgs]'s), a parameter copied at entry, or `this` in a method
     * that holds itself. A parameter or `this` the body only uses before or during the call
     * keeps nothing (`setAfter(h, mut label)` with `setAfter` clearing `h` wrote into the freed
     * Item: g++ segfaulted, MSVC ASan reported a heap-use-after-free); the walk counts such a
     * use as one after the call ([Walker.call]), which copies the parameter or holds `this`.
     */
    private fun releasable(place: Place, keeps: Keeps): Boolean = when (place) {
        is Place.Index -> releasable(place.container, keeps)
        is Place.Field -> when (val r = place.receiver) {
            is Place.Local -> false
            is Place.Param -> !(r.sym in keeps.keptParams)
            is Place.This -> !keeps.thisKept
            else -> true
        }
        // A local's own storage is the body's: no release frees it.
        is Place.Local -> false
        else -> true
    }

    /**
     * Whether effects [e] may move or free the buffer a view into the storage [place] (of type
     * [type]) points at, not only change what the buffer holds: the object holding it freed, the
     * storage itself replaced or resized when its type reallocates ([reallocates]: a `List`
     * grown, a `Str` assigned), or a container holding it by value resized. An element written
     * in place (`items[0] = 5`, `a = [4, 5, 6]` on an `Arr`) leaves the buffer where it was.
     */
    private fun movesBuffer(e: Effects, place: Place, type: KType, keeps: Keeps): Boolean {
        if (e.any || (e.releases && releasable(place, keeps))) {
            return true
        }
        // A local written whole drops what it held: storage reached from it (`it.labels` after `it = Item {}`) may be freed.
        // The local's own storage stays where it is, and is the reallocation rule's below.
        val local = (place.root() as? Place.Local)?.sym
        if (local != null && place !is Place.Local && e.writes.any { w -> w.at === local && !w.inside }) {
            return true
        }
        val at = location(place)
        return e.writes.any { w ->
            val there = when {
                w.at == null || at == null -> overlaps(w.type, type)
                w.at === at -> true
                w.at is FieldSymbol && at is FieldSymbol -> fieldsNest(w.at, w.type, at, type)
                else -> overlaps(w.type, type)
            }
            there && (hasParam(type) || hasParam(w.type) || w.type == KType.Error || type in nested(w.type) || (!w.inside && w.type == type && reallocates(type)))
        }
    }

    /** Whether replacing or growing a [t] may move the buffer a view into it points at: a `Str`, a resizable container, an `Arr` or struct holding one, a type parameter. */
    private fun reallocates(t: KType, seen: MutableSet<TypeSymbol> = identitySet()): Boolean = when (t) {
        KType.Str, is KType.Param, KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.STRUCT -> seen.add(sym) && sym.typeParams.zip(t.typeArgs()).toMap().let { sub -> sym.fields.any { reallocates(it.type.substitute(sub), seen) } }
                ClassKind.MAGIC -> sym.name in REALLOCATING_MAGIC || (sym.name == "Arr" && t.typeArgs().any { reallocates(it, seen) })
                else -> false
            }
            else -> false
        }
        else -> false
    }

    /** Whether effects [e] may change or free the storage [place] of type [type] names (a loop's range, a `mut` argument). */
    private fun reaches(e: Effects, place: Place, type: KType, keeps: Keeps): Boolean {
        if (e.any || (e.releases && releasable(place, keeps))) {
            return true
        }
        val at = location(place)
        return e.writes.any { w ->
            when {
                w.at == null || at == null -> overlaps(w.type, type)
                w.at === at -> true
                w.at is FieldSymbol && at is FieldSymbol -> fieldsNest(w.at, w.type, at, type)
                else -> overlaps(w.type, type)
            }
        }
    }

    /** Two distinct fields' storage overlaps only when one lies by value inside the other: an element struct's field, a field of a struct field. */
    private fun fieldsNest(f: FieldSymbol, fType: KType, g: FieldSymbol, gType: KType): Boolean =
        (isStruct(f.owner) && containsSym(gType, f.owner)) || (isStruct(g.owner) && containsSym(fType, g.owner)) || hasParam(fType) || hasParam(gType)

    /** Whether effects [e] may change or free storage a reference of type [t] was bound to, wherever that is. */
    private fun affects(e: Effects, t: KType): Boolean {
        if (e.releases || e.any) {
            return true
        }
        return e.writes.any { w ->
            val at = w.at
            if (at is FieldSymbol && !w.inside) {
                // The whole field replaced: a reference into it, or to an object holding it by value.
                t == w.type || t in nested(w.type) || (isStruct(at.owner) && containsSym(t, at.owner)) || hasParam(t) || hasParam(w.type)
            } else {
                overlaps(w.type, t)
            }
        }
    }

    /**
     * Whether effects [e] may free or move the storage a reference of type [t] was bound to,
     * not only change its value in place: an object freed (a release, or anything), a
     * container holding a [t] by value resized, rebuilt or emptied (a [t] strictly inside
     * what was written), or a type the analysis cannot see into. Replacing a [t] itself, or
     * an element inside it, leaves the reference where it was (`items.add(9)` on the List a
     * `const kira::List<std::int32_t>&` names moves the elements, not the List).
     */
    private fun invalidates(e: Effects, t: KType): Boolean {
        if (e.releases || e.any) {
            return true
        }
        return e.writes.any { w -> hasParam(t) || hasParam(w.type) || w.type == KType.Error || t == KType.Error || t in nested(w.type) }
    }

    // ---- types -------------------------------------------------------------------------------------------

    /**
     * Whether a value of type [t] can point into storage it does not own: a `View`, `MutView`,
     * `Unsafe` or `CStr`, or one held by value (a container's element, a struct's field, a
     * tuple's part, a `Ref`'s box). A class or trait handle points to a shared object, and an
     * `Fx` owns its captures (a lambda in the body is an escape route of its own); a type
     * parameter inside a generic body is a value the body cannot take a view of.
     */
    private fun pointsInto(t: KType, seen: MutableSet<TypeSymbol> = identitySet()): Boolean = when (t) {
        KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.STRUCT -> seen.add(sym) && sym.typeParams.zip(t.typeArgs()).toMap().let { sub -> sym.fields.any { pointsInto(it.type.substitute(sub), seen) } }
                ClassKind.MAGIC -> sym.name in POINTER_MAGIC || t.typeArgs().any { pointsInto(it, seen) }
                else -> false
            }
            else -> false
        }
        else -> false
    }

    /**
     * Whether a [t] owns a buffer a view can point into, so a view of a copy dies with the copy:
     * a `Str`, a container, a `Maybe`, tuple or struct holding one, a type parameter. A scalar,
     * an enum, a view, a pointer, a handle and an `Fx` do not.
     */
    private fun ownsBuffer(t: KType, seen: MutableSet<TypeSymbol> = identitySet()): Boolean = when (t) {
        KType.Str, is KType.Param, KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.STRUCT -> seen.add(sym) && sym.typeParams.zip(t.typeArgs()).toMap().let { sub -> sym.fields.any { ownsBuffer(it.type.substitute(sub), seen) } }
                ClassKind.MAGIC -> when {
                    isReferenceOwner(sym) || sym.name in VIEWS || sym.name == "Fx" -> false
                    sym.name in BUFFER_MAGIC -> true
                    else -> t.typeArgs().any { ownsBuffer(it, seen) }
                }
                else -> false
            }
            else -> false
        }
        else -> false
    }

    private val nestedMemo = HashMap<KType, Set<KType>>()

    /** The types held by value inside a [t]: a container's elements, a struct's fields, a Maybe's value, a tuple's parts; never through a handle. */
    private fun nested(t: KType): Set<KType> {
        nestedMemo[t]?.let { return it }
        nestedMemo[t] = emptySet()
        val out = LinkedHashSet<KType>()
        if (t is KType.Nominal) {
            val sym = t.sym
            when {
                sym is ClassSymbol && sym.kind == ClassKind.STRUCT -> {
                    val sub = sym.typeParams.zip(t.typeArgs()).toMap()
                    sym.fields.forEach { f ->
                        val ft = f.type.substitute(sub)
                        out += ft
                        out += nested(ft)
                    }
                }
                sym is ClassSymbol && sym.kind == ClassKind.MAGIC && !isReferenceOwner(sym) && sym.name !in VIEWS -> t.typeArgs().forEach { a ->
                    out += a
                    out += nested(a)
                }
                else -> {}
            }
        }
        nestedMemo[t] = out
        return out
    }

    private fun overlaps(a: KType, b: KType): Boolean =
        a == b || hasParam(a) || hasParam(b) || a == KType.Error || b == KType.Error || b in nested(a) || a in nested(b)

    private fun containsSym(t: KType, sym: TypeSymbol): Boolean =
        (t is KType.Nominal && t.sym === sym) || nested(t).any { it is KType.Nominal && it.sym === sym } || hasParam(t)

    private fun hasParam(t: KType): Boolean = when (t) {
        is KType.Param -> true
        is KType.Nominal -> t.typeArgs().any { hasParam(it) }
        is KType.Fn -> t.params.any { hasParam(it.type) } || hasParam(t.ret)
        else -> false
    }

    /** Whether [t] or a type argument inside it is a nominal type [test] holds for. */
    private fun mentions(t: KType, test: (TypeSymbol) -> Boolean): Boolean = when (t) {
        is KType.Nominal -> test(t.sym) || t.typeArgs().any { mentions(it, test) }
        is KType.Fn -> t.params.any { mentions(it.type, test) } || mentions(t.ret, test)
        else -> false
    }

    /** Whether dropping a [t] may drop a handle to an object: it holds a class, trait, `Ref`, system class, `Fx` or type parameter by value. */
    private fun ownsObjects(t: KType, seen: MutableSet<TypeSymbol> = Collections.newSetFromMap(IdentityHashMap())): Boolean = when (t) {
        is KType.Param, is KType.Fn, KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is TraitSymbol -> true
            is EnumSymbol -> false
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS -> true
                ClassKind.OPAQUE -> false
                ClassKind.STRUCT -> seen.add(sym) && sym.typeParams.zip(t.typeArgs()).toMap().let { sub ->
                    sym.fields.any { ownsObjects(it.type.substitute(sub), seen) }
                }
                ClassKind.MAGIC -> when {
                    isSystem(sym) || sym.name == "Ref" -> true
                    sym.name in NON_OWNING -> false
                    else -> t.typeArgs().any { ownsObjects(it, seen) }
                }
            }
            else -> true
        }
        else -> false
    }

    private fun isStruct(sym: TypeSymbol?): Boolean = sym is ClassSymbol && sym.kind == ClassKind.STRUCT

    /** A type whose members are reached through a pointer: a class, trait, opaque class, `Ref`, `Weak`, `Unsafe`, or a system class. */
    private fun isReferenceOwner(sym: Any?): Boolean = when (sym) {
        is TraitSymbol -> true
        is ClassSymbol -> sym.kind == ClassKind.CLASS || sym.kind == ClassKind.OPAQUE || (sym.kind == ClassKind.MAGIC && (sym.name in REFERENCE_MAGIC || isSystem(sym)))
        else -> false
    }

    private fun isSystem(sym: Any?): Boolean =
        sym is ClassSymbol && sym.kind == ClassKind.MAGIC && CppTypeSpeller.systemHeaderFor(sym.module.uri) != null

    // ---- the walk -----------------------------------------------------------------------------------------

    /**
     * One pass over a body in evaluation order (D33: left to right, a call after its receiver
     * and arguments, an assignment's store after its value), loops twice so the next iteration
     * sees what the last one did. [summary] walks for [summaries]: a write through one of the
     * body's own `mut` parameters is left to its callers, which know the place they passed.
     * Otherwise it walks for a definition's guards and records what each use comes after.
     */
    private inner class Walker(
        private val summary: Boolean,
        private val fn: FnSymbol?,
        private val owner: TypeSymbol?,
        /** The parameters whose reads it records ([paramHits]): a function's, or a lambda's. */
        tracked: List<ParamSymbol> = emptyList(),
        /** The reads of a tracked parameter that take a view of it in place ([lentParams]); every other read is of its value. */
        private val viewReads: Set<ASTNode> = emptySet(),
        /** When given, the walk also checks the views the body keeps ([viewHits]), against what keeps its objects alive. */
        private val views: Keeps? = null,
    ) {
        private val tracked: Set<ParamSymbol> = Collections.newSetFromMap<ParamSymbol>(IdentityHashMap()).also { it.addAll(tracked) }

        /** Everything the walked code may do. */
        val all = Effects()

        /** Each effect in the order met, with the node that has it. */
        val events = mutableListOf<Pair<Effects, ASTNode>>()

        /** The tracked parameters read after an effect that may reach them. */
        val paramHits: MutableSet<ParamSymbol> = Collections.newSetFromMap(IdentityHashMap())

        /** The first read of each tracked parameter after an effect that may free or move what it names ([invalidates]), with that effect. */
        val invalidHits = IdentityHashMap<ParamSymbol, Pair<ASTNode, ASTNode>>()

        /** The first read of each tracked parameter's value (not a view of it) after an effect that may change it, with that effect. */
        val valueHits = IdentityHashMap<ParamSymbol, Pair<ASTNode, ASTNode>>()

        /** The first effect that may free an object before a read of the receiver, when there is one. */
        var thisReleasedBy: ASTNode? = null

        /** Each view local that may point into storage the body does not own, with that storage and how many events came before it was taken. */
        private val viewLocals = IdentityHashMap<LocalSymbol, Pair<List<ViewSource>, Int>>()

        /** What [views] found: a view read, or handed to a call, while an effect may move or free what it points into. */
        val viewHits = mutableListOf<ViewHit>()
        private val viewReported: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

        /** What may have happened before the node being walked. */
        private var now = Effects()

        /** The lambdas written as arguments of the call being walked, each with the effects its callee may run around it. */
        private val lambdaRuns = IdentityHashMap<LambdaExpr, Effects>()

        fun run(statements: List<ASTNode>): Walker {
            statements.forEach(::node)
            return this
        }

        private fun event(e: Effects, at: ASTNode) {
            if (e.isEmpty) {
                return
            }
            now.addAll(e)
            all.addAll(e)
            events += e to at
        }

        private fun useThis() {
            if (thisReleasedBy == null && now.releases) {
                thisReleasedBy = events.firstOrNull { it.first.releases }?.second ?: fn?.decl
            }
        }

        /** A read of [p] at [at]; [viewRead] when it takes a view of [p] in place rather than reading its value. */
        private fun useParam(p: ParamSymbol, at: ASTNode?, viewRead: Boolean) {
            if (p !in tracked || !affects(now, p.type)) {
                return
            }
            paramHits.add(p)
            val read = at ?: p.decl ?: return
            if (p !in invalidHits && invalidates(now, p.type)) {
                events.firstOrNull { invalidates(it.first, p.type) }?.let { invalidHits[p] = read to it.second }
            }
            if (!viewRead && p !in valueHits) {
                events.firstOrNull { affects(it.first, p.type) }?.let { valueHits[p] = read to it.second }
            }
        }

        /**
         * The storage [place] lies in is in use again: a `mut` argument the callee writes
         * through while it runs, or the receiver a call runs on after its arguments. `this`
         * or a parameter it is reached from is read at this point of the walk, so a method
         * that frees an object before holds itself, and such a parameter is copied at entry.
         */
        private fun inUse(place: Place?, at: ASTNode) {
            when (val root = place?.root()) {
                is Place.This -> useThis()
                is Place.Param -> useParam(root.sym, at, viewRead = false)
                else -> {}
            }
        }

        private fun children(n: ASTNode) = AstTree.children(n).forEach(::node)

        fun node(n: ASTNode) {
            when (n) {
                // A lambda anywhere but a call's argument runs later: its effects are the Fx pool's, and it captures copies.
                is LambdaExpr -> lambdaRuns[n]?.let { runLambda(n, it) }
                is FunctionCallExpr -> call(n)
                is ObjectInitExpr -> {
                    children(n)
                    event(construction(n), n)
                }
                is AssignmentExpr -> {
                    node(n.value)
                    // A local written whole is not read (a view local given a new view is not a read of the old one).
                    if (model.place(n.target) !is Place.Local) {
                        node(n.target)
                    }
                    event(write(model.place(n.target), model.typeOrNull(n.target)), n)
                    ((model.place(n.target) as? Place.Local)?.sym)?.let { takeView(it, n.value) }
                }
                is CompoundAssignmentExpr -> {
                    node(n.left)
                    node(n.right)
                    event(write(model.place(n.left), model.typeOrNull(n.left)), n)
                }
                is PlaceAssignmentExpr -> {
                    node(n.value)
                    node(n.target)
                    event(write(model.place(n.target), model.typeOrNull(n.target)), n)
                }
                is VariableDecl -> {
                    n.value?.let(::node)
                    (model.declSymbol(n) as? LocalSymbol)?.let { local -> n.value?.let { takeView(local, it) } }
                    val t = (model.declSymbol(n) as? LocalSymbol)?.type ?: n.type.let { model.typeOf(it) }
                    // A local that holds an object is dropped at the end of its block, which may run a finally.
                    if (hasFinally && t != null && ownsObjects(t)) {
                        event(release(), n)
                    }
                }
                // Unwinding destroys the locals it leaves, which may run a finally.
                is ThrowExpr -> {
                    children(n)
                    if (hasFinally) {
                        event(release(), n)
                    }
                }
                is ForIterationStatement -> repeat(2) {
                    node(n.forIterationExpr.target)
                    n.body.forEach(::node)
                }
                is WhileIterationStatement -> repeat(2) {
                    node(n.condition)
                    n.statements.forEach(::node)
                }
                is DoWhileIterationStatement -> repeat(2) {
                    n.statements.forEach(::node)
                    node(n.condition)
                }
                is ThisExpr -> useThis()
                is IntrinsicExpr -> children(n)
                is Identifier -> identifier(n)
                is MemberAccessExpr -> {
                    node(n.origin)
                    if (n.member !is Identifier || n.member is IntrinsicExpr) {
                        node(n.member)
                    }
                }
                is FunctionCallNamedParameterExpr -> node(n.value)
                is Type -> {}
                else -> {
                    children(n)
                    // An operator a type overloads is a call the analysis does not follow.
                    if (n is Expr && model.opCall(n) != null) {
                        event(Effects.unknown(), n)
                    }
                }
            }
        }

        /** [local] now holds [value]: a view of storage the body does not own is followed from here ([viewLocals]). */
        private fun takeView(local: LocalSymbol, value: Expr) {
            if (views == null || !pointsInto(local.type)) {
                return
            }
            val sources = viewSources(value)
            if (sources.isEmpty()) {
                viewLocals.remove(local)
            } else {
                viewLocals[local] = sources to events.size
            }
        }

        /** The storage a view-valued [e] may point into that the body does not own: what it takes a view of in place, or the view local it reads. */
        private fun viewSources(e: Expr): List<ViewSource> {
            val keeps = views ?: return emptyList()
            val out = mutableListOf<ViewSource>()
            fun storage(x: Expr) {
                val p = model.place(x) ?: return
                val t = model.typeOrNull(x) ?: return
                // A parameter copied at entry is a local copy the body never writes; anything else may move under the view.
                val root = p.root()
                if (ownsBuffer(t) && !(root is Place.Param && !root.sym.byRef && root.sym in keeps.keptParams)) {
                    out += ViewSource(p, t, x)
                }
            }
            if (model.coercion(e) is Coercion.ToView) {
                storage(e)
            }
            val call = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr
            when {
                e is Identifier -> (model.symbolOf(e) as? LocalSymbol)?.let { local -> viewLocals[local]?.first?.let(out::addAll) }
                call != null -> model.call(call)?.let { rc ->
                    if (pointsInto(model.typeOrNull(e) ?: rc.returnType)) {
                        rc.receiver?.let { r ->
                            storage(r)
                            out += viewSources(r)
                        }
                        rc.args.forEach { a ->
                            if (a is ArgBinding.Given && !a.byRef) {
                                storage(a.expr)
                                out += viewSources(a.expr)
                            }
                        }
                    }
                }
                e is ObjectInitExpr -> AstTree.children(e).filterIsInstance<Expr>().forEach { out += viewSources(it) }
                else -> {}
            }
            return out
        }

        /** A read of the view local [local] at [at]: an effect since the view was taken that may move or free what it points into is a hit. */
        private fun readView(local: LocalSymbol, at: ASTNode) {
            val keeps = views ?: return
            val (sources, from) = viewLocals[local] ?: return
            if (local in viewReported) {
                return
            }
            for (i in from until events.size) {
                val (e, cause) = events[i]
                val s = sources.firstOrNull { movesBuffer(e, it.place, it.type, keeps) } ?: continue
                viewReported.add(local)
                viewHits += ViewHit(at, local.name, s.expr, cause, callee = null)
                return
            }
        }

        private fun identifier(id: Identifier) {
            when (val sym = model.symbolOf(id)) {
                is LocalSymbol -> readView(sym, id)
                is ParamSymbol -> useParam(sym, id, id in viewReads)
                // A bare field name is the receiver's field (a member name after `.` is never walked).
                is FieldSymbol -> useThis()
                else -> {}
            }
        }

        private fun call(n: FunctionCallExpr) {
            val rc = model.call(n)
            if (rc == null) {
                children(n)
                event(Effects.unknown(), n)
                return
            }
            if (rc.implicitThis) {
                useThis()
            }
            node(n.name)
            val callee = calleeEffects(rc)
            val lambdaArgs = rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr as? LambdaExpr }
            lambdaArgs.forEach { lambdaRuns[it] = callee }
            n.positionalParameters.forEach(::node)
            n.namedParameters.forEach(::node)
            lambdaArgs.forEach { lambdaRuns.remove(it) }
            // The call runs on its receiver after its arguments (C++ reads `this->labels` or
            // `this` first, then the arguments): an argument that frees the method's object
            // (`labels.add(h.take())`, `put(h.take())`) would leave the call on freed memory.
            if (rc.implicitThis) {
                useThis()
            }
            rc.receiver?.let { r -> if (model.place(r)?.root() is Place.This) useThis() }
            val e = callee.copy()
            rc.args.forEach { a ->
                if (a is ArgBinding.Given && a.byRef) {
                    e.addAll(write(model.place(a.expr), model.typeOrNull(a.expr)))
                }
            }
            // A view handed to the call points into its storage while the callee runs.
            views?.let { keeps ->
                rc.args.forEach { a ->
                    if (a is ArgBinding.Given && !a.byRef && (model.coercion(a.expr) is Coercion.ToView || pointsInto(model.typeOrNull(a.expr) ?: KType.Error))) {
                        val s = viewSources(a.expr).firstOrNull { movesBuffer(e, it.place, it.type, keeps) }
                        if (s != null) {
                            viewHits += ViewHit(a.expr, null, s.expr, n, callee = rc.fn?.name ?: KiraUnparser.text(n.name))
                        }
                    }
                }
            }
            event(e, n)
            // A mut argument is written through while the callee runs, so the object it lies in is in use until the call returns.
            rc.args.forEach { a ->
                if (a is ArgBinding.Given && a.byRef) {
                    inUse(model.place(a.expr), a.expr)
                }
            }
            // A runtime method handed a function runs it while it works on its receiver.
            if (rc.kind == CallKind.MAGIC && rc.args.any { a -> a is ArgBinding.Given && (a.expr is LambdaExpr || model.typeOrNull(a.expr) is KType.Fn) }) {
                rc.receiver?.let { r -> inUse(model.place(r), r) }
            }
            // A call through an Fx runs the function where it is stored: its callee (a parameter,
            // the receiver's field) is in use while what it runs runs, so it is read again after.
            if (rc.kind == CallKind.FN_VALUE) {
                node(n.name)
            }
        }

        /** A lambda handed to a call: it may run during the call, after what the callee does and after its own earlier runs. */
        private fun runLambda(l: LambdaExpr, callee: Effects) {
            val body = l.def.body ?: return
            val before = now
            now = before.copy().also { s ->
                s.addAll(callee)
                summaries[body]?.let(s::addAll)
            }
            body.forEach(::node)
            before.addAll(now)
            now = before
        }

        fun calleeEffects(rc: ResolvedCall): Effects = when (rc.kind) {
            CallKind.PRINT, CallKind.EXTERN -> Effects()
            CallKind.MAGIC -> magic(rc)
            CallKind.FREE, CallKind.METHOD -> rc.fn?.body?.let { summaries[it] } ?: Effects()
            CallKind.VIRTUAL, CallKind.TRAIT -> Effects().also { e ->
                val name = rc.fn?.name
                if (name == null) {
                    e.addAll(Effects.unknown())
                } else {
                    methodsNamed[name].orEmpty().forEach { m -> summaries[m.body]?.let(e::addAll) }
                }
            }
            CallKind.FN_VALUE -> fxPool
            else -> Effects.unknown()
        }

        /**
         * A `@_magic` call: a `mut fx` writes its receiver (a container that grows, shrinks or is
         * cleared); an `Fx` it is handed may run; a system class's method (a thread's join, a
         * mutex's lock) runs code the analysis cannot see.
         */
        private fun magic(rc: ResolvedCall): Effects {
            val fn = rc.fn ?: return Effects.unknown()
            val receiverType = rc.receiver?.let { model.typeOrNull(it) }
            if (isSystem(fn.owner) || (receiverType != null && mentions(receiverType) { isSystem(it) }) || CppTypeSpeller.systemHeaderFor(fn.module.uri) != null) {
                return Effects.unknown()
            }
            val e = Effects()
            if (fn.isMutMethod) {
                val receiver = rc.receiver
                if (receiver != null) {
                    // A temporary receiver (a call's result) is no place, and nothing else reaches it.
                    model.place(receiver)?.let { e.addAll(write(it, receiverType, whole = true)) }
                }
            }
            rc.args.forEach { a ->
                if (a is ArgBinding.Given) {
                    val lambda = a.expr as? LambdaExpr
                    when {
                        lambda != null -> lambda.def.body?.let { summaries[it] }?.let(e::addAll)
                        model.typeOrNull(a.expr) is KType.Fn -> e.addAll(fxPool)
                    }
                }
            }
            return e
        }

        /** The construction of [n]: the `initially` of each class of its chain runs, and a class may be dropped at once, which may run a `finally`. */
        private fun construction(n: ObjectInitExpr): Effects {
            val cls = model.init(n)?.cls ?: return Effects.unknown()
            if (isSystem(cls)) {
                return Effects.unknown()
            }
            val e = Effects()
            var c: ClassSymbol? = cls
            val seen: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())
            while (c != null && seen.add(c)) {
                c.initially?.let { summaries[it]?.let(e::addAll) }
                c = c.superclass?.sym as? ClassSymbol
            }
            if (cls.kind == ClassKind.CLASS && hasFinally) {
                e.addAll(release())
            }
            return e
        }

        /**
         * A write of a [type] at [place]: nothing outside the body for a local of its own (unless
         * dropping what it held may run a `finally`), the caller's place for a `mut` parameter
         * (left to the callers in a summary), and shared storage for anything else. [whole]
         * when the write replaces or resizes all of [place] (a container's `mut fx`).
         */
        fun write(place: Place?, type: KType?, whole: Boolean = false): Effects {
            val t = type ?: KType.Error
            if (place == null) {
                return Effects.unknown()
            }
            val e = Effects()
            when (kindOf(place)) {
                PlaceKind.LOCAL -> {
                    if (hasFinally && ownsObjects(t)) {
                        e.addAll(release())
                    }
                    // Checking views, a write of the body's own storage is one a view of that storage must see.
                    if (views != null) {
                        e.writes += Write(location(place), t, inside = !whole && place is Place.Index)
                    }
                }
                PlaceKind.MUT_PARAM -> if (!summary) {
                    e.writes += Write(location(place), t, inside = !whole && place is Place.Index)
                    if (ownsObjects(t)) {
                        e.addAll(release())
                    }
                }
                PlaceKind.SHARED -> {
                    e.writes += Write(location(place), t, inside = !whole && place !is Place.Field)
                    if (ownsObjects(t)) {
                        e.addAll(release())
                    }
                }
            }
            return e
        }
    }

    // Last, after every table above exists (Kotlin runs initializers in the order they are written).
    init {
        collectBodies()
        collectHeld()
        solve()
    }

    companion object {
        /** The `@_magic` classes that are references in C++ (`kira::Rc`, `kira::Weak`, a pointer). */
        private val REFERENCE_MAGIC = setOf("Ref", "Weak", "Unsafe", "CStr")

        private val VIEWS = setOf("View", "MutView")

        /** The `@_magic` classes the parameter column passes by value (CppTypeSpeller.BY_VALUE_MAGIC). */
        private val BY_VALUE_MAGIC = setOf("View", "MutView", "Unsafe", "CStr")

        /** The `@_magic` classes whose value holds no object: a non-owning handle, a view, a pointer. */
        private val NON_OWNING = setOf("Weak", "Unsafe", "CStr", "View", "MutView")

        /** The `@_magic` classes whose value points into storage it does not own. */
        private val POINTER_MAGIC = setOf("View", "MutView", "Unsafe", "CStr")

        /** The `@_magic` classes that move their buffer when they grow or are rebuilt (CppLending.REALLOCATING). */
        private val REALLOCATING_MAGIC = setOf("List", "Map", "Set", "Stack", "Queue", "Deque")

        /** The `@_magic` classes whose value owns a buffer a view can be taken into (CppLending.OWNERS). */
        private val BUFFER_MAGIC = setOf("StrBuf", "Arr", "List", "Map", "Set", "Stack", "Queue", "Deque")

        private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
    }
}
