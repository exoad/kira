package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
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
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
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
 *    some effect of the body may reach before the body reads it, or before a lambda captures it,
 *    is copied at entry ([Guard.snapshots]). The copy is the value the caller passed, as Kira's
 *    parameters are values (`c.set(d.name)` with `set` renaming `c` gives `a`, as the C and JS
 *    backends do). A template `Fx` parameter is copied the same way.
 * 2. `this` in a method. Reached through a field, an element or `unwrap()`, the object can lose
 *    its last owner while its method runs (`t.kids[0].leave()` with `leave` calling
 *    `tree.clear()`). A class method that reads its receiver after an effect that may free an
 *    object (a lambda capturing it included), or keeps a reference into it while such an effect
 *    runs (its field passed `mut`, the receiver of a call whose arguments free it:
 *    `labels.add(h.take())`), holds itself for the call (`weak_from_this().lock()`,
 *    [Guard.holdsThis]), and the root of its chain then derives `kira::Shared`. A trait's
 *    default body cannot hold itself (a trait has no `shared_from_this`), so it is refused. A
 *    parameter whose object is used the same way is copied at entry, which holds that object.
 * 3. A range-for over a field or an element of an object: an effect in the loop body that may
 *    change that storage invalidates the iterators (`for s in b.items { c.grow() }` with
 *    `c = b`, measured: freed heap bytes). Refused ([refusals]), as are a `mut` argument that
 *    names such storage when the call may change it, or lies inside another `mut` argument of
 *    the call, a call through an `Fx` such storage holds while the code it runs may replace
 *    it, a lambda's `const&` parameter read after an effect that may reach it, an argument
 *    or a receiver such storage holds that a C++-supplied callee reads after running an `Fx`
 *    that may change it, and a type-parameter receiver in such storage its call's arguments
 *    may change (C++ reads it after them): each of
 *    those lowerings is the statement part's, and each diagnostic names the local copy that
 *    makes the program safe. A range reached from a temporary is the statement part's, which
 *    copies it (W2.3 `CppHoister.rangeMayDangle`). A field's default is checked as a body is.
 *
 * Views (`View`, `MutView`, `CStr`, `Unsafe`) are second-class (decision 4b,
 * 30-second-class.md): W2.5's ViewPass refuses every use of one outside an argument, a
 * receiver or a return from the function's own view parameters or receiver, and every write
 * that could move the viewed storage while the view is in use. So nothing here follows a view,
 * and a copy at entry is always safe for one: a view of the copy is used only within the
 * body's own full-expressions, and a view the call hands back never points into a copy (a view
 * parameter passes by value, and a view of a non-view parameter cannot be returned).
 *
 * Effects are decided over the whole program ([summaries]): the fields, globals and `mut`
 * parameters each body may overwrite, with the types written there, and whether it may free an
 * object (drop a handle, or run a `finally`). A call's effects are its callee's: every body of
 * the name for a virtual or trait call, and every lambda and function value for a call through
 * an `Fx` (the lambda itself, for a local that is not `mut` and was given one). What C++
 * supplies (a C++ override of a trait method, an `Fx` built in C++, an `@_extern`, a bodyless
 * `pub` prototype a C++ file defines, an `@_opaque` method) writes Kira storage only through
 * its `mut` arguments and runs Kira code only through what it is given, and reads its
 * arguments during the call: the boundary contract of 30-second-class.md 5.4, recorded in
 * `docs/cpp-known-issues/w2-4-emit-oop.md`. So such a call may run any `Fx` its arguments
 * reach (40-round3 R-C, W2.5's `CallReach.mayHoldFx`: a lambda written there runs its body,
 * any other value that may hold an `Fx` runs everything), and an argument it reads, and the
 * receiver it runs on, is in use until it returns.
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

    /** One body the summaries cover: a function or method, a lambda, an `initially`, a `finally`, or a field's default. */
    private class Body(val statements: List<ASTNode>, val fn: FnSymbol?, val owner: TypeSymbol?)

    private val bodies = mutableListOf<Body>()
    private val lambdas = mutableListOf<LambdaExpr>()

    /** The locals that are not `mut` and are given a lambda where they are declared: a call through one runs that lambda. */
    private val lambdaLocals = IdentityHashMap<LocalSymbol, LambdaExpr>()
    private val fnValues: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /** Each body's effects, keyed by its statement list (identity): a fixpoint over the call graph. */
    private val summaries = IdentityHashMap<List<ASTNode>, Effects>()

    /**
     * Each field default as a body of its own ([summaries]): a construction that leaves the
     * field out runs it, inside the constructor (R-D and OQ-2: after the superclass's
     * `initially`), so the construction's effects carry it ([Walker.construction]).
     */
    private val defaultBodies = IdentityHashMap<FieldSymbol, List<ASTNode>>()

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
                    sym.fields.forEach { f ->
                        f.default?.let { d ->
                            val body = listOf<ASTNode>(d)
                            defaultBodies[f] = body
                            bodies += Body(body, null, sym)
                        }
                    }
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

    /**
     * Whether C++ may supply the body the call [rc] runs (40-round3 R-G, read here as its
     * wider question): C++ supplies it ([FnSymbol.suppliedByCpp], W2.5's one predicate: an
     * `@_extern`, a bodyless `pub` prototype, a bodyless `@_opaque` method), or the call is
     * dispatched and a C++ class may override it (a virtual or trait method: the chain golden's
     * test double). Such a callee reads its arguments during the call, after any Kira code it
     * runs ([cppRuns]), and copies none of them at entry.
     */
    private fun mayBeSuppliedByCpp(rc: ResolvedCall): Boolean =
        rc.kind == CallKind.EXTERN || rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT || rc.fn?.suppliedByCpp == true

    /** C++ supplies the body itself, with no Kira override beside it ([FnSymbol.suppliedByCpp], or the EXTERN kind). */
    private fun suppliedDirectly(rc: ResolvedCall): Boolean = rc.kind == CallKind.EXTERN || rc.fn?.suppliedByCpp == true

    /**
     * What C++ may run of Kira's while the call [rc] runs, when it may supply the body
     * ([mayBeSuppliedByCpp]) or the call goes through an `Fx` value the analysis cannot see
     * into: null when no Kira code can run. R-C (40-round3), with W2.5's one predicate
     * [CallReach.mayHoldFx]: a callee C++ supplies may run any `Fx` it reaches through what it
     * is given (contract 5.4.3), not only an argument of a function type. So each given
     * argument contributes: a lambda written there, its body (C++ can reach nothing else
     * through a closure: what it captures is the lambda's, and its body's summary covers it);
     * a function named as a value, its body; any other value whose type [CallReach.mayHoldFx]
     * (an `Fx` value, a class or trait handle, a container, `Maybe`, tuple or struct of one,
     * a type parameter), everything. A body C++ supplies directly may also run what its
     * receiver holds ([suppliedDirectly]: an `@_opaque` or extern method); a dispatched call's
     * receiver is the object whose override runs, left to OD-2's contract (a C++ override runs
     * Kira code only through its arguments), and a default never counts (D48: a constant).
     * Measured before R-C (second-class round 2): externnested's `Wrap` holding an `Fx` field
     * and externarr's `Arr<Fx>` were not seen, a heap-use-after-free under MSVC ASan each.
     */
    private fun cppRuns(rc: ResolvedCall): Effects? {
        var e: Effects? = null
        fun add(x: Effects) {
            e = (e ?: Effects()).also { it.addAll(x) }
        }
        for (a in rc.args) {
            val x = (a as? ArgBinding.Given)?.expr ?: continue
            val body = when (x) {
                is LambdaExpr -> x.def.body
                is Identifier -> (model.symbolOf(x) as? FnSymbol)?.body
                else -> null
            }
            when {
                body != null -> add(summaries[body] ?: Effects.unknown())
                CallReach.mayHoldFx(model.typeOrNull(x)) -> add(Effects.unknown())
            }
        }
        if (suppliedDirectly(rc) && (rc.implicitThis || rc.receiver?.let { CallReach.mayHoldFx(model.typeOrNull(it)) } == true)) {
            add(Effects.unknown())
        }
        return e
    }

    // ---- queries ---------------------------------------------------------------------------------------

    /**
     * What a definition of [fn] guards before its body. [snapshots] are the parameters some
     * effect of the body may reach before the body reads them (or a lambda captures them), of a
     * type an object can hold, whichever way C++ passes them (the caller keeps those it passes
     * by reference). [holdsThis] is true when the body reads its receiver (a lambda capturing it
     * included) after an effect that may free an object, or keeps a reference into it while one
     * runs (a `mut` argument, the receiver of a call whose arguments do); [releasedBy] is the
     * first such effect.
     */
    class Guard(
        val snapshots: List<ParamSymbol>,
        val holdsThis: Boolean,
        val releasedBy: ASTNode?,
    ) {
        companion object {
            val NONE = Guard(emptyList(), false, null)
        }
    }

    private val guards = IdentityHashMap<FnSymbol, Guard>()

    fun guard(fn: FnSymbol): Guard = guards.getOrPut(fn) {
        val body = fn.body ?: return@getOrPut Guard.NONE
        val w = Walker(summary = false, fn = fn, owner = fn.owner, tracked = fn.params).run(body)
        val snapshots = fn.params.filter { p -> !p.byRef && p in w.paramHits && holdable(p.type) }
        Guard(snapshots, w.thisReleasedBy != null, w.thisReleasedBy)
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
     * through an `Fx` such storage holds while the code it runs may replace or free it; and a
     * lambda's `const&` parameter read after an effect that may reach it (a lambda is the
     * statement part's, and copies nothing at entry). [fn] and [owner] are the body's function
     * (null for `initially`, `finally` and a field's default) and type.
     */
    fun refusals(nodes: List<ASTNode>, fn: FnSymbol?, owner: TypeSymbol?): List<Pair<ASTNode, String>> {
        val out = mutableListOf<Pair<ASTNode, String>>()
        val keeps = keepsOf(fn, owner)
        nodes.forEach { s ->
            AstTree.walk(s) { node ->
                when (node) {
                    is ForIterationStatement -> loopRefusal(node, fn, owner, keeps)?.let { out += node to it }
                    is FunctionCallExpr -> {
                        out += mutArgRefusals(node, keeps).map { node to it }
                        out += cppCalleeArgRefusals(node, keeps).map { node to it }
                        paramReceiverRefusal(node, fn, owner, keeps)?.let { out += node to it }
                        fxCallRefusal(node, keeps)?.let { out += node to it }
                    }
                    is LambdaExpr -> out += lambdaParamRefusals(node, owner)
                    else -> {}
                }
            }
        }
        return out
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
        val place = model.readPlace(callee) ?: return null
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

    /**
     * The by-value argument kinds W2.6 copies at a call C++ supplies whenever that call may
     * write them while it runs (40-round3 R-B, the copy that replaces [cppCalleeArgRefusals]
     * for them): a `Str`, a container, a `Maybe`, `Result` or tuple, and a value class (a
     * struct here; an immutable class after W2.9). A class, trait, `Ref` or `Weak` handle is not
     * one of them.
     */
    private fun copiedAtTheCall(t: KType): Boolean = when (t) {
        KType.Str -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.STRUCT -> true
                ClassKind.MAGIC -> sym.name in COPIED_AT_THE_CALL || sym.name.startsWith("Tuple")
                else -> false
            }
            else -> false
        }
        else -> false
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
        // The statement part (W2.3 9e00cfb and later) copies such a range while the temporary lives: the
        // loop iterates its own copy, which nothing in the body can reach. A loop over a view of one is
        // ViewPass's (rules.view.position: a range's origins are view parameters or literals only).
        if (temporaryRange(target) != null) {
            return null
        }
        val place = model.readPlace(target) ?: return null
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
     * `CppHoister.rangeMayDangle`), so [loopRefusal] leaves every
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
        if (!intoIt || e is Identifier || e is ThisExpr || model.readPlace(e) != null) {
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
        // What runs while the callee writes through the argument: the callee, and for a callee C++ supplies the Fx
        // arguments it may run (externmut: `bump(mut h.item.count, fx() Void { c.reset() })` wrote into the Item the
        // callback freed, MSVC ASan heap-use-after-free). A sibling argument's effect comes before the binding, since
        // the statement part locates a mut argument's place after its impure siblings (D33).
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
     * An argument C++ takes by `const&` that names storage an object holds, passed to a callee
     * C++ may supply ([mayBeSuppliedByCpp]) that may run Kira code which changes or frees that
     * storage ([cppRuns]): the C++ callee reads the reference while it runs, after the callback
     * (externstr's shape with the argument a field, `measure(c.item.label, fx() Void {
     * h.reset() })`). A Kira callee copies such a parameter at entry ([guard]); a C++ one
     * copies nothing, and a parameter of the caller passed on is copied at the caller's entry
     * instead ([Walker.call]). A local copy is what makes it safe.
     *
     * Where C++ supplies the body itself ([suppliedDirectly]), W2.6 copies at the call every
     * by-value argument of a kind 40-round3's R-B names ([copiedAtTheCall]), so those are not
     * refused here (R-B replaces this refusal for them). What R-B does not copy stays refused:
     * a class, trait, `Ref` or `Weak` handle in such storage, and every argument of a
     * dispatched call, whose override a C++ class may write.
     */
    private fun cppCalleeArgRefusals(n: FunctionCallExpr, keeps: Keeps): List<String> {
        val rc = model.call(n) ?: return emptyList()
        if (!mayBeSuppliedByCpp(rc) || cppRuns(rc) == null) {
            return emptyList()
        }
        val during = Walker(summary = true, fn = null, owner = null).calleeEffects(rc, n.name)
        val name = rc.fn?.name ?: KiraUnparser.text(n.name)
        return rc.args.filterIsInstance<ArgBinding.Given>().mapNotNull { a ->
            if (a.byRef || a.expr is LambdaExpr) {
                return@mapNotNull null
            }
            val place = model.readPlace(a.expr) ?: return@mapNotNull null
            val type = model.typeOrNull(a.expr) ?: return@mapNotNull null
            if (type is KType.Fn || !passedByReference(type) || kindOf(place) != PlaceKind.SHARED || !reaches(during, place, type, keeps)) {
                return@mapNotNull null
            }
            if (suppliedDirectly(rc) && copiedAtTheCall(type)) {
                return@mapNotNull null
            }
            val text = KiraUnparser.text(a.expr)
            "the argument $text, storage an object holds, passed by const& to $name, whose body C++ supplies and may run the Fx it is given " +
                "before it reads $text, while that code may change or free it: copy it into a local first (`v: ${type.display()} = $text`, then pass v)"
        } + listOfNotNull(cppCalleeReceiverRefusal(rc, name, during, keeps))
    }

    /**
     * The receiver of such a call ([cppCalleeArgRefusals]) when it names storage an object
     * holds that the `Fx` arguments may replace or free: C++ runs the method on the object the
     * slot held when the call began (`c.item.run(fx() Void { h.reset() })`), and a body C++
     * supplies may read that object after the callback freed it. A Kira override holds itself
     * for the call where it must ([guard]); a C++ one cannot be made to. A local copy of the
     * handle keeps the object alive for the call.
     */
    private fun cppCalleeReceiverRefusal(rc: ResolvedCall, name: String, during: Effects, keeps: Keeps): String? {
        val receiver = rc.receiver ?: return null
        val place = model.readPlace(receiver) ?: return null
        val type = model.typeOrNull(receiver) ?: return null
        if (kindOf(place) != PlaceKind.SHARED || place is Place.This || !reaches(during, place, type, keeps)) {
            return null
        }
        val text = KiraUnparser.text(receiver)
        return "the call to $name on $text, storage an object holds, whose body C++ may supply and may run the Fx it is given while $text still " +
            "names the object, though that code may replace or free it: copy it into a local first (`v: ${type.display()} = $text`, then v.$name(...))"
    }

    /**
     * A call on a receiver of type-parameter type (`kira::deref(x).m(...)`) that names storage
     * an object holds (a field, an element, anything through a handle, a `mut` parameter),
     * whose arguments may change or free that storage: C++ reads the receiver after the
     * arguments, so the call would run on what they left there, or on freed memory (genrecv2:
     * `x.greet(h.reset())` with x the element `c.kids[0]` reset replaced; g++ threw
     * std::bad_alloc, MSVC ASan heap-use-after-free). A trait- or class-typed receiver is a
     * handle the statement part copies before the arguments; a type parameter's is spelled
     * by reference, and a value parameter so read is copied at entry ([Walker.call]).
     */
    private fun paramReceiverRefusal(n: FunctionCallExpr, fn: FnSymbol?, owner: TypeSymbol?, keeps: Keeps): String? {
        val rc = model.call(n) ?: return null
        val receiver = rc.receiver ?: return null
        val type = model.typeOrNull(receiver) as? KType.Param ?: return null
        val place = model.readPlace(receiver) ?: return null
        if (kindOf(place) == PlaceKind.LOCAL) {
            return null
        }
        val args = rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }
        val w = Walker(summary = false, fn = fn, owner = owner).run(args)
        if (!reaches(w.all, place, type, keeps)) {
            return null
        }
        val text = KiraUnparser.text(receiver)
        val name = rc.fn?.name ?: "the method"
        return "the call to $name on $text, a ${type.display()} an object holds, whose arguments may change or free it before the call runs: " +
            "C++ reads a type parameter's receiver after the arguments. Copy it into a local first (`v: ${type.display()} = $text`, then v.$name(...))"
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
    ) {
        private val tracked: Set<ParamSymbol> = Collections.newSetFromMap<ParamSymbol>(IdentityHashMap()).also { it.addAll(tracked) }

        /** Everything the walked code may do. */
        val all = Effects()

        /** Each effect in the order met, with the node that has it. */
        val events = mutableListOf<Pair<Effects, ASTNode>>()

        /** The tracked parameters read after an effect that may reach them. */
        val paramHits: MutableSet<ParamSymbol> = Collections.newSetFromMap(IdentityHashMap())

        /** The first effect that may free an object before a read of the receiver, when there is one. */
        var thisReleasedBy: ASTNode? = null

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

        /** A read of [p]: a hit when an effect so far may have reached what it names. */
        private fun useParam(p: ParamSymbol) {
            if (p in tracked && affects(now, p.type)) {
                paramHits.add(p)
            }
        }

        /**
         * The storage [place] lies in is in use again: a `mut` argument the callee writes
         * through while it runs, or the receiver a call runs on after its arguments. `this`
         * or a parameter it is reached from is read at this point of the walk, so a method
         * that frees an object before holds itself, and such a parameter is copied at entry.
         */
        private fun inUse(place: Place?) {
            when (val root = place?.root()) {
                is Place.This -> useThis()
                is Place.Param -> useParam(root.sym)
                else -> {}
            }
        }

        /**
         * The lambda [l] is made here, and C++ copies what it captures now (design 5.6: `[s]`,
         * `[self = shared_from_this()]`, `[this]`), wherever it runs later: each captured
         * parameter is read, and so is `this` for a captured field or receiver. A lambda built
         * after an effect that renamed or freed what a `const&` parameter names captured the
         * change or freed memory (capparam printed `b` where Kira gives `a`; capparamfree was a
         * heap-use-after-free under MSVC ASan), and one capturing `shared_from_this()` of an
         * object freed under its own method threw std::bad_weak_ptr (capthis, g++ and zig).
         */
        private fun captured(l: LambdaExpr) {
            model.captures(l)?.forEach { c ->
                when (c) {
                    is Capture.Value -> (c.symbol as? ParamSymbol)?.let(::useParam)
                    is Capture.Field, is Capture.This -> useThis()
                }
            }
        }

        private fun children(n: ASTNode) = AstTree.children(n).forEach(::node)

        private fun block(statements: List<Statement>) = statements.forEach(::node)

        fun node(n: ASTNode) {
            when (n) {
                // A lambda anywhere but a call's argument runs later: its effects are the Fx pool's.
                is LambdaExpr -> {
                    captured(n)
                    lambdaRuns[n]?.let { runLambda(n, it) }
                }
                is FunctionCallExpr -> call(n)
                is ObjectInitExpr -> {
                    children(n)
                    event(construction(n), n)
                }
                is AssignmentExpr -> {
                    node(n.value)
                    node(n.target)
                    event(write(model.place(n.target), model.typeOrNull(n.target)), n)
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
                    block(n.body)
                }
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

        private fun identifier(id: Identifier) {
            when (val sym = model.symbolOf(id)) {
                is ParamSymbol -> useParam(sym)
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
            n.positionalParameters.forEach(::node)
            n.namedParameters.forEach(::node)
            lambdaArgs.forEach { lambdaRuns.remove(it) }
            // The call runs on its receiver after its arguments (C++ reads `this->labels` or
            // `this` first, then the arguments): an argument that frees the method's object
            // (`labels.add(h.take())`, `put(h.take())`) would leave the call on freed memory.
            if (rc.implicitThis) {
                useThis()
            }
            rc.receiver?.let { r -> if (model.readPlace(r)?.root() is Place.This) useThis() }
            // A receiver of type-parameter type is `kira::deref(x).m(...)`, x bound by reference: C++
            // reads it after the arguments (genrecv: `x.greet(h.reset())` ran greet on what reset left
            // in the caller's slot, a freed Kid under MSVC ASan). A parameter so read is copied at
            // entry; storage an object holds is refused ([paramReceiverRefusal]).
            rc.receiver?.let { r -> if (model.typeOrNull(r) is KType.Param) inUse(model.readPlace(r)) }
            val e = callee.copy()
            rc.args.forEach { a ->
                if (a is ArgBinding.Given && a.byRef) {
                    e.addAll(write(model.place(a.expr), model.typeOrNull(a.expr)))
                }
            }
            event(e, n)
            // A callee C++ may supply reads its arguments while it runs, after the Kira code it may
            // run (externstr: `measure(s, fx() Void { h.reset() })` read s, bound to the Item reset
            // freed: freed heap bytes on g++, MSVC ASan heap-use-after-free; externnested: the same
            // through an Fx field of a Wrap argument, R-C). A parameter so read is copied at entry;
            // storage an object holds is copied at the call by W2.6 (R-B) or refused here
            // ([cppCalleeArgRefusals]). Its receiver too: the object it runs on is in use until it returns.
            if (mayBeSuppliedByCpp(rc) && cppRuns(rc) != null) {
                rc.args.forEach { a ->
                    if (a is ArgBinding.Given && !a.byRef && a.expr !is LambdaExpr) {
                        inUse(model.readPlace(a.expr))
                    }
                }
                rc.receiver?.let { r -> inUse(model.readPlace(r)) }
                if (rc.implicitThis) {
                    useThis()
                }
            }
            // A mut argument is written through while the callee runs, so the object it lies in is in use until the call returns.
            rc.args.forEach { a ->
                if (a is ArgBinding.Given && a.byRef) {
                    inUse(model.place(a.expr))
                }
            }
            // A runtime method handed a function runs it while it works on its receiver.
            if (rc.kind == CallKind.MAGIC && rc.args.any { a -> a is ArgBinding.Given && (a.expr is LambdaExpr || model.typeOrNull(a.expr) is KType.Fn) }) {
                rc.receiver?.let { r -> inUse(model.readPlace(r)) }
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

        /**
         * What the call [rc] may run; [callee] is its callee expression (for a call through an
         * `Fx` value, the value). A callee C++ may supply ([mayBeSuppliedByCpp]: an `@_extern`,
         * a bodyless `pub` prototype, an `@_opaque` method, a C++ override of a virtual or
         * trait method) runs Kira code only through what it is given (contract 5.4.3), so it
         * may run whatever an argument reaches ([cppRuns], R-C). A body the program has not
         * got and C++ does not supply (a bodiless private prototype, which the typer refuses)
         * may do anything.
         */
        fun calleeEffects(rc: ResolvedCall, callee: Expr? = null): Effects = when (rc.kind) {
            CallKind.PRINT -> Effects()
            CallKind.EXTERN -> cppRuns(rc) ?: Effects()
            CallKind.MAGIC -> magic(rc)
            CallKind.FREE, CallKind.METHOD -> rc.fn?.body?.let { summaries[it] } ?: if (suppliedDirectly(rc)) cppRuns(rc) ?: Effects() else Effects.unknown()
            CallKind.VIRTUAL, CallKind.TRAIT -> (cppRuns(rc) ?: Effects()).also { e ->
                val name = rc.fn?.name
                if (name == null) {
                    e.addAll(Effects.unknown())
                } else {
                    methodsNamed[name].orEmpty().forEach { m -> summaries[m.body]?.let(e::addAll) }
                }
            }
            // A local never reassigned that was given a lambda runs that lambda. Any other Fx value runs anything the pool
            // may (every lambda and function of the program used as a value), and, since C++ may have built it (OD-2), also
            // whatever its arguments reach (R-C: a C++ closure handed an object that holds an Fx may call that Fx).
            CallKind.FN_VALUE -> ((callee as? Identifier)?.let { model.symbolOf(it) } as? LocalSymbol)?.let { lambdaLocals[it] }?.def?.body?.let { summaries[it] }
                ?: cppRuns(rc)?.let { e -> e.copy().also { it.addAll(fxPool) } } ?: fxPool
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
                    model.readPlace(receiver)?.let { e.addAll(write(it, receiverType, whole = true)) }
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
            // The defaults of the fields it leaves out run inside the constructors (R-D, OQ-2).
            model.init(n)?.fields?.forEach { f -> if (f is FieldInit.Default) defaultBodies[f.field]?.let { b -> summaries[b]?.let(e::addAll) } }
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
                PlaceKind.LOCAL -> if (hasFinally && ownsObjects(t)) {
                    e.addAll(release())
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

        /** The `@_magic` containers and wrappers R-B copies at a call C++ supplies ([copiedAtTheCall]); a `TupleN` too. */
        private val COPIED_AT_THE_CALL = setOf("List", "Map", "Set", "Deque", "Stack", "Queue", "Arr", "Maybe", "Result")

        /** The `@_magic` classes whose value holds no object: a non-owning handle, a view, a pointer. */
        private val NON_OWNING = setOf("Weak", "Unsafe", "CStr", "View", "MutView")

        private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
    }
}
