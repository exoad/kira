package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
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
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
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
 * lowering holds four kinds of reference into such storage.
 *
 * 1. A parameter C++ takes by `const&` (design table 5.1). Bound to a field or an element of an
 *    object, it reads whatever the callee's own effects leave there, or freed memory:
 *    `lines.add(...)` reallocates the List that `s` points into, and `h.clear()` frees the Item
 *    whose label `s` is (measured on g++: `std::bad_alloc`, freed heap bytes). A parameter that
 *    some effect of the body may reach before the body reads it is copied at entry
 *    ([Guard.snapshots]). The copy is the value the caller passed, as Kira's parameters are
 *    values (`c.set(d.name)` with `set` renaming `c` gives `a`, as the C and JS backends do).
 *    A template `Fx` parameter is copied the same way. One a view that may leave the call may
 *    point into ([Guard.lent]: returned, or kept through a `mut` parameter, a `Ref` box, an
 *    `Fx`) stays the caller's reference, since the copy would die at return under the view,
 *    and a read of it that the reference cannot make right is refused. A view that may leave
 *    the call into storage only a copied handle or a method's hold keeps alive is refused
 *    ([reachedRefusals]).
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
 *    `c = b`, measured: freed heap bytes). Refused ([refusals]), as are a `mut` argument that
 *    names such storage when the call may change it, or lies inside another `mut` argument of
 *    the call, a call through an `Fx` such storage holds while the code it runs may replace
 *    it, and a lambda's `const&` parameter read after an effect that may reach it: each of
 *    those lowerings is the statement part's, and each diagnostic names the local copy that
 *    makes the program safe. A range reached from a temporary is the statement part's, which
 *    copies it (W2.3 9e00cfb). A field's default is checked as a body is.
 * 4. A view (`View`, `MutView`) into such storage, which C++ keeps as a pointer: the object
 *    holding the buffer freed, or the buffer moved by a growth, leaves it dangling. Each view a
 *    body keeps ([Walker.viewSources]) is followed to what it may point into: a place taken in
 *    place (`b.items.view()`), or, through a call, whatever its receiver, arguments, implicit
 *    `this` and (for a Kira callee) the globals may reach (`b.all()`, `viewOf(b)`); a view kept
 *    in a local container (`vs.add(v)`), a `Ref` box, a struct or an if-expression holds what
 *    its parts do. A view read after an effect that may move or free that storage, captured by
 *    a lambda that may run after one, handed to a call that may have one, read by a call after
 *    a later argument that may, iterated by a loop whose body may, or returned through a
 *    local's handle is refused by name, and so is a heap object given a view into a temporary.
 *    (g++ printed garbage and MSVC ASan reported a heap-use-after-free on every one.)
 *
 * Effects are decided over the whole program ([summaries]): the fields, globals and `mut`
 * parameters each body may overwrite, with the types written there, and whether it may free an
 * object (drop a handle, or run a `finally`). A call's effects are its callee's: every body of
 * the name for a virtual or trait call, and every lambda and function value for a call through
 * an `Fx` (the lambda itself, for a local that is not `mut` and was given one). What C++
 * supplies (a C++ override of a trait method, an `Fx` built in C++, an `@_extern`, a bodyless
 * `pub` prototype a C++ file defines) is taken to leave Kira's objects alone during the call:
 * the FFI contract, recorded as an open decision in `docs/cpp-known-issues/w2-4-emit-oop.md`.
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

        /** The body's own locals it destroys: the end of the block that declares them (only a view check records these). */
        internal val drops: MutableSet<LocalSymbol> = Collections.newSetFromMap(IdentityHashMap())

        val isEmpty: Boolean get() = !releases && !any && writes.isEmpty() && drops.isEmpty()

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
            if (drops.addAll(other.drops)) {
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

    /** The locals that are not `mut` and are given a lambda where they are declared: a call through one runs that lambda. */
    private val lambdaLocals = IdentityHashMap<LocalSymbol, LambdaExpr>()
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
                    is VariableDecl -> (node.value as? LambdaExpr)?.let { l ->
                        (model.declSymbol(node) as? LocalSymbol)?.let { local -> if (!local.isMut) lambdaLocals[local] = l }
                    }
                    is Identifier -> if (node !in callees) {
                        (model.symbolOf(node) as? FnSymbol)?.let { fnValues.add(it) }
                    }
                    else -> {}
                }
            }
            // A lambda local named anywhere but as the callee of a call in its own body (not inside another lambda) may run anywhere.
            val declaredNames: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
            fun visit(n: ASTNode, lambda: LambdaExpr?) {
                if (n is VariableDecl) {
                    declaredNames.add(n.name)
                    if (n.value is LambdaExpr) {
                        (model.declSymbol(n) as? LocalSymbol)?.let { declaredIn[it] = lambda }
                    }
                }
                if (n is Identifier && n !in declaredNames) {
                    (model.symbolOf(n) as? LocalSymbol)?.let { local ->
                        if (n !in callees) {
                            namedAsValue.add(local)
                        } else {
                            callSites.getOrPut(local) { mutableListOf() } += lambda
                        }
                    }
                }
                AstTree.children(n).forEach { c -> visit(c, if (n is LambdaExpr) n else lambda) }
            }
            visit(ast, null)
        }
        lambdaLocals.keys.forEach { local ->
            val home = declaredIn[local]
            if (local !in namedAsValue && callSites[local].orEmpty().all { it === home }) {
                calledInPlace.add(local)
            }
        }
    }

    private val declaredIn = IdentityHashMap<LocalSymbol, LambdaExpr?>()
    private val namedAsValue: MutableSet<LocalSymbol> = Collections.newSetFromMap(IdentityHashMap())
    private val callSites = IdentityHashMap<LocalSymbol, MutableList<LambdaExpr?>>()

    /**
     * The lambda locals ([lambdaLocals]) that are only ever called, and only by the body that
     * declares them: the lambda runs at those calls and nowhere else, so what it captures is
     * in use there ([Walker.capture]).
     */
    private val calledInPlace: MutableSet<LocalSymbol> = Collections.newSetFromMap(IdentityHashMap())

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
        val lending = lentParams(fn, body)
        val w = Walker(summary = false, fn = fn, owner = fn.owner, tracked = fn.params, viewReads = lending.viewReads).run(body)
        val snapshots = fn.params.filter { p -> !p.byRef && p in w.paramHits && holdable(p.type) && p !in lending.lent }
        val refused = lending.lent.filter { holdable(it.type) }.mapNotNull { p -> lentRefusal(p, w) } + reachedRefusals(fn, lending, snapshots, w)
        Guard(snapshots, w.thisReleasedBy != null, w.thisReleasedBy, lending.lent.toList(), refused)
    }

    /**
     * A view that may leave the call ([Lending.reachedParams], [Lending.reachedThis]) taken of
     * storage reached through a handle the call itself keeps alive: a parameter copied at entry
     * (the copy holds the object once an effect of the body may drop the caller's handle to it),
     * or `this` in a method that holds itself. The copy and the hold die at return, and when
     * they were the object's last owner the view points into freed memory (`return b.items.view()`
     * after `h.reset()` replaced the Bag the caller passed, and `return items.view()` from a method
     * holding itself, were a heap-use-after-free under MSVC ASan). Without the copy or the hold
     * the body reads freed memory, or another object than the one it was passed, so both are
     * refused.
     */
    private fun reachedRefusals(fn: FnSymbol, lending: Lending, snapshots: List<ParamSymbol>, w: Walker): List<Pair<ASTNode, String>> {
        val out = mutableListOf<Pair<ASTNode, String>>()
        val remedy = "Return a copy of what the view shows (`items: List<T> = ...`, then return items), or take the view in the caller"
        lending.reachedParams.forEach { (p, at) ->
            if (snapshots.any { it === p }) {
                val cause = w.firstHits[p]?.let(::describe) ?: "an effect of the body"
                out += at to "the view of ${KiraUnparser.text(at)}, which may leave ${fn.name}, points into storage reached through the parameter ${p.name}, which the body copies at entry " +
                    "because $cause may change or free what ${p.name} names: once the call returns, that copy may have been the object's only owner, and the view would point into freed memory. $remedy"
            }
        }
        val owner = fn.owner
        val released = w.thisReleasedBy
        lending.reachedThis?.let { at ->
            if (released != null && owner is ClassSymbol && owner.kind == ClassKind.CLASS) {
                out += at to "the view of ${KiraUnparser.text(at)}, which may leave ${fn.name}, points into the object the method runs on, which the method keeps alive only for the call " +
                    "(it holds itself because ${describe(released)} may free it): once the call returns, that hold may have been the object's only owner, and the view would point into freed memory. $remedy"
            }
        }
        return out
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
     * What [lentParams] finds in a body: the parameters a view the call may hand back points
     * into ([lent]) with the reads that take such a view ([viewReads]), and, where a view may
     * leave the call, each parameter and `this` whose storage, reached through a handle, the
     * body takes a view of, with the first expression that takes it ([reachedParams],
     * [reachedThis]; [reachedRefusals] refuses those the call alone may keep alive).
     */
    private class Lending(
        val lent: Set<ParamSymbol>,
        val viewReads: Set<ASTNode>,
        val reachedParams: Map<ParamSymbol, Expr>,
        val reachedThis: Expr?,
    )

    /**
     * The parameters of [fn] a view the call may hand back can point into, with the reads of
     * them that take such a view: one C++ takes by `const&`, of a type a view can point into
     * (it owns a buffer: a `Str`, a container, a struct holding one), that the body takes a
     * view of in place (a conversion to a `View`, the receiver or an argument of a call whose
     * result or `mut` argument can hold a view), when a view may leave the call: its return
     * type or a `mut` parameter can hold one, a parameter reaches storage that can keep one (a
     * `Ref` of a view, a generic class, an `Fx`, a type parameter: `r.value = xs.view()` kept
     * the view of the copy in the caller's box, and MSVC ASan reported a heap-use-after-free),
     * the body throws, builds a lambda or hands a view to C++, or the program has storage that
     * may keep one (a global, a class field of a view, an `Fx` or a type parameter). W2.3 lends
     * such an argument from the caller's own storage; a copy would be a local that dies at
     * return.
     *
     * The storage a view is taken of through a handle (`b.items.view()`, `b.all()`, `items.view()`
     * in a method) is recorded as reached through the parameter or `this` it starts from, when a
     * view may leave the call by the call itself (a global or a class field that keeps a view is
     * W2.5's EscapePass, D5).
     */
    private fun lentParams(fn: FnSymbol, body: List<Statement>): Lending {
        val candidates = identitySet<ParamSymbol>()
        fn.params.filter { !it.byRef && passedByReference(it.type) && ownsBuffer(it.type) }.forEach { candidates.add(it) }
        val viewReads = identitySet<ASTNode>()
        val reachedParams = IdentityHashMap<ParamSymbol, Expr>()
        var reachedThis: Expr? = null
        var leaves = pointsInto(fn.ret) || fn.params.any { p -> if (p.byRef) pointsInto(p.type) else keepsViewsThrough(p.type) }
        fun mark(e: Expr?) {
            e ?: return
            val root = rootIdentifier(e)
            val p = root?.let { model.symbolOf(it) } as? ParamSymbol
            val place = model.place(e)
            if (root != null && p != null && p in candidates && place != null && !throughReference(place)) {
                viewReads.add(root)
                return
            }
            val roots: MutableSet<Any> = identitySet()
            viewRoots(e, roots)
            roots.forEach { r ->
                when {
                    r === THIS -> if (reachedThis == null) reachedThis = e
                    r is ParamSymbol && passedByReference(r.type) && reachesThroughHandle(r.type) -> reachedParams.putIfAbsent(r, e)
                }
            }
        }
        body.forEach { s ->
            AstTree.walk(s) { node ->
                when (node) {
                    is LambdaExpr, is ThrowExpr -> leaves = true
                    is FunctionCallExpr -> model.call(node)?.let { rc ->
                        val given = rc.args.filterIsInstance<ArgBinding.Given>()
                        if (pointsInto(rc.returnType) || given.any { it.byRef && pointsInto(model.typeOrNull(it.expr) ?: KType.Error) }) {
                            mark(rc.receiver)
                            given.forEach { mark(it.expr) }
                            if (rc.implicitThis && reachedThis == null) {
                                reachedThis = node
                            }
                        }
                        if (rc.kind == CallKind.EXTERN && given.any { pointsInto(model.typeOrNull(it.expr) ?: KType.Error) }) {
                            leaves = true
                        }
                    }
                    else -> {}
                }
                if (node is Expr && model.coercion(node) is Coercion.ToView) {
                    mark(node)
                }
            }
        }
        val escapes = leaves || programKeepsViews
        val lent = identitySet<ParamSymbol>()
        if (escapes) {
            viewReads.forEach { r -> (model.symbolOf(r as Identifier) as? ParamSymbol)?.let { lent.add(it) } }
        }
        return Lending(
            lent,
            if (escapes) viewReads else emptySet(),
            if (leaves) reachedParams else emptyMap(),
            if (leaves) reachedThis else null,
        )
    }

    /**
     * The parameters and `this` ([THIS]) whose storage [e] may be reached through: the variable
     * a path starts from, and through a call, its receiver, its arguments and an implicit
     * `this` (`b.get().items` is reached through b).
     */
    private fun viewRoots(e: Expr, out: MutableSet<Any>) {
        fun call(c: FunctionCallExpr) {
            val rc = model.call(c) ?: return
            if (rc.implicitThis) {
                out.add(THIS)
            }
            rc.receiver?.let { viewRoots(it, out) }
            rc.args.forEach { a -> if (a is ArgBinding.Given) viewRoots(a.expr, out) }
        }
        when (e) {
            is Identifier -> when (val sym = model.symbolOf(e)) {
                is ParamSymbol -> out.add(sym)
                is FieldSymbol -> out.add(THIS)
                else -> {}
            }
            is ThisExpr -> out.add(THIS)
            is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let(::call) ?: viewRoots(e.origin, out)
            is ArrayIndexExpr -> viewRoots(e.originExpr, out)
            is FunctionCallExpr -> call(e)
            else -> {}
        }
    }

    /**
     * Whether a parameter of type [t], not `mut`, can carry a view out of the call through what
     * it reaches: a `Ref` box or a generic class that can hold one ([sharesViews]), an `Fx`
     * (the lambda it runs may keep it), or a type parameter (any of those).
     */
    private fun keepsViewsThrough(t: KType): Boolean = sharesViews(t) || mentionsFn(t) || hasParam(t)

    /**
     * Whether a [t] reaches, through a handle, storage that can keep a view: a `Ref` or `Weak`
     * box, or a generic class, instantiated with a type that can hold one (a view, an `Fx`, a
     * type parameter), or a value holding such a handle (a `List<Ref<View<Int32>>>`). Anyone
     * holding the same handle can store a view there, so a view read out of it may point
     * anywhere.
     */
    private fun sharesViews(t: KType, seen: MutableSet<TypeSymbol> = identitySet()): Boolean = when (t) {
        is KType.Nominal -> {
            val sym = t.sym
            val args = t.typeArgs()
            when {
                (isReferenceOwner(sym) || (sym is ClassSymbol && sym.kind == ClassKind.CLASS)) && args.any { keepsViews(it) || sharesViews(it, seen) } -> true
                sym is ClassSymbol && sym.kind == ClassKind.STRUCT ->
                    seen.add(sym) && sym.typeParams.zip(args).toMap().let { sub -> sym.fields.any { sharesViews(it.type.substitute(sub), seen) } }
                else -> args.any { sharesViews(it, seen) }
            }
        }
        else -> false
    }

    /** Whether a value of type [t] is, or holds, a view, or reaches storage that can keep one ([sharesViews]). */
    private fun holdsViews(t: KType): Boolean = pointsInto(t) || sharesViews(t)

    /** Whether a [t] reaches objects through a handle it holds (a class, trait, `Ref`, `Weak`, system class, `Fx`, type parameter), where a view it leads to may point. */
    private fun reachesThroughHandle(t: KType): Boolean = ownsObjects(t) || mentions(t) { it is ClassSymbol && it.kind == ClassKind.MAGIC && it.name == "Weak" }

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

    /**
     * The program's own modules have a global a view may be taken into or through: one that
     * owns a buffer, holds an object, or holds a view. A view a Kira function returns may then
     * point into it whatever its arguments are (`allNums()` returning `nums.view()`).
     */
    private val globalsHoldStorage: Boolean by lazy {
        program.modules.filter { !it.isStdlib }.flatMap { it.declarations }.any { sym ->
            sym is GlobalSymbol && (ownsBuffer(sym.type) || reachesThroughHandle(sym.type) || holdsViews(sym.type))
        }
    }

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

    /**
     * Storage a view may point into: its [place] and [type], and the expression that names it.
     * A null [place] is storage reached through [expr], wherever that leads: a handle
     * (`b.all()`, `viewOf(b)`), `this`, a global a call may view, or a box a view is kept in;
     * [locals] are the body's locals it is reached from, whose handles keep it alive until they
     * are written whole; [box] names the local whose shared box (`Ref<View<T>>`) any holder of
     * it may point elsewhere.
     */
    private class ViewSource(val place: Place?, val type: KType, val expr: Expr, val locals: Set<LocalSymbol> = emptySet(), val box: String? = null)

    /** The end of the block that declares [local], as the cause of a view check's hit ([Walker.block]). */
    private class BlockEnd(val local: LocalSymbol) : ASTNode() {
        override fun accept(visitor: KiraASTVisitor) {}
    }

    /** How a view is in use when an effect may move or free what it points into ([ViewHit]). */
    private enum class HitKind {
        /** The view local is read after the effect. */
        READ,

        /** The view is handed to a call whose callee may have the effect. */
        HANDED,

        /** The view, read before a later argument of the same call has the effect, is read by the call after it. */
        SIBLING,

        /** A range-for over the view, whose body may have the effect. */
        LOOP,

        /** A lambda captures the view, and may run after the effect. */
        CAPTURE,

        /** The body returns the view, which points into storage only a local's handle may keep alive. */
        RETURN,
    }

    /**
     * A view in use while an effect may move or free what it points into: the view local [view]
     * read at [at] after [cause], or the view at [at] used the way [kind] says ([callee] is the
     * call it is handed to).
     */
    private class ViewHit(val at: ASTNode, val view: String?, val source: ViewSource, val cause: ASTNode, val callee: String?, val kind: HitKind = HitKind.READ)

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
     * storage; a `mut` argument naming such storage that the call may change or free; a call
     * through an `Fx` such storage holds while the code it runs may replace or free it; a
     * lambda's `const&` parameter read after an effect that may reach it (a lambda is the
     * statement part's, and copies nothing at entry); a lent parameter the body reads after an
     * effect that may reach it ([Guard.lent]), and a view that may leave the call into storage
     * only the call keeps alive ([reachedRefusals]); and each view the body keeps that is in use
     * after an effect that may move or free what it points into ([ViewHit]).
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

    /**
     * The temporary a view [e] holds points into, when [e] is stored in a heap object's field of
     * type [field] (a class construction, a `Ref`'s box): `makeList()` of `Ref<View<Int32>> {
     * value = makeList().view() }`, which C++ destroys at the end of the statement while the box
     * keeps the view (g++ printed 2 and zig c++ 334 where Kira gives 2 and 2). A temporary is a
     * value no place names that owns a buffer (a call's result, a construction), or storage one
     * holds (`makeItem().labels`); a view of a view, or the result of a call made from one,
     * points where its operands do. The statement part refuses the same construction when it
     * lowers it; this does not rely on that.
     */
    fun temporaryViewed(e: Expr, field: KType): Expr? {
        if (!holdsViews(field)) {
            return null
        }
        return viewedTemporary(e)
    }

    private fun viewedTemporary(e: Expr): Expr? {
        if (model.coercion(e) is Coercion.ToView) {
            return temporaryStorage(e)
        }
        val t = model.typeOrNull(e) ?: return null
        if (!holdsViews(t)) {
            return null
        }
        val call = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr
        if (call != null) {
            val rc = model.call(call) ?: return null
            val operands = listOfNotNull(rc.receiver) + rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }
            return operands.firstNotNullOfOrNull { x -> temporaryStorage(x) ?: viewedTemporary(x) }
        }
        return when (e) {
            is Identifier, is LambdaExpr -> null
            else -> if (model.place(e) != null) null else AstTree.children(e).filterIsInstance<Expr>().firstNotNullOfOrNull(::viewedTemporary)
        }
    }

    /** [x] as the temporary whose buffer a view of it points into: a fresh value that owns one, or a place inside such a value. */
    private fun temporaryStorage(x: Expr): Expr? {
        val t = model.typeOrNull(x) ?: return null
        // A literal's storage is static (`"x".view()` is `kira::lit("x")`).
        if (!ownsBuffer(t) || pointsInto(t) || x is StringLiteral) {
            return null
        }
        val place = model.place(x) ?: return x
        return if (place.root() is Place.Field) x else null
    }

    private fun viewRefusal(h: ViewHit): String {
        val source = h.source
        val of = KiraUnparser.text(source.expr)
        val type = model.typeOrNull(source.expr)?.display() ?: "its type"
        val cause = describe(h.cause)
        // The body's own local is copied by taking the view later; storage outside the body by viewing a local copy of it.
        val own = source.place?.root() is Place.Local
        val copy = if (source.place == null) "a view of a local copy of what it shows (`items: List<T> = ...`, then a view of items)" else "a view of a local copy (`items: $type = $of`, then a view of items)"
        // What the view points into: a place, or wherever storage reached through an expression leads.
        val into = when {
            source.box != null -> "what it points into (anyone who holds the box ${source.box} may have stored a view of any storage there)"
            source.place == null -> "storage reached through $of"
            else -> of
        }
        val view = h.view?.let { "the view $it" } ?: "the view ${KiraUnparser.text(h.at)}"
        (h.cause as? BlockEnd)?.let { end ->
            val taken = if (source.place == null) "which may point into storage reached through $of" else "taken of $of"
            val how = if (h.kind == HitKind.CAPTURE) "captured by a lambda that may run after" else "read after"
            return "$view, $taken, $how $cause, which destroys ${end.local.name}: C++ keeps the view pointing into storage that no longer exists. " +
                "Declare ${end.local.name} before that block, or finish with the view inside it"
        }
        if (source.box != null && h.kind == HitKind.READ) {
            return "the view the box ${source.box} holds, read after $cause, which may change or free $into: C++ keeps the view pointing where it was, which the effect may move. " +
                "Read it before $cause, or keep a copy of what the view shows (a List<T>) in the box instead of a view"
        }
        return when (h.kind) {
            HitKind.HANDED -> if (source.place == null) {
                "the view ${KiraUnparser.text(h.at)} handed to ${h.callee}, which may change or free $into while the view is in use: C++ passes a pointer into that storage, which the call may move. Pass $copy"
            } else {
                "the view of $of handed to ${h.callee}, which may change or free $of while the view is in use: C++ passes a pointer into that storage, which the call may move. " +
                    (if (own) "Take the view after the call, or copy $of first" else "Pass $copy")
            }
            HitKind.SIBLING ->
                "$view, read by the call to ${h.callee} after $cause among its arguments, which may change or free $into: C++ reads the view where it pointed before. " +
                    "Evaluate that argument into a local first, then take the view, or pass $copy"
            HitKind.LOOP ->
                "the loop over ${KiraUnparser.text((h.at as? ForIterationStatement)?.forIterationExpr?.target ?: h.at)}, a view of $into, while its body may change or free that storage ($cause): a C++ range-for keeps pointers into it. " +
                    "Iterate over a local copy instead (`items: List<T> = ...` of what the view shows, then `for ... in items`)"
            HitKind.RETURN -> {
                val local = source.locals.firstOrNull()?.name ?: (source.place?.root() as? Place.Local)?.sym?.name ?: of
                "the returned view ${KiraUnparser.text(h.at)} points into storage reached through the local $local, whose handle is dropped as the function returns: " +
                    "when $local is that object's only owner, the view points into freed memory. Return a copy of what the view shows (a List<T>), or view storage the caller passed"
            }
            HitKind.CAPTURE ->
                "$view, captured by a lambda that may run after $cause, which may change or free $into: the lambda keeps the view pointing where it was. " +
                    "Capture a local copy of what the lambda needs instead, or take the view inside the lambda"
            HitKind.READ -> if (source.place == null) {
                "$view, which may point into $into, read after $cause, which may change or free that storage: C++ keeps the view pointing where it was, which the effect may move. " +
                    "Take the view after $cause, or take $copy"
            } else {
                "the view ${h.view}, taken of $of, read after $cause, which may change or free $of: C++ keeps the view pointing into that storage, which the effect may move. " +
                    "Take the view after $cause" + (if (own) "" else ", or take $copy")
            }
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
        // The statement part (W2.3 9e00cfb and later) copies such a range while the temporary lives, and
        // refuses a view of one: the loop iterates its own copy, which nothing in the body can reach.
        if (temporaryRange(target) != null) {
            return null
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
     * C++ spells as a reference into what it is reached from. `for s in makeItem().labels` was
     * `for(const kira::Str& s : makeItem()->labels)`, and the `kira::Rc` temporary died at the
     * end of the range's initializer, freeing the Item before the first iteration (g++ printed
     * freed heap bytes; MSVC ASan: heap-use-after-free). The statement part now copies such a
     * range in the range expression (`kira::List<kira::Str>(makeItem()->labels)`,
     * `CppHoister.rangeMayDangle`), and refuses a view of one, so [loopRefusal] leaves every
     * such loop to it: the copy is the loop's own, which its body cannot change. A temporary
     * that is the range itself, or a struct's field reached by value, is kept alive by the
     * range-for's reference.
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
        val during = Walker(summary = true, fn = null, owner = null).calleeEffects(rc, n.name)
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
        is BlockEnd -> "the end of the block that declares ${cause.local.name}"
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
    private fun movesBuffer(e: Effects, source: ViewSource, keeps: Keeps): Boolean {
        // A block's end destroys its locals: storage in one, or reached through its handle, is gone.
        val dropped = source.place?.let { p -> (p.root() as? Place.Local)?.sym?.let { it in e.drops } } ?: source.locals.any { it in e.drops }
        return dropped || (source.place?.let { movesBuffer(e, it, source.type, keeps) } ?: (movesReached(e) || e.writes.any { w -> !w.inside && source.locals.any { it === w.at } }))
    }

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
                // Nothing but the body itself reaches its own local's storage, and it writes it as that local.
                at is LocalSymbol -> false
                w.at is FieldSymbol && at is FieldSymbol -> fieldsNest(w.at, w.type, at, type)
                else -> overlaps(w.type, type)
            }
            there && (hasParam(type) || hasParam(w.type) || w.type == KType.Error || type in nested(w.type) || (!w.inside && w.type == type && reallocates(type)))
        }
    }

    /**
     * Whether effects [e] may move or free storage reached through a handle, `this`, a global or
     * a call's result, wherever that storage is ([ViewSource] without a place): anything the
     * analysis cannot see, or a write, anywhere but the body's own locals, of a type that moves
     * a buffer when replaced or grown ([reallocates]) or that holds an object (replacing it may
     * free what the view points into, and whatever its `finally` does is among the writes).
     * The objects the handle itself reaches stay alive while it holds them, so a release that
     * writes nothing (a local dropped, a construction) cannot free them.
     */
    private fun movesReached(e: Effects): Boolean =
        e.any || e.writes.any { w -> w.at !is LocalSymbol && (w.type == KType.Error || hasParam(w.type) || reallocates(w.type) || ownsObjects(w.type)) }

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

        /** The first effect that may reach each of [paramHits]. */
        val firstHits = IdentityHashMap<ParamSymbol, ASTNode>()

        /** The first read of each tracked parameter after an effect that may free or move what it names ([invalidates]), with that effect. */
        val invalidHits = IdentityHashMap<ParamSymbol, Pair<ASTNode, ASTNode>>()

        /** The first read of each tracked parameter's value (not a view of it) after an effect that may change it, with that effect. */
        val valueHits = IdentityHashMap<ParamSymbol, Pair<ASTNode, ASTNode>>()

        /** The first effect that may free an object before a read of the receiver, when there is one. */
        var thisReleasedBy: ASTNode? = null

        /**
         * Each local that holds a view, or can hold one ([holdsViews]: a view, a container or a
         * struct of views, a `Ref` box of one), with the storage each view it holds may point
         * into and how many events came before that view was taken.
         */
        private val viewLocals = IdentityHashMap<LocalSymbol, MutableList<Pair<ViewSource, Int>>>()

        /** The view locals a lambda captures, with what each held then: the lambda may run at any later point of the body. */
        private val captures = mutableListOf<Triple<LambdaExpr, LocalSymbol, List<Pair<ViewSource, Int>>>>()

        /** The same for a lambda that runs only where the local it was given to is called ([calledInPlace]): checked at each such call. */
        private val inPlaceCaptures = IdentityHashMap<LocalSymbol, MutableList<Triple<LambdaExpr, LocalSymbol, List<Pair<ViewSource, Int>>>>>()

        /** What [views] found: a view read, or handed to a call, while an effect may move or free what it points into. */
        val viewHits = mutableListOf<ViewHit>()
        private val viewReported: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

        /** What may have happened before the node being walked. */
        private var now = Effects()

        /** The lambdas written as arguments of the call being walked, each with the effects its callee may run around it. */
        private val lambdaRuns = IdentityHashMap<LambdaExpr, Effects>()

        /** The lambdas among those a runtime method runs while it works and keeps no more ([runsNow]): they capture nothing that outlives the call. */
        private val runOnce: MutableSet<LambdaExpr> = Collections.newSetFromMap(IdentityHashMap())

        /** How many lambdas the walk is inside, walked where a call runs them ([runLambda]). */
        private var inLambda = 0

        fun run(statements: List<ASTNode>): Walker {
            statements.forEach(::node)
            checkCaptures()
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
            if (paramHits.add(p)) {
                events.firstOrNull { affects(it.first, p.type) }?.let { firstHits[p] = it.second }
            }
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
                is LambdaExpr -> {
                    lambdaRuns[n]?.let { runLambda(n, it) }
                    if (n !in runOnce) {
                        capture(n)
                    }
                }
                is FunctionCallExpr -> call(n)
                is ObjectInitExpr -> {
                    children(n)
                    event(construction(n), n)
                }
                is AssignmentExpr -> {
                    node(n.value)
                    val place = model.place(n.target)
                    // A local written whole is not read (a view local given a new view is not a read of the old one).
                    if (place !is Place.Local) {
                        node(n.target)
                    }
                    event(write(place, model.typeOrNull(n.target)), n)
                    if (place is Place.Local) {
                        takeView(place.sym, n.value)
                    } else {
                        storeView(place, n.value)
                    }
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
                    storeView(model.place(n.target), n.value)
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
                is ForIterationStatement -> loop(n)
                is WhileIterationStatement -> repeat(2) {
                    node(n.condition)
                    block(n.statements)
                }
                is DoWhileIterationStatement -> repeat(2) {
                    block(n.statements)
                    node(n.condition)
                }
                is IfSelectionStatement -> {
                    node(n.expr)
                    block(n.thenStatements)
                    n.elseBranches.forEach { b ->
                        when (b) {
                            is ElseIfBranchStatement -> {
                                node(b.condition)
                                block(b.statements)
                            }
                            is ElseBranchStatement -> block(b.statements)
                        }
                    }
                }
                is IfExpr -> {
                    node(n.condition)
                    block(n.thenBranch)
                    block(n.elseBranch)
                }
                is TryExpr -> {
                    block(n.tryBlock)
                    block(n.handlerBlock)
                }
                is ReturnStatement -> {
                    children(n)
                    returned(n.expr)
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

        /**
         * The statements of a block, then its end: C++ destroys each local the block declares
         * (and [also], a loop's variable, at the end of each step), so a view of one, or of an
         * object only its handle kept, dangles past it (`if c { x: Bag = makeBag(); v =
         * x.items.view() }`, then `v.get(1)`: MSVC ASan, heap-use-after-free). Only a view
         * check records the end.
         */
        private fun block(statements: List<Statement>, also: List<LocalSymbol> = emptyList()) {
            statements.forEach(::node)
            if (views == null) {
                return
            }
            val declared = statements.mapNotNull { s -> ((s as? VariableDecl) ?: (s.expr as? VariableDecl))?.let { model.declSymbol(it) as? LocalSymbol } } + also
            declared.forEach { local -> event(Effects().also { it.drops.add(local) }, BlockEnd(local)) }
        }

        /**
         * [local] now holds [value]: a view of storage the body does not own is followed from
         * here ([viewLocals]). A box others may hold ([sharesViews]: a `Ref<View<T>>`) can be
         * given a view by any of them, so what it holds may point anywhere from here on.
         */
        private fun takeView(local: LocalSymbol, value: Expr) {
            if (views == null || !holdsViews(local.type)) {
                return
            }
            val sources = viewSources(value).toMutableList()
            if (sharesViews(local.type)) {
                sources += ViewSource(null, local.type, value, localRoots(value), box = local.name) to events.size
            }
            if (sources.isEmpty()) {
                viewLocals.remove(local)
            } else {
                viewLocals[local] = sources
            }
        }

        /** [value] is stored into [place], inside a local that holds views (`vs[0] = v`, `r.value = v`): the local holds what it points into as well. */
        private fun storeView(place: Place?, value: Expr) {
            val local = (place?.root() as? Place.Local)?.sym ?: return
            if (views != null && holdsViews(local.type)) {
                addViews(local, viewSources(value))
            }
        }

        private fun addViews(local: LocalSymbol, sources: List<Pair<ViewSource, Int>>) {
            val held = viewLocals.getOrPut(local) { mutableListOf() }
            sources.forEach { s -> if (held.none { it.first === s.first }) held += s }
        }

        /** The local [x] is, or lies in by value (`vs`, `vs[0]`, `p.views`), when it has one. */
        private fun rootLocal(x: Expr): LocalSymbol? = (model.place(x)?.root() as? Place.Local)?.sym

        /** The body's locals [x] reads: a view reached through one of their handles stays alive until that local is written whole. */
        private fun localRoots(x: Expr): Set<LocalSymbol> {
            val out = identitySet<LocalSymbol>()
            AstTree.walk(x) { node ->
                if (node is Identifier) {
                    (model.symbolOf(node) as? LocalSymbol)?.let { if (reachesThroughHandle(it.type)) out.add(it) }
                }
            }
            return out
        }

        /**
         * The storage [x] names in place, when a view into it can be taken there (it owns a
         * buffer). A parameter copied at entry is a local copy the body never writes, unless the
         * storage lies through a handle it holds; anything else may move under the view.
         */
        private fun storage(x: Expr): List<Pair<ViewSource, Int>> {
            val keeps = views ?: return emptyList()
            val p = model.place(x) ?: return emptyList()
            val t = model.typeOrNull(x) ?: return emptyList()
            val root = p.root()
            if (!ownsBuffer(t) || (root is Place.Param && !root.sym.byRef && root.sym in keeps.keptParams && !throughReference(p))) {
                return emptyList()
            }
            return listOf(ViewSource(p, t, x) to events.size)
        }

        /**
         * What a view made from the operand [x] (a receiver or an argument of a call whose
         * result can hold a view) may point into: [x]'s own storage, what [x] is or holds a view
         * of, and, when [x] is or holds a handle (`b` of `viewOf(b)`, `b.all()`), anything
         * reached through it.
         */
        private fun reach(x: Expr): List<Pair<ViewSource, Int>> {
            val t = model.typeOrNull(x) ?: KType.Error
            val out = mutableListOf<Pair<ViewSource, Int>>()
            out += storage(x)
            if (holdsViews(t) || model.coercion(x) is Coercion.ToView) {
                out += viewSources(x)
            }
            if (reachesThroughHandle(t)) {
                out += ViewSource(null, t, x, localRoots(x)) to events.size
            }
            return out
        }

        /**
         * The storage a view-valued [e] may point into, each with how many events came before
         * the view was taken: what it takes a view of in place, the views a local it reads holds,
         * and for a call, what its receiver, its arguments, an implicit `this`, the Fx it runs
         * and (for a Kira callee, when the program has a global that holds storage) the globals
         * may lead to. A view read out of storage through a handle, a parameter or a global may
         * point anywhere.
         */
        private fun viewSources(e: Expr): List<Pair<ViewSource, Int>> {
            if (views == null) {
                return emptyList()
            }
            val out = mutableListOf<Pair<ViewSource, Int>>()
            val t = model.typeOrNull(e)
            if (model.coercion(e) is Coercion.ToView) {
                out += storage(e)
            }
            val call = e as? FunctionCallExpr ?: (e as? MemberAccessExpr)?.member as? FunctionCallExpr
            val place = model.place(e)
            when {
                e is Identifier -> (model.symbolOf(e) as? LocalSymbol)?.let { local -> viewLocals[local]?.let(out::addAll) }
                call != null -> model.call(call)?.let { rc ->
                    val result = t ?: rc.returnType
                    if (holdsViews(result)) {
                        rc.receiver?.let { out += reach(it) }
                        if (rc.implicitThis) {
                            out += ViewSource(null, result, e) to events.size
                        }
                        rc.args.forEach { a -> if (a is ArgBinding.Given) out += reach(a.expr) }
                        if (rc.kind == CallKind.FN_VALUE) {
                            out += reach(call.name)
                        }
                        if (rc.kind != CallKind.MAGIC && globalsHoldStorage) {
                            out += ViewSource(null, result, e) to events.size
                        }
                    }
                }
                e is ObjectInitExpr -> AstTree.children(e).filterIsInstance<Expr>().forEach { out += viewSources(it) }
                // Each branch's value, the expression of its last statement; one the walk cannot find may be anything.
                e is IfExpr || e is TryExpr -> {
                    val branches = if (e is IfExpr) listOf(e.thenBranch, e.elseBranch) else (e as TryExpr).let { listOf(it.tryBlock, it.handlerBlock) }
                    branches.forEach { b ->
                        val value = b.lastOrNull()?.takeIf { it.javaClass == Statement::class.java }?.expr
                        if (value != null) {
                            out += viewSources(value)
                        } else if (t != null && holdsViews(t)) {
                            out += ViewSource(null, t, e, localRoots(e)) to events.size
                        }
                    }
                }
                t != null && holdsViews(t) && place != null -> {
                    val local = (place.root() as? Place.Local)?.sym
                    if (local != null && !throughReference(place)) {
                        viewLocals[local]?.let(out::addAll)
                    } else {
                        out += ViewSource(null, t, e, localRoots(e)) to events.size
                    }
                }
                // An if-expression, a tuple, an array literal: what any part holds.
                t != null && holdsViews(t) && e !is LambdaExpr -> AstTree.children(e).filterIsInstance<Expr>().forEach { out += viewSources(it) }
                else -> {}
            }
            return out
        }

        /** The first event from [since] on, after each of [sources] was taken, that may move or free what it points into, with the source it moves. */
        private fun firstMove(sources: List<Pair<ViewSource, Int>>, keeps: Keeps, since: Int = 0): Pair<ViewSource, ASTNode>? {
            var best: Pair<ViewSource, ASTNode>? = null
            var bestAt = events.size
            for ((s, from) in sources) {
                for (i in maxOf(from, since) until bestAt) {
                    if (movesBuffer(events[i].first, s, keeps)) {
                        best = s to events[i].second
                        bestAt = i
                        break
                    }
                }
            }
            return best
        }

        /** A read of the view local [local] at [at]: an effect since the view was taken that may move or free what it points into is a hit. */
        private fun readView(local: LocalSymbol, at: ASTNode) {
            val keeps = views ?: return
            val sources = viewLocals[local] ?: return
            if (local in viewReported) {
                return
            }
            firstMove(sources, keeps)?.let { (s, cause) ->
                viewReported.add(local)
                viewHits += ViewHit(at, local.name, s, cause, callee = null)
            }
        }

        /**
         * The body returns [value]: a view in it reached through a local's handle
         * (`return x.items.view()` with `x: Bag` a local, `return x.all()`) points into an object
         * the local may be the only owner of, which C++ frees as the function returns (g++
         * printed freed heap bytes). W2.5's EscapePass refuses a view of a local leaving its
         * function (D5); this does not rely on it for a class's storage.
         */
        private fun returned(value: Expr) {
            // A lambda walked where it runs returns from itself, and its own walk checks what it returns.
            if (views == null || inLambda > 0 || !holdsViews(model.typeOrNull(value) ?: KType.Error)) {
                return
            }
            val s = viewSources(value).map { it.first }.firstOrNull { s ->
                if (s.place == null) s.locals.isNotEmpty() else s.place.root() is Place.Local && throughReference(s.place)
            } ?: return
            viewHits += ViewHit(value, null, s, value, callee = null, kind = HitKind.RETURN)
        }

        /** The lambda [l] may run at any later point of the body: each view local it captures is checked against every effect after the view was taken ([checkCaptures]). */
        private fun capture(l: LambdaExpr) {
            if (views == null) {
                return
            }
            val captured: MutableSet<LocalSymbol> = identitySet()
            model.captures(l)?.forEach { c -> ((c as? Capture.Value)?.symbol as? LocalSymbol)?.let { captured.add(it) } }
            l.def.body?.forEach { s -> AstTree.walk(s) { node -> if (node is Identifier) (model.symbolOf(node) as? LocalSymbol)?.let { captured.add(it) } } }
            val runner = lambdaLocals.entries.firstOrNull { it.value === l }?.key?.takeIf { it in calledInPlace }
            captured.forEach { local ->
                viewLocals[local]?.let { held ->
                    val c = Triple(l, local, held.toList())
                    if (runner != null) inPlaceCaptures.getOrPut(runner) { mutableListOf() } += c else captures += c
                }
            }
        }

        /** The call [n] runs the lambda a local was given ([inPlaceCaptures]): each view it captured is read now. */
        private fun runCaptured(n: FunctionCallExpr) {
            val keeps = views ?: return
            val local = (n.name as? Identifier)?.let { model.symbolOf(it) } as? LocalSymbol ?: return
            inPlaceCaptures[local]?.forEach { (l, view, sources) ->
                if (view !in viewReported) {
                    firstMove(sources, keeps)?.let { (s, cause) ->
                        viewReported.add(view)
                        viewHits += ViewHit(l, view.name, s, cause, callee = null, kind = HitKind.CAPTURE)
                    }
                }
            }
        }

        private fun checkCaptures() {
            val keeps = views ?: return
            for ((l, local, sources) in captures) {
                if (local in viewReported) {
                    continue
                }
                firstMove(sources, keeps)?.let { (s, cause) ->
                    viewReported.add(local)
                    viewHits += ViewHit(l, local.name, s, cause, callee = null, kind = HitKind.CAPTURE)
                }
            }
        }

        /**
         * A range-for, walked twice so the next iteration sees what the last one did. Over a
         * view (`for s in b.items.view()`, `for s in b.all()`), a body that may move or free what
         * the view points into leaves the loop's pointers dangling ([HitKind.LOOP]); a loop
         * variable that holds a view holds what the range's views point into.
         */
        private fun loop(n: ForIterationStatement) {
            val target = n.forIterationExpr.target
            val keeps = views
            val t = model.typeOrNull(target)
            val overView = keeps != null && (model.coercion(target) is Coercion.ToView || (t is KType.Nominal && (t.sym as? ClassSymbol)?.let { it.kind == ClassKind.MAGIC && it.name in VIEWS } == true))
            var sources: List<Pair<ViewSource, Int>> = emptyList()
            // The events of the body's walks: C++ evaluates the range once, so its own second walk is no effect under the loop.
            val inBody = mutableListOf<Int>()
            repeat(2) { i ->
                node(target)
                if (i == 0 && keeps != null) {
                    if (overView) {
                        sources = viewSources(target)
                    }
                    (model.loop(n)?.variable as? LocalSymbol)?.let { v ->
                        if (holdsViews(v.type)) {
                            viewLocals[v] = viewSources(target).toMutableList()
                        }
                    }
                }
                val start = events.size
                block(n.body, listOfNotNull(model.loop(n)?.variable as? LocalSymbol))
                (start until events.size).forEach { inBody += it }
            }
            if (keeps != null && overView) {
                inBody.firstNotNullOfOrNull { i -> sources.firstOrNull { (s, _) -> movesBuffer(events[i].first, s, keeps) }?.let { it.first to events[i].second } }
                    ?.let { (s, cause) -> viewHits += ViewHit(n, null, s, cause, callee = null, kind = HitKind.LOOP) }
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
            val callee = calleeEffects(rc, n.name)
            val lambdaArgs = rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr as? LambdaExpr }
            lambdaArgs.forEach { lambdaRuns[it] = callee }
            if (runsNow(rc)) {
                runOnce.addAll(lambdaArgs)
            }
            val argStart = events.size
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
            views?.let { keeps -> checkHanded(n, rc, e, argStart, keeps) }
            views?.let { keepViews(n, rc) }
            event(e, n)
            if (rc.kind == CallKind.FN_VALUE) {
                runCaptured(n)
            }
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

        /**
         * The views the call [n] reads, its receiver's and its arguments': one made before the
         * arguments (a view local, a view receiver) that a later argument may move or free is
         * read by the call where it pointed before (`v.get(b.growAndGet())`, `at(v, grow())`,
         * [HitKind.SIBLING]); one the callee [e] may move or free while it runs is handed to it
         * ([HitKind.HANDED]). A view the arguments make themselves is the statement part's to
         * order: it reads such an operand after its siblings (D33).
         */
        private fun checkHanded(n: FunctionCallExpr, rc: ResolvedCall, e: Effects, argStart: Int, keeps: Keeps) {
            val name = rc.fn?.name ?: KiraUnparser.text(n.name)
            val used = mutableListOf<Expr>()
            rc.receiver?.let { r -> if (holdsViews(model.typeOrNull(r) ?: KType.Error)) used += r }
            rc.args.forEach { a ->
                if (a is ArgBinding.Given && !a.byRef && (model.coercion(a.expr) is Coercion.ToView || holdsViews(model.typeOrNull(a.expr) ?: KType.Error))) {
                    used += a.expr
                }
            }
            for (x in used) {
                val sources = viewSources(x)
                val local = (x as? Identifier)?.let { model.symbolOf(it) as? LocalSymbol }
                val early = firstMove(sources.filter { (_, from) -> from <= argStart }, keeps, since = argStart)
                if (early != null) {
                    if (local == null || viewReported.add(local)) {
                        viewHits += ViewHit(x, local?.name, early.first, early.second, callee = name, kind = HitKind.SIBLING)
                    }
                    continue
                }
                sources.firstOrNull { (s, _) -> movesBuffer(e, s, keeps) }?.let { (s, _) ->
                    viewHits += ViewHit(x, null, s, n, callee = name, kind = HitKind.HANDED)
                }
            }
        }

        /**
         * A call that may store a view into a local that holds views: a runtime method's receiver
         * (`vs.add(b.items.view())`), a `mut` argument (`point(mut v, b)`), or a box others may
         * hold ([sharesViews]: `stash(xs, b, r)` with `r: Ref<View<Int32>>`). The local then
         * holds what any operand may lead to. What a Kira callee stores there is followed into
         * class storage: what its operands reach through a handle, `this`, or a place through a
         * reference. A view it stores of a local or a global the caller then grows is W2.3's open
         * decision (its OD-3: a view held across a growth), and the union it would need cannot
         * tell a `mut` argument the callee replaces from one it keeps (`keep<View<Int32>>(mut h,
         * gl, growL())` replaces `h.v`, and the evalorder golden reads it).
         */
        private fun keepViews(n: FunctionCallExpr, rc: ResolvedCall) {
            val into: MutableSet<LocalSymbol> = identitySet()
            rc.receiver?.let(::rootLocal)?.let { l -> if ((rc.fn?.isMutMethod == true && holdsViews(l.type)) || sharesViews(l.type)) into.add(l) }
            rc.args.forEach { a ->
                if (a is ArgBinding.Given) {
                    rootLocal(a.expr)?.let { l -> if ((a.byRef && holdsViews(l.type)) || sharesViews(l.type)) into.add(l) }
                }
            }
            if (into.isEmpty()) {
                return
            }
            val stored = mutableListOf<Pair<ViewSource, Int>>()
            rc.args.forEach { a -> if (a is ArgBinding.Given) stored += reach(a.expr) }
            if (rc.kind != CallKind.MAGIC) {
                rc.receiver?.let { stored += reach(it) }
                if (rc.implicitThis) {
                    stored += ViewSource(null, KType.Error, n) to events.size
                }
                stored.retainAll { (s, _) -> s.place == null || throughReference(s.place) }
            }
            into.forEach { addViews(it, stored) }
        }

        /** Whether a runtime method runs the lambdas it is handed while it works and keeps none (a container's `forEach`), where a system class's may keep one (a thread's body). */
        private fun runsNow(rc: ResolvedCall): Boolean {
            val fn = rc.fn ?: return false
            val receiverType = rc.receiver?.let { model.typeOrNull(it) }
            return rc.kind == CallKind.MAGIC && !isSystem(fn.owner) && !(receiverType != null && mentions(receiverType) { isSystem(it) }) &&
                CppTypeSpeller.systemHeaderFor(fn.module.uri) == null
        }

        /** A lambda handed to a call: it may run during the call, after what the callee does and after its own earlier runs. */
        private fun runLambda(l: LambdaExpr, callee: Effects) {
            val body = l.def.body ?: return
            val before = now
            now = before.copy().also { s ->
                s.addAll(callee)
                summaries[body]?.let(s::addAll)
            }
            inLambda += 1
            body.forEach(::node)
            inLambda -= 1
            before.addAll(now)
            now = before
        }

        /** What the call [rc] may run; [callee] is its callee expression (for a call through an `Fx` value, the value). */
        fun calleeEffects(rc: ResolvedCall, callee: Expr? = null): Effects = when (rc.kind) {
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
            // A local never reassigned that was given a lambda runs that lambda; any other Fx value, anything the pool may.
            CallKind.FN_VALUE -> ((callee as? Identifier)?.let { model.symbolOf(it) } as? LocalSymbol)?.let { lambdaLocals[it] }?.def?.body?.let { summaries[it] } ?: fxPool
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

        /** The receiver of the method being analyzed, as a root of [viewRoots] beside its parameters. */
        private val THIS = Any()
    }
}
