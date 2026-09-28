package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.PlaceKind
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.ViewOrigin
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import java.util.IdentityHashMap

/**
 * Views are second-class (decision 4b; the rule is design 30-second-class, whose section
 * numbers the comments here cite). A `View`, `MutView`, `CStr` or `Unsafe` value, and a
 * lambda that captures one, is only ever an argument, a receiver, a return or a `for` range,
 * so no view outlives the call that consumes it. The rule is safe by construction: C++ keeps
 * every temporary of a full-expression to its end, so the one check left is local, that
 * nothing between forming a view and the return of its consumer moves the viewed place.
 *
 * - `rules.view.type` (1.2): a type that holds a second-class type, where it is written or
 *   inferred: a global, a field, a `for` variable, a `mut` parameter, a type argument of any
 *   container, `Maybe`, tuple, `Ref` or user generic class, a construction, an array literal.
 * - `rules.view.local` (2.1): a local whose type is second-class.
 * - `rules.view.generic` (1.3): a generic function instantiated with a second-class `T` that
 *   returns a `T`, stores one, or passes it to a function that does (a fixpoint).
 * - `rules.view.return` (2.2): a returned view that points into anything but the function's
 *   own view parameters, its receiver or a literal.
 * - `rules.view.position` (2.1): a view anywhere but an argument, a receiver, a return or a
 *   `for` over a view parameter or a literal.
 * - `rules.view.capture` (4): a lambda that captures a view parameter and escapes.
 * - `rules.view.store` (2.1): a view passed, assigned or returned where a first-class value
 *   (an `Any`, a trait value) is kept.
 * - `rules.view.write` (3.3): a view of a place formed while a later operand of its consuming
 *   call, or the call itself, may move that place: a named write that may be it, or, when the
 *   place is shared or a `mut` global, any IMPURE call or node (decision 4b read literally:
 *   "any impure call, when the place lies in a mutable class"; [Effects]).
 * - `rules.view.extern` (5.2): an extern that returns a pointer.
 * - `rules.view.unsafe` (1.4): an `Unsafe<T>` anywhere but an extern's parameter.
 *
 * One diagnostic per root cause: at one node only the first code of that order (extern,
 * unsafe, local, type, generic, return, position, capture, store, write) is reported, and a
 * use of a declaration already refused is not reported again. What every second-class
 * expression points into is recorded in `TypedModel.viewOrigins` for the emitter.
 */
internal class ViewPass : RulePass {
    override val name: String = "view"

    override fun run(program: TypedProgram) {
        Check(Rules(program), Bodies.of(program)).run()
    }

    private companion object {
        const val EXTERN = "rules.view.extern"
        const val UNSAFE = "rules.view.unsafe"
        const val LOCAL = "rules.view.local"
        const val TYPE = "rules.view.type"
        const val GENERIC = "rules.view.generic"
        const val RETURN = "rules.view.return"
        const val POSITION = "rules.view.position"
        const val CAPTURE = "rules.view.capture"
        const val STORE = "rules.view.store"
        const val WRITE = "rules.view.write"

        /** Section 6's order: at one node only the first applicable code is reported. */
        val ORDER = listOf(EXTERN, UNSAFE, LOCAL, TYPE, GENERIC, RETURN, POSITION, CAPTURE, STORE, WRITE)

        /** The second-class magic classes (1.1). */
        val SECOND_CLASS = listOf("View", "MutView", "CStr", "Unsafe")
    }

    private class Report(val node: ASTNode?, val code: String, val message: String)

    /** The whole program's check: declarations, then view-safety of generics, then every body. */
    private class Check(val r: Rules, val bodies: List<Body>) {
        val model = r.model
        val facts = r.facts
        val effects = EffectsPass.of(r)
        val reports = mutableListOf<Report>()

        /** Declarations refused here: a use of one is not reported again. */
        val refused: MutableSet<Symbol> = java.util.Collections.newSetFromMap(IdentityHashMap())

        /** For each generic function, why it is not view-safe for each type parameter; no entry is view-safe (1.3). */
        val unsafeFor = IdentityHashMap<FnSymbol, IdentityHashMap<TypeParamSymbol, String>>()
        private val bodyOf = IdentityHashMap<FnSymbol, Body>()
        private val overriders = IdentityHashMap<FnSymbol, MutableList<FnSymbol>>()

        /** The methods with a body that override [fn], directly or further down. */
        fun overridersOf(fn: FnSymbol): List<FnSymbol> = overriders[fn].orEmpty()

        fun run() {
            bodies.forEach { b -> b.fn?.let { bodyOf[it] = b } }
            // Every method with a body, under each method it overrides (a trait method, a superclass method, and theirs).
            for (fn in bodyOf.keys) {
                var base = fn.overrides
                val seen = java.util.Collections.newSetFromMap(IdentityHashMap<FnSymbol, Boolean>())
                while (base != null && seen.add(base)) {
                    overriders.getOrPut(base) { ArrayList() }.add(fn)
                    base = base.overrides
                }
            }
            declarations()
            viewSafety()
            for (b in bodies) {
                BodyCheck(this, b, emptySet(), dry = false).run()
            }
            emit()
        }

        fun report(node: ASTNode?, code: String, message: String) {
            reports.add(Report(node, code, message))
        }

        /** Section 6: one code per node, the first of [ORDER]; reports without a node all stand. */
        private fun emit() {
            val best = IdentityHashMap<ASTNode, Report>()
            for (rep in reports) {
                val node = rep.node ?: continue
                val was = best[node]
                if (was == null || ORDER.indexOf(rep.code) < ORDER.indexOf(was.code)) {
                    best[node] = rep
                }
            }
            for (rep in reports) {
                if (rep.node == null || best[rep.node] === rep) {
                    r.report(rep.code, rep.message, rep.node)
                }
            }
        }

        // ---- types (1.1-1.4) --------------------------------------------------------------------

        fun isSecondClass(t: KType?, sc: Set<TypeParamSymbol>): Boolean = when (t) {
            is KType.Nominal -> SECOND_CLASS.any { facts.isMagic(t, it) }
            is KType.Param -> t.sym in sc
            else -> false
        }

        private fun isUnsafe(t: KType?): Boolean = t is KType.Nominal && facts.isMagic(t, "Unsafe")

        /**
         * The first second-class type [t] holds where none may be (1.2), or null. [top] says
         * whether [t] itself may be one (a parameter, a result); an `Fx` signature's parameters
         * (unless `mut`) and result may be, and nothing inside a type argument may.
         */
        fun misplaced(t: KType?, top: Boolean, sc: Set<TypeParamSymbol>): KType? {
            if (t == null) {
                return null
            }
            if (!top && isSecondClass(t, sc)) {
                return t
            }
            return when (t) {
                is KType.Fn -> t.params.firstNotNullOfOrNull { misplaced(it.type, !it.byRef, sc) } ?: misplaced(t.ret, true, sc)
                is KType.Nominal -> t.typeArgs().firstNotNullOfOrNull { misplaced(it, false, sc) }
                else -> null
            }
        }

        /** An `Unsafe` in [t] but at its top when [top] (1.4). */
        fun unsafeIn(t: KType?, top: Boolean): KType? {
            if (t == null) {
                return null
            }
            if (!top && isUnsafe(t)) {
                return t
            }
            return when (t) {
                is KType.Fn -> t.params.firstNotNullOfOrNull { unsafeIn(it.type, false) } ?: unsafeIn(t.ret, false)
                is KType.Nominal -> t.typeArgs().firstNotNullOfOrNull { unsafeIn(it, false) }
                else -> null
            }
        }

        /** A function whose body C++ supplies (section 5): R-G's one predicate, [suppliedByCpp]. */
        fun isExtern(fn: FnSymbol): Boolean = fn.suppliedByCpp

        private fun returnsPointer(t: KType): Boolean = isSecondClass(t, emptySet()) || (t is KType.Fn && returnsPointer(t.ret))

        fun typeMessage(what: String, t: KType, held: KType, remedy: String): String {
            val holds = if (held == t) "is a ${held.display()}" else "is a ${t.display()}, which holds a ${held.display()}"
            return "$what $holds, and a view cannot be held (decision 4b): $remedy."
        }

        private fun declarations() {
            for (m in r.program.modules) {
                for (s in m.declarations) {
                    when (s) {
                        is FnSymbol -> signature(s)
                        is ClassSymbol -> {
                            s.fields.forEach { field(it) }
                            (listOfNotNull(s.superclass) + s.traits).forEach { parent ->
                                misplaced(parent, false, emptySet())?.let { held ->
                                    report(s.decl, TYPE, typeMessage("The parent of ${s.name}", parent, held, "a class's type argument is a field somewhere; keep the owner and pass the view"))
                                }
                            }
                            s.methods.forEach { signature(it) }
                        }
                        is TraitSymbol -> s.methods.forEach { signature(it) }
                        is GlobalSymbol -> storage(s, s.type, s.decl, "The global '${s.name}'")
                        else -> {}
                    }
                }
            }
        }

        private fun field(f: FieldSymbol) {
            storage(f, f.type, f.decl, "Field '${f.name}' of ${f.owner.name}")
        }

        /** A global or a field: never second-class, never holding one (1.2). */
        private fun storage(sym: Symbol, t: KType, node: ASTNode?, what: String) {
            unsafeIn(t, false)?.let {
                report(node, UNSAFE, "$what is a ${t.display()}: Unsafe<T> is an extern parameter type only (1.4). Keep the owner, and pass a View<T> to the extern.")
                refused.add(sym)
            }
            val held = if (isSecondClass(t, emptySet())) t else misplaced(t, false, emptySet())
            if (held != null) {
                val remedy = if (facts.isMagic(held, "CStr")) "keep the Strs (a Str, a List<Str>)" else "keep the owner (a List, Arr or Str) and form the view where it is passed"
                report(node, TYPE, typeMessage(what, t, held, remedy))
                refused.add(sym)
            }
        }

        /** A function's parameters and result (1.2, 1.4, 5.2, 5.3). */
        private fun signature(fn: FnSymbol) {
            val extern = isExtern(fn)
            for (p in fn.params) {
                val t = p.type
                unsafeIn(t, top = extern)?.let {
                    report(p.decl, UNSAFE, "Parameter '${p.name}' of '${fn.name}' is a ${t.display()}: Unsafe<T> is an extern parameter type only (1.4). " +
                        "Take a View<T> (a MutView<T> for a mut Unsafe<T>) and use the view itself.")
                    refused.add(p)
                }
                if (p.byRef && isSecondClass(t, emptySet()) && !(extern && isUnsafe(t))) {
                    report(p.decl, TYPE, "Parameter '${p.name}' of '${fn.name}' is a mut ${t.display()}, which would hold a view the caller keeps (decision 4b): " +
                        "return the view instead, from a view parameter.")
                    refused.add(p)
                }
                misplaced(t, true, emptySet())?.let { held ->
                    report(p.decl, TYPE, typeMessage("Parameter '${p.name}' of '${fn.name}'", t, held, remedyFor(held)))
                    refused.add(p)
                }
            }
            val ret = fn.ret
            if (extern && returnsPointer(ret)) {
                report(fn.decl, EXTERN, "The extern '${fn.name}' returns a ${ret.display()}: an extern never hands back a pointer (5.2). " +
                    "Declare the result Str (copied at the call), or an @_opaque class for an object C++ owns.")
                refused.add(fn)
            }
            unsafeIn(ret, false)?.let {
                report(fn.decl, UNSAFE, "'${fn.name}' returns a ${ret.display()}: Unsafe<T> is an extern parameter type only (1.4). Return a View<T> derived from a view parameter.")
                refused.add(fn)
            }
            misplaced(ret, true, emptySet())?.let { held ->
                val remedy = if (facts.isMagic(ret, "Maybe")) "return an index, Maybe<Size>, and let the caller slice" else remedyFor(held)
                report(fn.decl, TYPE, typeMessage("The result of '${fn.name}'", ret, held, remedy))
                refused.add(fn)
            }
        }

        fun remedyFor(held: KType): String =
            if (facts.isMagic(held, "CStr")) "keep the Strs (a List<Str>)" else "take the view as a parameter, or keep the owner (a List, Arr or Str) and form the view where it is passed"

        // ---- generics (1.3) --------------------------------------------------------------------

        fun viewSafe(fn: FnSymbol, t: TypeParamSymbol): Boolean = unsafeFor[fn]?.get(t) == null

        fun whyNotViewSafe(fn: FnSymbol, t: TypeParamSymbol): String = unsafeFor[fn]?.get(t) ?: ""

        /** The view-safety of every generic function for every type parameter: a fixpoint from "view-safe", lowering. */
        private fun viewSafety() {
            val generics = mutableListOf<FnSymbol>()
            for (m in r.program.modules) {
                for (s in m.declarations) {
                    when (s) {
                        is FnSymbol -> generics.add(s)
                        is ClassSymbol -> generics.addAll(s.methods)
                        is TraitSymbol -> generics.addAll(s.methods)
                        else -> {}
                    }
                }
            }
            generics.retainAll { it.typeParams.isNotEmpty() }
            var changed = true
            while (changed) {
                changed = false
                for (fn in generics) {
                    for (t in fn.typeParams) {
                        if (!viewSafe(fn, t)) {
                            continue
                        }
                        val why = unsafeReason(fn, t) ?: continue
                        unsafeFor.getOrPut(fn) { IdentityHashMap() }[t] = why
                        changed = true
                    }
                }
            }
        }

        private fun mentions(t: KType, p: TypeParamSymbol): Boolean = when (t) {
            is KType.Param -> t.sym === p
            is KType.Nominal -> t.typeArgs().any { mentions(it, p) }
            // A callback that receives a T holds none; one that returns a T hands one back.
            is KType.Fn -> mentions(t.ret, p)
            else -> false
        }

        /** Why [fn] is not view-safe for [t] (1.3), or null when it is. */
        private fun unsafeReason(fn: FnSymbol, t: TypeParamSymbol): String? {
            if (mentions(fn.ret, t)) {
                return "returns a ${t.name}"
            }
            val sc = setOf(t)
            for (p in fn.params) {
                if (p.byRef && isSecondClass(p.type, sc)) {
                    return "takes '${p.name}' as a mut ${t.name}"
                }
                misplaced(p.type, true, sc)?.let { return "takes '${p.name}' as a ${p.type.display()}" }
            }
            val body = bodyOf[fn] ?: return null
            val dry = BodyCheck(this, body, sc, dry = true)
            dry.run()
            return dry.violation
        }
    }

    /** Where a second-class expression stands (2.1). */
    private sealed interface Ctx {
        /** A given argument of [rc] (the call [call]) at parameter [index], bound to a parameter of type [slot]. */
        class Arg(val rc: ResolvedCall, val index: Int, val slot: KType?, val call: FunctionCallExpr) : Ctx

        /** The receiver of a method or operator, an operand of an operator or an index, a hole of an interpolation. */
        data object Recv : Ctx

        /** The callee of a call, called where it is written. */
        data object Callee : Ctx

        /** The value of a branch of an if-expression with no statements: the if-expression is judged instead. */
        data object Branch : Ctx

        class Return(val stmt: ReturnStatement) : Ctx

        class Range(val loop: ForIterationExpr) : Ctx

        /** Kept in a slot of type [slot]: a local's, global's or field's initializer, an assignment, a construction's field, an array literal's element; [sym] the declaration when there is one. */
        class Kept(val slot: KType?, val sym: Symbol?, val what: String) : Ctx

        /** A parameter's default, bound to the parameter of type [slot]. */
        class Default(val slot: KType?) : Ctx

        /** The last value of an if-expression branch that has statements. */
        data object BranchWithStatements : Ctx

        class Other(val what: String) : Ctx
    }

    /** A function or lambda a node is in: its own parameters, its declared result, and whether it is a method's body. */
    private class Frame(val params: Set<ParamSymbol>, val result: KType?, val lambda: LambdaExpr?, val method: Boolean)

    /** A moving write a call site spells (3.2): a `mut` argument's place, or the receiver of a `mut fx` on a value. [type] is the written value's type; [at] the operand. */
    private class NamedWrite(val place: Place, val type: KType?, val at: ASTNode)

    /** What happens between forming a view and the return of its consumer (3.1). */
    private sealed interface Event {
        val by: String

        class Named(val w: NamedWrite, override val by: String) : Event

        /** An IMPURE call or node (EffectsPass): a write of every shared or global place (decision 4b). */
        class Impure(override val by: String) : Event
    }

    /**
     * One body under one assumption: [sc] are the type parameters treated as second-class
     * (1.3's check of a generic body), and a [dry] run reports nothing and writes nothing to the
     * model: it only answers [violation], the first reason the body breaks sections 1.2, 2 or 4.
     */
    private class BodyCheck(val c: Check, val b: Body, val sc: Set<TypeParamSymbol>, val dry: Boolean) {
        val r = c.r
        val model = r.model
        val facts = r.facts
        var violation: String? = null

        private val parent = IdentityHashMap<ASTNode, ASTNode>()
        private val frameOf = IdentityHashMap<ASTNode, Frame>()
        private val covered: MutableSet<ASTNode> = java.util.Collections.newSetFromMap(IdentityHashMap())
        private val originMemo = IdentityHashMap<Expr, Set<ViewOrigin>>()
        private val viewNodes = mutableListOf<Expr>()

        private fun report(node: ASTNode?, code: String, message: String, why: String) {
            if (dry) {
                if (violation == null) {
                    violation = why
                }
                return
            }
            c.report(node, code, message)
        }

        /** A declaration refused here; a dry run's refusals hold only under its assumption, and are not the program's. */
        private fun refuse(sym: Symbol) {
            if (!dry) {
                c.refused.add(sym)
            }
        }

        fun run() {
            val base = when {
                b.fn != null -> Frame(b.fn.params.toSet(), b.fn.ret, null, b.fn.owner != null)
                else -> Frame(emptySet(), null, null, false)
            }
            fun index(n: ASTNode, frame: Frame) {
                frameOf[n] = frame
                val inner = if (n is LambdaExpr) {
                    val params = n.def.parameters.mapNotNull { model.declSyms[it] as? ParamSymbol }.toSet()
                    Frame(params, (model.types[n] as? KType.Fn)?.ret, n, false)
                } else {
                    frame
                }
                for (k in AstTree.children(n)) {
                    parent[k] = n
                    index(k, inner)
                }
            }
            b.roots.forEach { index(it, base) }
            val order = ArrayList<ASTNode>()
            fun collect(n: ASTNode) {
                order.add(n)
                AstTree.children(n).forEach { collect(it) }
            }
            b.roots.forEach { collect(it) }
            for (n in order) {
                declaration(n)
            }
            for (n in order) {
                if (n is Expr && isValueNode(n) && isSecondClassExpr(n)) {
                    viewNodes.add(n)
                    position(n)
                }
            }
            if (dry) {
                return
            }
            for (v in viewNodes) {
                writes(v)
            }
            for (v in viewNodes) {
                val os = origins(v)
                model.viewOrigins[v] = os
                callOf(v)?.first?.let { if (it is Expr && it !== v) model.viewOrigins[it] = os }
            }
        }

        // ---- what is second-class (1.5) ---------------------------------------------------------

        private fun isSc(t: KType?): Boolean = c.isSecondClass(t, sc)

        /** A lambda literal that captures a second-class parameter (a refused view local it captures is reported at the local). */
        private fun borrowing(l: LambdaExpr): Boolean = model.captures[l].orEmpty().any { cap ->
            cap is Capture.Value && (cap.symbol as? ParamSymbol)?.let { isSc(it.type) } == true
        }

        private fun isSecondClassExpr(e: Expr): Boolean = when {
            e is LambdaExpr -> borrowing(e)
            isSc(model.types[e]) -> true
            else -> model.coercions[e] is Coercion.ToView
        }

        /** A node that stands for a value in its own right: not a type, a declaration, a callee's name or a member's name. */
        private fun isValueNode(e: Expr): Boolean {
            if (e is Type || e is Decl || e is FunctionCallPositionalParameterExpr || e is FunctionCallNamedParameterExpr || e is FunctionDeclParameterExpr) {
                return false
            }
            return when (val p = parent[e]) {
                // A place written is no value read: its declaration's type is judged, not the write.
                is AssignmentExpr -> p.target !== e
                is PlaceAssignmentExpr -> p.target !== e
                is CompoundAssignmentExpr -> p.left !== e
                is MemberAccessExpr -> p.member !== e
                is FunctionCallExpr -> p.name !== e || e is LambdaExpr
                is ForIterationExpr -> p.initializer !== e
                is FunctionCallNamedParameterExpr -> p.name !== e
                is FunctionDeclParameterExpr -> p.name !== e
                is VariableDecl -> p.name !== e
                else -> true
            }
        }

        /** The call an expression is: a plain call, the member call of `a.m(...)`, or an operator overload. */
        fun callOf(e: Expr): Pair<ASTNode, ResolvedCall>? {
            if (e is FunctionCallExpr) {
                model.calls[e]?.let { return e to it }
            }
            if (e is MemberAccessExpr) {
                (e.member as? FunctionCallExpr)?.let { m -> model.calls[m]?.let { return m to it } }
            }
            if (e is BinaryExpr || e is UnaryExpr) {
                model.opCalls[e]?.let { return e to it }
            }
            return null
        }

        /**
         * The method call whose receiver is [recv], the origin of the member access [p]: either
         * shape the parser makes, `MemberAccessExpr(a, FunctionCallExpr(m, ...))` or
         * `FunctionCallExpr(MemberAccessExpr(a, m), ...)`.
         */
        private fun callOnReceiver(p: MemberAccessExpr, recv: Expr): FunctionCallExpr? {
            val m = p.member
            if (m is FunctionCallExpr && model.calls[m]?.receiver === recv) {
                return m
            }
            val g = parent[p]
            if (g is FunctionCallExpr && g.name === p && model.calls[g]?.receiver === recv) {
                return g
            }
            return null
        }

        /** The expression a call's value is: `a.m(...)`'s whole member access, else the call. */
        private fun valueOf(call: FunctionCallExpr): Expr {
            val p = parent[call]
            return if (p is MemberAccessExpr && p.member === call) p else call
        }

        /** An expression that names a declaration this pass refused, or calls a function it refused: not reported again. */
        private fun usesRefused(e: Expr): Boolean {
            if (e is Identifier && model.refs[e]?.let { it in c.refused } == true) {
                return true
            }
            if (model.places[e]?.let { r.rootSymbol(it) }?.let { it in c.refused } == true) {
                return true
            }
            return callOf(e)?.second?.fn?.let { it in c.refused } == true
        }

        // ---- declarations in bodies (1.2, 1.4, 2.1) --------------------------------------------

        private fun declaration(n: ASTNode) {
            when (n) {
                is VariableDecl -> {
                    val sym = model.declSyms[n] as? LocalSymbol ?: return
                    local(n, sym)
                }
                is ForIterationStatement -> {
                    val v = model.loops[n]?.variable as? LocalSymbol ?: return
                    val held = if (isSc(v.type)) v.type else c.misplaced(v.type, false, sc)
                    if (held != null) {
                        report(n.forIterationExpr, TYPE, c.typeMessage("The loop variable '${v.name}'", v.type, held, "iterate the owner, or pass the view to a function that iterates it"),
                            "keeps a ${held.display()} in the loop variable '${v.name}'")
                        refuse(v)
                    }
                }
                is LambdaExpr -> {
                    for (d in n.def.parameters) {
                        val p = model.declSyms[d] as? ParamSymbol ?: continue
                        c.unsafeIn(p.type, false)?.let {
                            report(d, UNSAFE, "Lambda parameter '${p.name}' is a ${p.type.display()}: Unsafe<T> is an extern parameter type only (1.4). Take a View<T>.", "takes an Unsafe")
                            refuse(p)
                        }
                        if (p.byRef && isSc(p.type)) {
                            report(d, TYPE, "Lambda parameter '${p.name}' is a mut ${p.type.display()}, which would hold a view (decision 4b): return the view instead.",
                                "takes a mut ${p.type.display()}")
                            refuse(p)
                        }
                        c.misplaced(p.type, true, sc)?.let { held ->
                            report(d, TYPE, c.typeMessage("Lambda parameter '${p.name}'", p.type, held, c.remedyFor(held)), "takes a ${p.type.display()}")
                            refuse(p)
                        }
                    }
                    val ret = (model.types[n] as? KType.Fn)?.ret
                    c.misplaced(ret, true, sc)?.let { held ->
                        report(n, TYPE, c.typeMessage("This lambda's result", ret!!, held, c.remedyFor(held)), "returns a ${ret.display()}")
                    }
                }
                is ObjectInitExpr -> if (n !in covered) {
                    val t = model.inits[n]?.type ?: return
                    c.misplaced(t, false, sc)?.let { held ->
                        report(n, TYPE, c.typeMessage("This construction", t, held, "a class's type argument is a field somewhere; keep the owner and form the view where it is passed"),
                            "builds a ${t.display()}")
                    }
                }
                is ArrayLiteral -> if (n !in covered) {
                    val t = model.types[n] ?: return
                    c.misplaced(t, false, sc)?.let { held ->
                        report(n, TYPE, c.typeMessage("This array literal", t, held, "keep the owners, and form each view where it is passed"), "builds a ${t.display()}")
                    }
                }
                is FunctionCallExpr -> generic(n)
                else -> {}
            }
        }

        private fun local(n: VariableDecl, sym: LocalSymbol) {
            val t = sym.type
            val value = n.value
            val refusedHere = when {
                c.unsafeIn(t, false) != null -> {
                    report(n, UNSAFE, "'${sym.name}' is a ${t.display()}: Unsafe<T> is an extern parameter type only (1.4). Pass a View<T> to the extern instead.", "keeps an Unsafe")
                    true
                }
                isSc(t) -> {
                    val text = value?.let { KiraUnparser.text(it) } ?: sym.name
                    report(n, LOCAL, "'${sym.name}' would hold $text, a ${t.display()}: a view is never kept in a local (decision 4b). " +
                        "Pass it straight to the call that uses it, f($text), or take a ${t.display()} parameter.", "keeps a ${t.display()} in the local '${sym.name}'")
                    true
                }
                else -> c.misplaced(t, false, sc)?.let { held ->
                    report(n, TYPE, c.typeMessage("The local '${sym.name}'", t, held, c.remedyFor(held)), "keeps a ${t.display()} in the local '${sym.name}'")
                    true
                } ?: false
            }
            if (refusedHere) {
                refuse(sym)
                if (value is ObjectInitExpr || value is ArrayLiteral) {
                    covered.add(value)
                }
            }
        }

        /** A generic callee instantiated with a second-class type (1.3), or with a type that holds one (1.2). */
        private fun generic(e: FunctionCallExpr) {
            val rc = model.calls[e] ?: return
            val fn = rc.fn ?: return
            for (t in fn.typeParams) {
                val arg = rc.substitution[t] ?: continue
                val at = valueOf(e)
                val held = c.misplaced(arg, true, sc)
                if (held != null) {
                    report(at, TYPE, c.typeMessage("The type argument ${t.name} of '${fn.name}'", arg, held, c.remedyFor(held)), "instantiates '${fn.name}' with ${arg.display()}")
                    continue
                }
                if (!isSc(arg)) {
                    continue
                }
                // Dispatched at run time, the call runs whichever override the object has: each must be view-safe.
                val runs = if (rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT) listOf(fn) + c.overridersOf(fn) else listOf(fn)
                val index = fn.typeParams.indexOf(t)
                for (impl in runs) {
                    val ti = impl.typeParams.getOrNull(index) ?: continue
                    if (c.viewSafe(impl, ti)) {
                        continue
                    }
                    val why = c.whyNotViewSafe(impl, ti)
                    val whose = if (impl === fn) "'${fn.name}'" else "its override in ${impl.owner?.name ?: "a subclass"}"
                    report(at, GENERIC, "'${fn.name}' cannot take ${t.name} = ${arg.display()}: $whose $why (1.3). Write the function over View<T> directly.",
                        "passes a ${arg.display()} to '${fn.name}', where $whose $why")
                    break
                }
            }
        }

        // ---- positions (2.1) --------------------------------------------------------------------

        private fun slotOf(rc: ResolvedCall, index: Int, callee: Expr?): KType? {
            rc.fn?.params?.getOrNull(index)?.let { return it.type.substitute(rc.substitution) }
            return (callee?.let { model.types[it] } as? KType.Fn)?.params?.getOrNull(index)?.type
        }

        private fun context(e: Expr): Ctx {
            val p = parent[e] ?: return rootContext()
            return when (p) {
                is FunctionCallPositionalParameterExpr, is FunctionCallNamedParameterExpr -> when (val g = parent[p]) {
                    is FunctionCallExpr -> argContext(g, e)
                    is ObjectInitExpr -> constructionContext(g, e)
                    else -> Ctx.Other("an argument")
                }
                is MemberAccessExpr -> if (p.origin === e && callOnReceiver(p, e) != null) Ctx.Recv else Ctx.Other("a member access")
                is FunctionCallExpr -> when {
                    p.name === e -> Ctx.Callee
                    model.calls[p]?.receiver === e -> Ctx.Recv
                    else -> argContext(p, e)
                }
                is ArrayIndexExpr -> if (p.originExpr === e) Ctx.Recv else Ctx.Other("an index")
                is BinaryExpr, is UnaryExpr, is InterpolatedStringLiteral -> Ctx.Recv
                is ReturnStatement -> Ctx.Return(p)
                is ForIterationExpr -> if (p.target === e) Ctx.Range(p) else Ctx.Other("a loop")
                is VariableDecl -> {
                    val sym = model.declSyms[p]
                    Ctx.Kept((sym as? LocalSymbol)?.type ?: (sym as? GlobalSymbol)?.type, sym, "'${p.name.value}'")
                }
                is AssignmentExpr -> if (p.value === e) Ctx.Kept(model.types[p.target] ?: (model.refs[p.target] as? LocalSymbol)?.type, model.refs[p.target], "'${p.target.value}'") else Ctx.Other("an assignment")
                is PlaceAssignmentExpr -> if (p.value === e) Ctx.Kept(model.types[p.target], null, "'${KiraUnparser.text(p.target)}'") else Ctx.Other("an assignment")
                is ObjectInitExpr -> constructionContext(p, e)
                is ArrayLiteral -> Ctx.Kept(model.types[p], null, "an array literal")
                is FunctionDeclParameterExpr -> if (p.defaultValue === e) Ctx.Default((model.declSyms[p] as? ParamSymbol)?.type) else Ctx.Other("a parameter")
                is Statement -> {
                    val g = parent[p]
                    if (p.javaClass == Statement::class.java && g is IfExpr && (g.thenBranch.lastOrNull() === p || g.elseBranch.lastOrNull() === p)) {
                        if (g.thenBranch.size == 1 && g.elseBranch.size == 1) Ctx.Branch else Ctx.BranchWithStatements
                    } else {
                        Ctx.Other("a statement, whose value is dropped")
                    }
                }
                else -> Ctx.Other("a ${p.javaClass.simpleName}")
            }
        }

        /** The root of a default's or initializer's body: kept in the declaration it initializes. */
        private fun rootContext(): Ctx {
            val sym = b.at?.let { model.declSyms[it] }
            return when (b.kind) {
                BodyKind.PARAM_DEFAULT -> Ctx.Default((sym as? ParamSymbol)?.type)
                BodyKind.FIELD_DEFAULT -> Ctx.Kept((sym as? FieldSymbol)?.type, sym, "the field '${sym?.name}'")
                BodyKind.GLOBAL_INIT -> Ctx.Kept((sym as? GlobalSymbol)?.type, sym, "the global '${sym?.name}'")
                else -> Ctx.Other("a statement, whose value is dropped")
            }
        }

        private fun argContext(call: FunctionCallExpr, e: Expr): Ctx {
            val rc = model.calls[call] ?: return Ctx.Other("an argument of an unresolved call")
            val index = rc.args.indexOfFirst { (it as? ArgBinding.Given)?.expr === e }
            if (index < 0) {
                return Ctx.Other("an argument")
            }
            return Ctx.Arg(rc, index, slotOf(rc, index, call.name), call)
        }

        private fun constructionContext(o: ObjectInitExpr, e: Expr): Ctx {
            val ri = model.inits[o] ?: return Ctx.Other("a construction")
            val f = ri.fields.firstOrNull { (it as? FieldInit.Given)?.expr === e }?.field
            return Ctx.Kept(f?.type?.substitute(ri.substitution), null, "the field '${f?.name}' of ${ri.type.display()}")
        }

        private fun position(e: Expr) {
            if (usesRefused(e)) {
                return
            }
            val ctx = context(e)
            val text = KiraUnparser.text(e)
            if (e is LambdaExpr && !isSc(model.types[e])) {
                capture(e, ctx)
                return
            }
            val t = model.types[e]?.takeIf { isSc(it) } ?: facts.viewOf(facts.elementOf(model.types[e] ?: KType.Error) ?: KType.Error)
            // F3 (40-round3, KI-13): an if-expression that picks a view of a temporary in one branch. D33 may have to
            // order it against a sibling, and C++ cannot spill one branch's owner without making the other's; like a
            // for over a temporary, it is refused where it stands. A returned one is the return rule's.
            if (e is IfExpr && ctx !is Ctx.Return && ctx != Ctx.Branch && ctx != Ctx.BranchWithStatements) {
                val bad = origins(e).firstOrNull { it is ViewOrigin.Temp }
                if (bad != null) {
                    report(e, POSITION, "$text chooses a view of ${describe(bad)} in an if-expression: a view of a temporary is only an argument, a receiver or a return " +
                        "written straight at its call (decision 4b), and C++ cannot keep one branch's temporary without making the other's. " +
                        "Use an if statement, one call per branch.", "chooses a view of ${describe(bad)} in an if-expression")
                    return
                }
            }
            when (ctx) {
                is Ctx.Arg -> {
                    val slot = ctx.slot
                    if (isSc(slot) || ctx.rc.kind == CallKind.PRINT || c.misplaced(slot, false, sc) != null) {
                        return
                    }
                    val callee = ctx.rc.fn?.name ?: "this call"
                    report(e, STORE, "$text is a view, passed to '$callee' where a ${slot?.display() ?: "first-class"} value is kept: a view is never boxed or stored (decision 4b). " +
                        "Take a ${t.display()} parameter there, or pass the owner.", "passes a ${t.display()} where a ${slot?.display()} is kept")
                }
                Ctx.Recv, Ctx.Callee, Ctx.Branch -> {}
                is Ctx.Return -> {
                    val frame = frameOf[ctx.stmt] ?: return
                    val result = frame.result
                    if (isSc(result)) {
                        returnRule(e, frame)
                    } else if (result != null && result != KType.Void && result != KType.Error && c.misplaced(result, true, sc) == null) {
                        report(e, STORE, "$text is a view, returned as a ${result.display()}: a view is never boxed or stored (decision 4b). Return a ${t.display()}.",
                            "returns a ${t.display()} as a ${result.display()}")
                    }
                }
                is Ctx.Range -> {
                    val bad = origins(e).firstOrNull { it !is ViewOrigin.Param && it !is ViewOrigin.Static } ?: return
                    report(e, POSITION, "$text is a view of ${describe(bad)} used as a for range: a view is only an argument, a receiver or a return (decision 4b), " +
                        "and only a view parameter or a literal is iterated. Iterate the owner, for x in ${ownerText(bad)}.", "iterates a view of ${describe(bad)}")
                }
                is Ctx.Kept -> {
                    if (ctx.sym != null && ctx.sym in c.refused) {
                        return
                    }
                    val slot = ctx.slot
                    if (slot == null || isSc(slot) || slot == KType.Error || c.misplaced(slot, false, sc) != null) {
                        return
                    }
                    report(e, STORE, "$text is a view, kept in ${ctx.what}, a ${slot.display()}: a view is never boxed or stored (decision 4b). " +
                        "Keep the owner, and form the view where it is passed.", "keeps a ${t.display()} in ${ctx.what}")
                }
                is Ctx.Default -> {
                    if (!isSc(ctx.slot) && ctx.slot != null && c.misplaced(ctx.slot, false, sc) == null) {
                        report(e, STORE, "$text is a view, the default of a ${ctx.slot.display()} parameter: a view is never boxed or stored (decision 4b).", "keeps a view in a default")
                    }
                }
                Ctx.BranchWithStatements -> report(e, POSITION, "$text is a view, the value of an if-expression branch with statements: C++ would run the branch in a lambda " +
                    "whose temporaries die before the view is used (decision 4b). Use an if statement, one call per branch.", "is a view in an if-expression with statements")
                is Ctx.Other -> report(e, POSITION, "$text is a view used as ${ctx.what}: a view is only an argument, a receiver or a return (decision 4b). " +
                    "Pass it straight to the call that uses it.", "uses a view as ${ctx.what}")
            }
        }

        /** A borrowing lambda (4): only an argument for a parameter that does not escape, or called where it is written. */
        private fun capture(l: LambdaExpr, ctx: Ctx) {
            val captured = model.captures[l].orEmpty().firstNotNullOfOrNull { cap ->
                ((cap as? Capture.Value)?.symbol as? ParamSymbol)?.takeIf { isSc(it.type) }
            } ?: return
            val how = when (ctx) {
                Ctx.Callee, Ctx.Branch -> return
                is Ctx.Arg -> {
                    val p = ctx.rc.fn?.params?.getOrNull(ctx.index)
                    if (p != null && !model.fxEscapes(p) && ctx.rc.kind != CallKind.VIRTUAL && ctx.rc.kind != CallKind.TRAIT) {
                        return
                    }
                    // An Fx-value callee has no FnSymbol: name it as it is written (`sink(...)`), never ''.
                    "is passed to '${ctx.rc.fn?.name ?: KiraUnparser.text(ctx.call.name)}', which keeps it"
                }
                is Ctx.Kept -> "is stored in ${ctx.what}"
                is Ctx.Return -> "is returned"
                else -> "leaves the call"
            }
            report(l, CAPTURE, "This lambda captures the view '${captured.name}' and $how (decision 4b): the view would outlive the call that lent it. " +
                "Pass '${captured.name}' to the lambda as an argument where it is called, or pass the lambda to a parameter that does not escape.",
                "captures the ${captured.type.display()} '${captured.name}' in a lambda that escapes")
        }

        // ---- the return rule (2.2) ---------------------------------------------------------------

        private fun returnRule(e: Expr, frame: Frame) {
            val bad = origins(e).firstOrNull { o ->
                when (o) {
                    is ViewOrigin.Param -> o.param !in frame.params
                    ViewOrigin.Static -> false
                    is ViewOrigin.Stored -> !(frame.lambda == null && frame.method && o.place.root() is Place.This)
                    is ViewOrigin.Temp -> true
                }
            } ?: return
            val who = if (frame.lambda != null) "This lambda" else b.what
            val remedy = when (bad) {
                is ViewOrigin.Param -> "Take '${bad.param.name}' as a parameter of this lambda, or return an index"
                is ViewOrigin.Temp -> "Return a copy (a List<T> or an Arr<T, N>), or take the storage as a view parameter"
                else -> "Take ${ownerText(bad)} as a View<T> parameter (callers pass it unchanged), or return an index or a copy"
            }
            report(e, RETURN, "$who returns ${KiraUnparser.text(e)}, a view of ${describe(bad)}, which is not one of its view parameters or its receiver (decision 4b): $remedy.",
                "returns a view of ${describe(bad)}")
        }

        private fun describe(o: ViewOrigin): String = when (o) {
            is ViewOrigin.Param -> "the parameter '${o.param.name}'"
            ViewOrigin.Static -> "a literal"
            is ViewOrigin.Stored -> (if (o.within) "storage inside " else "") + "'${r.describe(o.place)}'"
            is ViewOrigin.Temp -> "the temporary ${KiraUnparser.text(o.owner)}"
        }

        private fun ownerText(o: ViewOrigin): String = when (o) {
            is ViewOrigin.Stored -> r.describe(o.place)
            is ViewOrigin.Temp -> KiraUnparser.text(o.owner)
            is ViewOrigin.Param -> o.param.name
            ViewOrigin.Static -> "the literal"
        }

        // ---- origins (2.3) -----------------------------------------------------------------------

        fun origins(e: Expr): Set<ViewOrigin> {
            originMemo[e]?.let { return it }
            originMemo[e] = emptySet()
            val out = compute(e)
            originMemo[e] = out
            return out
        }

        private fun compute(e: Expr): Set<ViewOrigin> {
            if (e is LambdaExpr) {
                return model.captures[e].orEmpty().mapNotNullTo(LinkedHashSet()) { cap ->
                    ((cap as? Capture.Value)?.symbol as? ParamSymbol)?.takeIf { isSc(it.type) }?.let { ViewOrigin.Param(it) }
                }
            }
            if (model.coercions[e] is Coercion.ToView && !isSc(model.types[e])) {
                return storages(e, within = false)
            }
            return when (e) {
                is Identifier -> when (val sym = model.refs[e]) {
                    is ParamSymbol -> if (isSc(sym.type)) setOf(ViewOrigin.Param(sym)) else emptySet()
                    // A view local or global is refused where it is declared.
                    else -> emptySet()
                }
                is StringLiteral -> setOf(ViewOrigin.Static)
                is IfExpr -> AstScan.values(e).flatMapTo(LinkedHashSet()) { origins(it) }
                else -> {
                    val (_, rc) = callOf(e) ?: return setOf(ViewOrigin.Temp(e))
                    callOrigins(rc)
                }
            }
        }

        /** A call whose result is a view points into its view arguments and, but for an `Fx` value, into its receiver (the callee may return a view of it, 2.2). */
        private fun callOrigins(rc: ResolvedCall): Set<ViewOrigin> {
            val out = LinkedHashSet<ViewOrigin>()
            for (a in rc.args) {
                when (a) {
                    is ArgBinding.Given -> if (isSecondClassExpr(a.expr)) {
                        out.addAll(origins(a.expr))
                    }
                    is ArgBinding.Default -> if (isSc(a.param.type.substitute(rc.substitution))) {
                        a.param.default?.let { out.add(if (it is StringLiteral) ViewOrigin.Static else ViewOrigin.Temp(it)) }
                    }
                }
            }
            val ro = receiverOrigins(rc)
            if (ro.isNotEmpty()) {
                out.addAll(ro)
            } else {
                rc.receiver?.takeIf { rc.kind != CallKind.FN_VALUE && isSecondClassExpr(it) }?.let { out.addAll(origins(it)) }
            }
            return out
        }

        /** The storage a call with a view result may point into through its first-class receiver: formed at the call (3.1). Empty for none. */
        private fun receiverOrigins(rc: ResolvedCall): Set<ViewOrigin> {
            if (rc.kind == CallKind.FN_VALUE) {
                return emptySet()
            }
            if (rc.implicitThis) {
                return b.owner?.let { setOf(ViewOrigin.Stored(Place.This(it), PlaceKind.SHARED, true, (it as? ClassSymbol)?.selfType)) }.orEmpty()
            }
            val recv = rc.receiver ?: return emptySet()
            if (isSecondClassExpr(recv)) {
                return emptySet()
            }
            val lender = rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders
            return storages(recv, within = !lender)
        }

        /**
         * The storage a first-class expression [x] owns or names, viewed (2.3): a lent result on a
         * second-class receiver is where that receiver's view points ([lentOrigins], R-A rule 2),
         * recorded in `viewOrigins` for [x] so the emitter reads the same answer; anything else is
         * one [storage].
         */
        private fun storages(x: Expr, within: Boolean): Set<ViewOrigin> {
            val lent = lentOrigins(x, within) ?: return setOf(storage(x, within))
            if (!dry) {
                model.viewOrigins[x] = lent
            }
            return lent
        }

        /**
         * R-A rule 2 (40-round3): an element or a value field read off a second-class receiver
         * that is no place (`mk().view().get(0)`, `pick(a.view(), b.view()).get(0)`, and a
         * struct field of such an element) is stored where that receiver's view points, one step
         * further: each PLACE origin gains the step; STATIC and TEMP stay as they are. A PARAM
         * origin becomes the storage inside that view parameter, which is the caller's and which
         * the callee may itself write through a `MutView` (`total(mv.from(0).get(0).view(),
         * clobber(mv))`), so SHARED, as the index spelling `mv[0]` is. Rule 1 comes first: a
         * receiver that is a place, or lent from one (`xs.view().get(0)` is `xs[0]`), is a place
         * (`readPlace`) and is [storage]'s. A field through a reference (`...get(0).items` of a
         * class element) is shared storage whoever lent the view, and is [storage]'s too. Null
         * when [x] is none of these.
         */
        private fun lentOrigins(x: Expr, within: Boolean): Set<ViewOrigin>? {
            if (model.readPlace(x) != null) {
                return null
            }
            val (recv, field) = lentStep(x) ?: return null
            if (field != null && r.ownerIsReference(field)) {
                return null
            }
            val base = if (isSecondClassExpr(recv)) origins(recv) else lentOrigins(recv, within) ?: return null
            val t = model.types[x]
            fun step(p: Place, owner: KType?): Place = if (field != null) Place.Field(p, field) else Place.Index(p, indexKind(owner))
            return base.mapTo(LinkedHashSet()) { o ->
                when (o) {
                    is ViewOrigin.Stored -> ViewOrigin.Stored(step(o.place, o.type), o.kind, within || o.within, t)
                    is ViewOrigin.Param -> ViewOrigin.Stored(step(Place.Param(o.param), o.param.type), PlaceKind.SHARED, within, t)
                    else -> o
                }
            }
        }

        /** [x]'s receiver and step when [x] reads through it: an accessor in [Rules.ACCESSORS], an index, or a field (null step: an element). */
        private fun lentStep(x: Expr): Pair<Expr, FieldSymbol?>? {
            if (x is ArrayIndexExpr) {
                return x.originExpr to null
            }
            callOf(x)?.let { (_, rc) ->
                val key = (rc.fn?.foreign as? Foreign.Magic)?.key ?: return null
                val recv = rc.receiver ?: return null
                return when (key.takeIf { it in Rules.ACCESSORS }?.substringAfter('.')) {
                    "get" -> recv to null
                    "unwrap", "unwrapErr" -> {
                        val cls = (model.types[recv] as? KType.Nominal)?.sym as? ClassSymbol ?: return null
                        val name = if (key.endsWith("unwrapErr")) "error" else "value"
                        recv to (cls.fields.firstOrNull { it.name == name } ?: return null)
                    }
                    else -> null
                }
            }
            if (x is MemberAccessExpr) {
                val f = (model.members[x] as? MemberRef.Field)?.field ?: return null
                return x.origin to f
            }
            return null
        }

        private fun indexKind(t: KType?): IndexKind = when {
            t == null -> IndexKind.OTHER
            t == KType.Str -> IndexKind.STR
            facts.isList(t) -> IndexKind.LIST
            facts.isArr(t) -> IndexKind.ARR
            facts.isView(t) -> IndexKind.VIEW
            facts.isMutView(t) -> IndexKind.MUT_VIEW
            facts.isMap(t) -> IndexKind.MAP
            else -> IndexKind.OTHER
        }

        /** The storage a first-class expression [x] owns or names, viewed (2.3); a lent result is a place (R-A, `readPlace`). */
        private fun storage(x: Expr, within: Boolean): ViewOrigin {
            if (x is StringLiteral) {
                return ViewOrigin.Static
            }
            val t = model.types[x]
            val place = model.readPlace(x) ?: return ViewOrigin.Temp(x)
            val root = place.root()
            val throughRef = place.path().any { r.isReferenceStep(it) }
            if (!throughRef && root is Place.Global && !root.sym.isMut) {
                // A constant: an Arr is `inline constexpr std::array`, static; a Str is a `const char*` the call makes a kira::Str of.
                return if (t == KType.Str || r.isLiteralStrConstant(root.sym)) ViewOrigin.Temp(x) else ViewOrigin.Static
            }
            if (!throughRef && root is Place.Field && root.receiver == null) {
                return ViewOrigin.Temp(x)
            }
            val deref = t != null && r.isReference(t)
            return ViewOrigin.Stored(place, if (deref) PlaceKind.SHARED else kindOf(place), within || deref, t)
        }

        private fun kindOf(p: Place): PlaceKind {
            if (p.path().any { r.isReferenceStep(it) }) {
                return PlaceKind.SHARED
            }
            return when (val root = p.root()) {
                is Place.Param -> if (root.sym.byRef || r.aliasesCaller(root.sym.type)) PlaceKind.SHARED else PlaceKind.PRIVATE
                is Place.Global -> if (root.sym.isMut) PlaceKind.GLOBAL else PlaceKind.PRIVATE
                is Place.This -> PlaceKind.SHARED
                else -> PlaceKind.PRIVATE
            }
        }

        /** The places formed at [v] itself (3.1): a converted place, or a call's receiver; with whether the call is itself in the span (it forms the view of its receiver). */
        private fun formed(v: Expr): Pair<List<ViewOrigin.Stored>, Boolean> {
            if (model.coercions[v] is Coercion.ToView && !isSc(model.types[v])) {
                return storages(v, within = false).filterIsInstance<ViewOrigin.Stored>() to false
            }
            val (_, rc) = callOf(v) ?: return emptyList<ViewOrigin.Stored>() to false
            val os = receiverOrigins(rc).filterIsInstance<ViewOrigin.Stored>()
            if (os.isEmpty()) {
                return os to false
            }
            // A lender (`xs.view()`, `p.from(4)`) only forms the view; any other method may move its receiver first.
            return os to !(rc.kind == CallKind.MAGIC && rc.fn?.name in r.lenders)
        }

        // ---- the full-expression rule (3.1, 3.3) --------------------------------------------------

        private fun writes(v: Expr) {
            val (qs, selfInSpan) = formed(v)
            if (qs.isEmpty()) {
                return
            }
            val events = span(v, selfInSpan)
            for (q in qs) {
                for (ev in events) {
                    val how = conflict(q, ev) ?: continue
                    val by = ev.by
                    report(v, WRITE, "The view of ${describe(q)} formed here is still in use until its consuming call returns, and $by may replace, grow or free " +
                        "'${r.describe(q.place)}' ($how) (decision 4b). Call it in its own statement first, or pass a view parameter so the caller checks it.",
                        "writes '${r.describe(q.place)}' while its view is in use")
                    return
                }
            }
        }

        /** Every write between forming [v] and the return of its consumer (3.1). */
        private fun span(v: Expr, selfInSpan: Boolean): List<Event> {
            val out = mutableListOf<Event>()
            if (selfInSpan) {
                callOf(v)?.let { (site, rc) ->
                    // The call forms the view of its receiver, and may move or free it first; its own write of that
                    // receiver (a `mut fx` on a value) comes before the view is formed, and is its own business.
                    val text = KiraUnparser.text(v)
                    if (c.effects.node(site) == Effect.IMPURE) {
                        out.add(Event.Impure("'$text'"))
                    }
                    (site as? FunctionCallExpr)?.let { call ->
                        named(call, rc).filter { w -> w.at !== rc.receiver && w.at !== call }.forEach { out.add(Event.Named(it, "'$text'")) }
                    }
                }
            }
            var x: Expr = v
            while (true) {
                val p = parent[x] ?: break
                var later: List<Expr> = emptyList()
                var site: Pair<ASTNode, ResolvedCall>? = null
                val value: Expr
                when {
                    (p is FunctionCallPositionalParameterExpr || p is FunctionCallNamedParameterExpr) && parent[p] is FunctionCallExpr -> {
                        val call = parent[p] as FunctionCallExpr
                        val rc = model.calls[call] ?: break
                        val k = rc.sourceOrder.indexOfFirst { (rc.args[it] as? ArgBinding.Given)?.expr === x }
                        later = rc.sourceOrder.drop(k + 1).mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr }
                        site = call to rc
                        value = valueOf(call)
                    }
                    p is MemberAccessExpr && p.origin === x -> {
                        val call = callOnReceiver(p, x) ?: break
                        val rc = model.calls[call]!!
                        later = rc.sourceOrder.mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr }
                        site = call to rc
                        value = valueOf(call)
                    }
                    p is FunctionCallExpr -> {
                        val rc = model.calls[p] ?: break
                        val k = rc.sourceOrder.indexOfFirst { (rc.args[it] as? ArgBinding.Given)?.expr === x }
                        later = if (p.name === x || rc.receiver === x) rc.sourceOrder.mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr } else if (k >= 0) {
                            rc.sourceOrder.drop(k + 1).mapNotNull { (rc.args[it] as? ArgBinding.Given)?.expr }
                        } else {
                            break
                        }
                        site = p to rc
                        value = valueOf(p)
                    }
                    p is ArrayIndexExpr && p.originExpr === x -> {
                        later = listOf(p.indexExpr)
                        value = p
                    }
                    p is BinaryExpr -> {
                        later = if (p.leftExpr === x) listOf(p.rightExpr) else emptyList()
                        site = model.opCalls[p]?.let { p to it }
                        value = p
                    }
                    p is UnaryExpr -> {
                        site = model.opCalls[p]?.let { p to it }
                        value = p
                    }
                    p is InterpolatedStringLiteral -> {
                        val holes = AstTree.children(p).filterIsInstance<Expr>()
                        later = holes.dropWhile { it !== x }.drop(1)
                        value = p
                    }
                    p is Statement && p.javaClass == Statement::class.java && parent[p] is IfExpr -> {
                        value = parent[p] as IfExpr
                    }
                    else -> break
                }
                for (op in later) {
                    events(op, out)
                }
                site?.let { (node, rc) -> callEvents(node, rc, out) }
                if (!isSecondClassExpr(value)) {
                    break
                }
                x = value
            }
            return out
        }

        /** A call on the chain: its named writes, and itself when it is IMPURE. */
        private fun callEvents(site: ASTNode, rc: ResolvedCall, out: MutableList<Event>) {
            val text = "'${KiraUnparser.text(site)}'"
            (site as? FunctionCallExpr)?.let { call -> named(call, rc).forEach { out.add(Event.Named(it, text)) } }
            if (c.effects.node(site) == Effect.IMPURE) {
                out.add(Event.Impure(text))
            }
        }

        /**
         * What evaluating the later operand [e] does (its lambdas aside, which run only when
         * called; a callee given one is IMPURE when it runs it): each call's and assignment's
         * named writes, and every node that is IMPURE or declares a local whose drop is.
         */
        private fun events(e: Expr, out: MutableList<Event>) {
            AstScan.outsideLambdas(listOf(e)) { n ->
                when (n) {
                    is FunctionCallExpr -> model.calls[n]?.let { rc ->
                        val text = "'${KiraUnparser.text(n)}'"
                        named(n, rc).forEach { out.add(Event.Named(it, text)) }
                        lentWrites(n).forEach { out.add(Event.Named(it, text)) }
                    }
                    is AssignmentExpr -> assigned(model.places[n.target], model.types[n.target], n, out)
                    is CompoundAssignmentExpr -> assigned(model.places[n.left], model.types[n.left], n, out)
                    is PlaceAssignmentExpr -> assigned(model.places[n.target], model.types[n.target], n, out)
                    else -> {}
                }
                if (c.effects.node(n) == Effect.IMPURE || c.effects.declared(n) == Effect.IMPURE) {
                    out.add(Event.Impure("'${KiraUnparser.text(n)}'"))
                }
            }
        }

        /**
         * The places a call in a later operand is handed write access to through a `MutView` (an
         * argument lent as one, `clobber(xs.view())`, or a `MutView` receiver a magic mutator
         * writes): its callee may replace or grow what it is lent before the view's consumer runs,
         * so for a private place too (q10c, `total(xs[0].view(), clobber(xs.view()))`). The order
         * rule refused this until round 3's F1 left view operands to this pass. Within the consuming
         * call itself a `MutView` beside a view of one place is D37's (`rules.exclusivity.argument`),
         * and not listed here.
         */
        private fun lentWrites(n: FunctionCallExpr): List<NamedWrite> =
            r.callOperands(b, n).filter { op ->
                op.writes && (op.how == "a MutView of" || op.isReceiver && (op.at as? Expr)?.let { x -> facts.isMutView(model.types[x] ?: KType.Error) } == true)
            }.map { op -> NamedWrite(op.place, (op.at as? Expr)?.let { model.types[it] }, op.at) }

        private fun assigned(place: Place?, type: KType?, at: Expr, out: MutableList<Event>) {
            // A write of no known place is IMPURE (EffectsPass), which [events] adds.
            place ?: return
            out.add(Event.Named(NamedWrite(place, type, at), "'${KiraUnparser.text(at)}'"))
        }

        /**
         * The moving writes the call [e] spells (3.2): each `mut` argument's place, and the
         * receiver of a `mut fx` on a value (a container, a `StrBuf`, a struct; for a stdlib
         * container's `set`, the element alone, which moves nothing that contains it). A `mut fx`
         * on a reference writes the object behind it, which only its effect shows; a `MutView`
         * argument moves nothing at the call site (a callee that writes through it is IMPURE).
         */
        private fun named(e: FunctionCallExpr, rc: ResolvedCall): List<NamedWrite> {
            val out = mutableListOf<NamedWrite>()
            for ((i, a) in rc.args.withIndex()) {
                val given = a as? ArgBinding.Given ?: continue
                // F2 (40-round3): a second-class argument moves nothing (3.2), `mut` or not: a `mut p: Unsafe<T>` is
                // a `T*` by value (1.4), and a callee that writes through it is IMPURE already.
                if (given.byRef && !isSc(slotOf(rc, i, e.name))) {
                    r.placeOf(given.expr)?.let { out.add(NamedWrite(it, model.types[given.expr], given.expr)) }
                }
            }
            val fn = rc.fn ?: return out
            if (!fn.isMutMethod) {
                return out
            }
            val owner = b.owner
            val recvType = if (rc.implicitThis) (owner as? ClassSymbol)?.selfType else rc.receiver?.let { model.types[it] }
            if (recvType == null || r.isReference(recvType)) {
                return out
            }
            val place = if (rc.implicitThis) owner?.let { Place.This(it) } else rc.receiver?.let { r.placeOf(it) }
            val at: ASTNode = rc.receiver ?: e
            if (place != null) {
                val element = elementWrite(fn, recvType)
                if (element != null) {
                    out.add(NamedWrite(Place.Index(place, element), r.facts.elementOf(recvType), at))
                } else {
                    out.add(NamedWrite(place, recvType, at))
                }
            }
            return out
        }

        /** The index kind of a stdlib container's element setter (`xs.set(i, v)`), which writes the element alone; null for every other mutator. */
        private fun elementWrite(fn: FnSymbol, recvType: KType): IndexKind? {
            if (fn.foreign !is Foreign.Magic || fn.name != "set") {
                return null
            }
            return when {
                r.facts.isList(recvType) -> IndexKind.LIST
                r.facts.isArr(recvType) -> IndexKind.ARR
                else -> null
            }
        }

        /** Why [ev] may move the viewed place [q] (3.3), or null when it cannot. */
        private fun conflict(q: ViewOrigin.Stored, ev: Event): String? = when (ev) {
            is Event.Named -> {
                val w = ev.w
                when {
                    contains(w.place, q.place) -> "'mut ${r.describe(w.place)}'"
                    q.within && contains(q.place, w.place) && r.mayHoldStorage(w.type) -> "'mut ${r.describe(w.place)}', inside it"
                    q.kind == PlaceKind.GLOBAL && (w.place.root() as? Place.Param)?.sym?.byRef == true && mayHold(w.type, q.type) ->
                        "'mut ${r.describe(w.place)}', a mut parameter that may be bound to it"
                    q.kind == PlaceKind.SHARED && kindOf(w.place) != PlaceKind.PRIVATE && !disjoint(w.place, q.place) && mayHold(w.type, q.type) ->
                        "'mut ${r.describe(w.place)}', which may be the same storage"
                    else -> null
                }
            }
            // Decision 4b, literally: beside a view of a shared place or a mut global, any impure call may be a write of it.
            is Event.Impure -> when (q.kind) {
                PlaceKind.PRIVATE -> null
                PlaceKind.GLOBAL -> "it is impure, and '${r.describe(q.place)}' is a mut global any impure call may write"
                PlaceKind.SHARED -> "it is impure, and '${r.describe(q.place)}' is shared storage any impure call may write"
            }
        }

        private fun sameRoot(a: Place, b: Place): Boolean = when {
            a is Place.Field && a.receiver == null -> a === b
            a is Place.This && b is Place.This -> a.owner === b.owner
            else -> a == b
        }

        /** [outer] contains [inner]: the same root, and [outer]'s path a prefix of [inner]'s (an index matches any index). */
        private fun contains(outer: Place, inner: Place): Boolean {
            if (!sameRoot(outer.root(), inner.root())) {
                return false
            }
            val a = outer.path()
            val b = inner.path()
            return a.size <= b.size && a.indices.all { a[it].matches(b[it]) }
        }

        /** Provably disjoint (3.3): the same root, and the paths part at two different fields with no step through a reference after. */
        private fun disjoint(a: Place, b: Place): Boolean {
            if (!sameRoot(a.root(), b.root())) {
                return false
            }
            val pa = a.path()
            val pb = b.path()
            for (i in 0 until minOf(pa.size, pb.size)) {
                if (pa[i].matches(pb[i])) {
                    continue
                }
                if (pa[i] !is PathStep.FieldStep || pb[i] !is PathStep.FieldStep) {
                    return false
                }
                return pa.drop(i + 1).none { r.isReferenceStep(it) } && pb.drop(i + 1).none { r.isReferenceStep(it) }
            }
            return false
        }

        /** A value of [t] may hold the storage of a place of type [q] (3.3): [Rules.mayHold], the one predicate W2.6's R-B copy reads too. */
        private fun mayHold(t: KType?, q: KType?): Boolean = r.mayHold(t, q)
    }
}
