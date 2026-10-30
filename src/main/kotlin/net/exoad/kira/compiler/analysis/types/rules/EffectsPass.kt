package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PrimInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Purity, bottom-up over the call graph (design 3.4, D33). It reports nothing; it fills
 * `TypedModel.fnEffects` for every function with a body (and every stdlib binding marked
 * `pure: true`) and `TypedModel.effects` for every expression of every body. ViewPass reads
 * it too ([of]): decision 4b refuses any IMPURE call in the span of a view of a shared or
 * global place, so anything this pass cannot prove pure is IMPURE. Its second fixpoint,
 * [Confinement], fills the CONFINED tables (`fnConfined`, `lambdaConfined`, `defaultConfined`)
 * that `CallReach.confined` reads (50-round4 2.3).
 *
 * The answer is three-valued ([Effect], ordered PURE < READS < IMPURE), because D33 asks two
 * things of an operand: does evaluating it change anything a sibling could see, and does a
 * sibling's change reach it? Kira evaluates call arguments and the operands of an operator
 * left to right (C and JS do); C++ leaves them unsequenced, so the C++ emitter spills the
 * operands of a call or an operator into typed temporaries, in source order, when one of
 * them is IMPURE and another is not PURE (R19). An expression is
 *
 * - IMPURE when it has an effect: it writes anything but the writer's own locals (a `mut`
 *   parameter, the receiver of a `mut fx`, a global, a field through a class reference, a
 *   `Ref`, a `MutView`), prints, calls an extern or a bodiless prototype, a virtual or
 *   trait-dispatched method or an `Fx` value, calls a function that does any of these, calls
 *   a magic binding without `pure: true` (or a pure one that runs a user type's operators or
 *   is given an `Fx`), constructs an object whose `initially` or field defaults are not pure
 *   or that runs an `Fx` field as it is made (a `Thread` starts its `body`), or
 *   may throw (a `throw` is caught by a `try` around the call, so which operand ran first
 *   decides what a sibling's print or write left behind);
 * - READS when it has no effect but reads shared state ([Rules.isSharedPlace]): a `mut`
 *   global, a `mut` parameter, a parameter C++ passes by `const&` (a struct, a `Str`, a
 *   container: design 5.1, so the callee reads the caller's storage, which a sibling's
 *   effect could write), a field through `this` (a class's shared object; a struct's `const
 *   S&`), a view (its elements, or the view itself, which is iterated or indexed next), or
 *   the contents of a view or reference receiver (`v.get(0)`, `c.peek()`), which a sibling's
 *   effect could change before the read. Two READS siblings need no ordering, so `p[0] |
 *   p[1] << 8` on a View parameter is emitted bare;
 * - PURE otherwise: reads of locals, scalar parameters, constants and what they hold by
 *   copy. Only a `mut` argument, the receiver of a `mut fx` or a `MutView` lent from such a
 *   place, nested in a sibling, could change those, and ExclusivityPass refuses that pair
 *   (`rules.exclusivity.order`).
 *
 * A stdlib binding marked `pure: true` keeps its promise (no effect, a result from its
 * receiver and arguments); the receiver's own effect is the receiver expression's, so
 * `XS.size()` on a `mut` global is READS while `xs.size()` on a local is PURE. A checked
 * binding's panic is not a throw a `try` catches (D10), so it stays pure as the manifests
 * say.
 *
 * A function's effect is the join of its body's (its lambdas aside, which run only when
 * called). The computation starts from "every function is PURE" and raises it until nothing
 * changes, so mutually recursive functions that do nothing impure stay pure. "Pure" never licenses dropping a
 * call: the emitter spills or emits it in place, and only that.
 */
internal class EffectsPass : RulePass {
    override val name: String = "effects"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fnBodies = bodies.filter { it.fn != null }
        val fns = IdentityHashMap<FnSymbol, Effect>()
        fnBodies.forEach { fns[it.fn!!] = Effect.PURE }
        val effects = Effects(r, fns)
        var changed = true
        while (changed) {
            changed = false
            for (b in fnBodies) {
                val fn = b.fn!!
                val was = fns[fn]!!
                if (was == Effect.IMPURE) {
                    continue
                }
                val now = effects.body(b)
                if (now > was) {
                    fns[fn] = now
                    changed = true
                }
            }
        }
        val model = r.model
        model.fnEffects.putAll(fns)
        // A stdlib binding marked pure is pure to the emitter too, so it can ask one table.
        for (m in program.modules) {
            if (!m.isStdlib) {
                continue
            }
            for (s in m.declarations) {
                val declared = when (s) {
                    is FnSymbol -> listOf(s)
                    is ClassSymbol -> s.methods
                    is TraitSymbol -> s.methods
                    else -> emptyList()
                }
                for (fn in declared) {
                    if (fn.foreign is Foreign.Magic && fn.body == null && r.bindings.isPure(fn)) {
                        model.fnEffects[fn] = Effect.PURE
                    }
                }
            }
        }
        Confinement(r, effects).solve(bodies)
        val exprs = ExprEffects(effects)
        for (b in bodies) {
            b.roots.forEach { root -> AstTree.walk(root) { n -> if (n is Expr && n !is Type) exprs.of(n) } }
        }
    }

    companion object {
        /**
         * The effect of single nodes of [r]'s program after this pass ran (ViewPass: decision 4b's
         * "any impure call"). Run without it, every function is IMPURE.
         */
        fun of(r: Rules): Effects = Effects(r, r.model.fnEffects)
    }

    /** Per-expression effect, bottom-up: the join of the node's own effect and every child expression's. */
    private class ExprEffects(private val effects: Effects) {
        private val memo = IdentityHashMap<Expr, Effect>()

        fun of(e: Expr): Effect {
            memo[e]?.let { return it }
            val effect = compute(e)
            memo[e] = effect
            effects.r.model.effects[e] = effect
            return effect
        }

        private fun compute(e: Expr): Effect {
            if (e is LambdaExpr) {
                // Creating a closure copies what it captures; its body is walked on its own, for its own expressions.
                return effects.node(e)
            }
            var out = effects.node(e)
            for (k in AstTree.children(e)) {
                if (out == Effect.IMPURE) {
                    break
                }
                out = maxOf(out, if (k is Expr && k !is Type) of(k) else childrenEffect(k))
            }
            return out
        }

        /** A non-expression child (a statement of an if-expression): the join of everything under it, a local it declares included. */
        private fun childrenEffect(n: ASTNode): Effect {
            var out = Effect.PURE
            for (k in AstTree.children(n)) {
                if (out == Effect.IMPURE) {
                    break
                }
                out = maxOf(out, if (k is Expr && k !is Type) of(k) else childrenEffect(k))
            }
            return out
        }
    }
}

/**
 * The effect of a body or of one node, under the functions' effects [fns] so far.
 */
internal class Effects(val r: Rules, private val fns: Map<FnSymbol, Effect>) {
    private val model = r.model

    /** Constructions whose defaults are being walked: a class whose default builds itself adds nothing new. */
    private val constructing: MutableSet<ClassSymbol> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** The join of every node of [b] (outside its lambdas, which run only when called). */
    fun body(b: Body): Effect {
        var e = Effect.PURE
        AstScan.walk(b.roots) { n, lambdas ->
            if (e == Effect.IMPURE || lambdas.isNotEmpty()) {
                return@walk
            }
            e = maxOf(e, node(n))
        }
        return e
    }

    /** The effect of [n] itself (its children aside). */
    fun node(n: ASTNode): Effect {
        val e = n as? Expr ?: return Effect.PURE
        var out = Effect.PURE
        model.opCalls[e]?.let { out = maxOf(out, call(it, model.types[e])) }
        if (sharedRead(e)) {
            out = maxOf(out, Effect.READS)
        }
        val own = when (n) {
            is FunctionCallExpr -> call(model.calls[n], model.types[n])
            is IntrinsicExpr -> if (n.intrinsicKey.name != "_static_assert") Effect.IMPURE else Effect.PURE
            is ThrowExpr -> Effect.IMPURE
            is AssignmentExpr -> write(model.places[n.target])
            is CompoundAssignmentExpr -> write(model.places[n.left])
            is PlaceAssignmentExpr -> write(model.places[n.target])
            is PrimInitExpr -> Effect.PURE
            is ObjectInitExpr -> {
                val ri = model.inits[n] ?: return Effect.IMPURE
                val given = ri.fields.filterIsInstance<FieldInit.Given>().mapTo(HashSet()) { it.field }
                construction(ri.cls, given, ri.type, ri.fields.map { it.field })
            }
            else -> Effect.PURE
        }
        return maxOf(out, own)
    }

    /**
     * A read of state a sibling operand could change before it happens (D33): a `mut`
     * global or `mut` parameter by name, or a place that is shared ([Rules.isSharedPlace]).
     */
    private fun sharedRead(e: Expr): Boolean {
        when (e) {
            is Identifier -> when (val s = model.refs[e]) {
                is GlobalSymbol -> if (s.isMut) return true
                is ParamSymbol -> if (s.byRef) return true
                else -> {}
            }
            is MemberAccessExpr -> ((model.members[e] as? MemberRef.ModuleMember)?.symbol as? GlobalSymbol)?.let { if (it.isMut) return true }
            else -> {}
        }
        // A lent result reads the storage it is lent from (R-A): `GL.get(0)` is `GL[0]`.
        val p = model.readPlace(e) ?: return false
        // A view read as a value is iterated or indexed next: its contents are borrowed storage.
        return r.isSharedPlace(p) || r.isView(model.types[e])
    }

    /**
     * A call's effect: its callee's, joined with READS when its receiver is a view or a
     * reference (the callee reads the storage those borrow or share, which a sibling could
     * change; a struct or container receiver is read as the place the receiver expression
     * is), and with every defaulted parameter's default.
     */
    private fun call(rc: ResolvedCall?, result: KType?): Effect {
        if (rc == null) {
            return Effect.IMPURE
        }
        val fn = rc.fn
        var e = when (rc.kind) {
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> when {
                fn == null -> Effect.IMPURE
                // R-G: C++ supplies the body (an extern, a bodiless pub prototype, an opaque class's method).
                fn.suppliedByCpp -> Effect.IMPURE
                fn.foreign is Foreign.Magic && fn.body == null -> magic(rc, fn)
                // A slot a closure fills has no entry either: IMPURE.
                else -> fns[fn] ?: Effect.IMPURE
            }
            CallKind.MAGIC -> if (fn != null) magic(rc, fn) else Effect.IMPURE
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE, CallKind.PRINT -> Effect.IMPURE
        }
        if (e == Effect.IMPURE) {
            return e
        }
        if (rc.kind == CallKind.CTOR) {
            e = maxOf(e, construction((rc.returnType as? KType.Nominal)?.sym as? ClassSymbol, emptySet(), rc.returnType))
        }
        val receiverType = rc.receiver?.let { model.types[it] }
        if (receiverType != null && (r.isView(receiverType) || r.isReference(receiverType))) {
            e = maxOf(e, Effect.READS)
        }
        for (a in rc.args) {
            val d = (a as? ArgBinding.Default)?.param?.default ?: continue
            e = maxOf(e, tree(d))
        }
        return e
    }

    /**
     * A stdlib binding: IMPURE unless marked `pure: true`, and then still IMPURE when it is
     * given an `Fx` (it may run it) or compares or hashes elements of a user type (it runs
     * their operators, which this pass does not follow into).
     */
    private fun magic(rc: ResolvedCall, fn: FnSymbol): Effect {
        if (!r.bindings.isPure(fn)) {
            return Effect.IMPURE
        }
        // R-C's question, asked once (CallReach): a binding handed an Fx may run it. A pure binding runs
        // nothing else of Kira's (its manifest's promise), so an Fx inside an argument is not its to run.
        if (CallReach.givenFx(rc, model)) {
            return Effect.IMPURE
        }
        if ((fn.foreign as? Foreign.Magic)?.key in CallReach.RUNS_OPERATORS) {
            val recvType = rc.receiver?.let { model.types[it] } as? KType.Nominal
            val typeArgs = rc.substitution.values + (recvType?.typeArgs() ?: emptyList())
            if (typeArgs.any { CallReach.holdsUserType(it) }) {
                return Effect.IMPURE
            }
        }
        return Effect.PURE
    }

    /**
     * A construction: IMPURE when its class or a superclass has an `initially`, when it runs an
     * `Fx` field as it makes the object (`CallReach.runsAtConstruction`: `Thread { name, body }`
     * starts `body`, as an `Fx` value call would); else the join of the defaults of the fields
     * it leaves out ([given] are written at the construction).
     */
    private fun construction(cls: ClassSymbol?, given: Set<FieldSymbol>, type: KType?, listed: List<FieldSymbol> = emptyList()): Effect {
        if (CallReach.runsAtConstruction(cls).isNotEmpty()) {
            return Effect.IMPURE
        }
        if (cls != null && !constructing.add(cls)) {
            return Effect.PURE
        }
        try {
            val defaults: MutableSet<FieldSymbol> = java.util.Collections.newSetFromMap(IdentityHashMap())
            defaults.addAll(listed)
            val seen = HashSet<ClassSymbol>()
            var c: ClassSymbol? = cls
            while (c != null && seen.add(c)) {
                if (c.initially != null) {
                    return Effect.IMPURE
                }
                defaults.addAll(c.fields)
                c = c.superclass?.sym as? ClassSymbol
            }
            var e = Effect.PURE
            for (f in defaults) {
                if (f !in given) {
                    f.default?.let { e = maxOf(e, tree(it)) }
                }
            }
            return e
        } finally {
            if (cls != null) {
                constructing.remove(cls)
            }
        }
    }

    private fun typeOf(s: net.exoad.kira.compiler.analysis.types.Symbol): KType? = when (s) {
        is LocalSymbol -> s.type
        is ParamSymbol -> s.type
        is GlobalSymbol -> s.type
        is FieldSymbol -> s.type
        else -> null
    }

    /** A write: PURE only to the writer's own storage. */
    private fun write(p: Place?): Effect = if (localWrite(p)) Effect.PURE else Effect.IMPURE

    /** The join of every node of [e] (lambda bodies aside). */
    private fun tree(e: Expr): Effect {
        var out = Effect.PURE
        AstScan.walk(listOf(e)) { n, lambdas ->
            if (out != Effect.IMPURE && lambdas.isEmpty()) {
                out = maxOf(out, node(n))
            }
        }
        return out
    }

    /**
     * A write that touches only the writer's own storage: the place starts at a local and
     * reaches no shared object on the way (no field of a class, trait, `Ref` or stdlib
     * handle; no element of a view).
     */
    private fun localWrite(p: Place?): Boolean {
        if (p == null || p.root() !is Place.Local) {
            return false
        }
        // A `with`'s copy owns its fields: its first step is its own storage.
        val copy = (p.root() as Place.Local).sym.builds
        return p.path().withIndex().all { (i, step) ->
            when (step) {
                is PathStep.FieldStep -> (copy && i == 0) || !r.ownerIsReference(step.sym)
                is PathStep.IndexStep -> step.kind != IndexKind.MUT_VIEW && step.kind != IndexKind.VIEW
            }
        }
    }

}

/**
 * CONFINED (50-round4 2.3), EffectsPass's second fixpoint: which bodies, run, write nothing
 * their caller can see but through their own `mut` operands, and run no code the checker does
 * not see. It fills `TypedModel.fnConfined` (every function with a body), `lambdaConfined`
 * (every lambda literal's body) and `defaultConfined` (every field default);
 * `CallReach.confined` reads them, and nothing else answers the question.
 *
 * A body is CONFINED when every node outside its lambdas (which run only where they are handed
 * over, and are charged there) is one of: a read (PURE or READS), a throw, a write whose place
 * is its own ([own]: rooted at one of its locals, one of its `mut` parameters, the elements of
 * one of its `MutView` parameters, or, in a value class's `mut fx`, its `this`, through value
 * steps only), a CONFINED call ([CallReach.confined]) whose written operands are its own, a
 * call of one of its own `Fx` parameters (the call site that handed the `Fx` in charged it)
 * whose arguments row 1 passes (a `T` argument never does: it may be an `Fx` no call site saw),
 * or a CONFINED construction ([CallReach.construction], which charges an `Fx` field the
 * construction runs, a `Thread`'s `body`, as the call site of a lambda it runs would). The
 * computation starts from "every body
 * is CONFINED" and lowers until nothing changes, so mutually recursive functions that write
 * only their own storage stay CONFINED.
 */
internal class Confinement(private val r: Rules, private val effects: Effects) {
    private val model = r.model
    private val fns = IdentityHashMap<FnSymbol, Boolean>()
    private val lambdas = IdentityHashMap<LambdaExpr, Boolean>()
    private val defaults = IdentityHashMap<FieldSymbol, Boolean>()

    /**
     * A body being judged: the [body] it is written in (a lambda's is the enclosing one), its own
     * [params], its own [locals] (null: every local, as in a function body outside its
     * lambdas), whether its value `this` is its own ([valueThis]: a value class's `mut fx`), and
     * the type of an implicit `this` ([selfType]).
     */
    private inner class Frame(val body: Body, val params: Set<ParamSymbol>, val locals: Set<LocalSymbol>?, val valueThis: Boolean, val selfType: KType?) {
        val known = CallReach.Known({ fns[it] == true }, { lambdas[it] == true }, { defaults[it] == true }, params.filterTo(HashSet()) { it.type is KType.Fn && !it.byRef })
    }

    fun solve(bodies: List<Body>) {
        val fnFrames = bodies.filter { it.fn != null }.map { b -> b to functionFrame(b) }
        fnFrames.forEach { (b, _) -> fns[b.fn!!] = true }
        val lambdaFrames = mutableListOf<Pair<LambdaExpr, Frame>>()
        for (b in bodies) {
            AstScan.walk(b.roots) { n, _ ->
                if (n is LambdaExpr) {
                    lambdas[n] = true
                    lambdaFrames.add(n to lambdaFrame(b, n))
                }
            }
        }
        val defaultBodies = IdentityHashMap<ASTNode, Body>()
        bodies.filter { it.kind == BodyKind.FIELD_DEFAULT }.forEach { b -> b.roots.singleOrNull()?.let { defaultBodies[it] = b } }
        val defaultFrames = mutableListOf<Triple<FieldSymbol, Expr, Frame>>()
        for (m in r.program.modules) {
            for (s in m.declarations) {
                for (f in (s as? ClassSymbol)?.fields.orEmpty()) {
                    val d = f.default ?: continue
                    val b = defaultBodies[d] ?: continue
                    defaults[f] = true
                    defaultFrames.add(Triple(f, d, Frame(b, emptySet(), emptySet(), false, null)))
                }
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for ((b, f) in fnFrames) {
                val fn = b.fn!!
                if (fns[fn] == true && !runs(b.roots, f)) {
                    fns[fn] = false
                    changed = true
                }
            }
            for ((l, f) in lambdaFrames) {
                if (lambdas[l] == true && !runs(l.def.body.orEmpty(), f)) {
                    lambdas[l] = false
                    changed = true
                }
            }
            for ((field, d, f) in defaultFrames) {
                if (defaults[field] == true && !runs(listOf(d), f)) {
                    defaults[field] = false
                    changed = true
                }
            }
        }
        model.fnConfined.putAll(fns)
        model.lambdaConfined.putAll(lambdas)
        model.defaultConfined.putAll(defaults)
    }

    private fun functionFrame(b: Body): Frame {
        val fn = b.fn!!
        return Frame(b, fn.params.toSet(), null, b.isStructOwner && fn.isMutMethod, (b.owner as? ClassSymbol)?.selfType)
    }

    /** A lambda's own parameters and the locals declared in its body; its `this` is a capture, never its own. */
    private fun lambdaFrame(b: Body, l: LambdaExpr): Frame {
        val params = l.def.parameters.mapNotNullTo(HashSet()) { model.declSyms[it] as? ParamSymbol }
        val locals = HashSet<LocalSymbol>()
        AstScan.walk(l.def.body.orEmpty()) { n, _ ->
            (model.declSyms[n] as? LocalSymbol)?.let { locals.add(it) }
            ((n as? ForIterationStatement)?.let { model.loops[it]?.variable } as? LocalSymbol)?.let { locals.add(it) }
        }
        return Frame(b, params, locals, false, (b.owner as? ClassSymbol)?.selfType)
    }

    /** Every node of [roots] outside their lambdas keeps [f] CONFINED. */
    private fun runs(roots: List<ASTNode>, f: Frame): Boolean {
        var ok = true
        AstScan.walk(roots) { n, inner ->
            if (ok && inner.isEmpty() && !node(n, f)) {
                ok = false
            }
        }
        return ok
    }

    private fun node(n: ASTNode, f: Frame): Boolean {
        val e = n as? Expr ?: return true
        model.opCalls[e]?.let { rc -> if (!call(rc, null, f)) return false }
        return when (e) {
            is FunctionCallExpr -> {
                val rc = model.calls[e] ?: return false
                call(rc, e.name, f) && writesOwn(e, f)
            }
            is IntrinsicExpr -> e.intrinsicKey.name == "_static_assert"
            is AssignmentExpr -> own(model.places[e.target], f)
            is CompoundAssignmentExpr -> own(model.places[e.left], f)
            is PlaceAssignmentExpr -> own(model.places[e.target], f)
            is PrimInitExpr -> true
            is ObjectInitExpr -> model.inits[e]?.let { ri ->
                CallReach.construction(ri.cls, ri.fields.filterIsInstance<FieldInit.Given>().associate { it.field to it.expr }, model, f.known)
            } ?: false
            else -> true
        }
    }

    private fun call(rc: ResolvedCall, callee: Expr?, f: Frame): Boolean {
        val recvType = if (rc.implicitThis) f.selfType else rc.receiver?.let { model.types[it] }
        return CallReach.confined(rc, model, recvType, f.known, callee)
    }

    /** Every operand the call writes is the body's own; a class `mut fx` with a body writes what that body writes, which its own CONFINED answer judged. */
    private fun writesOwn(e: FunctionCallExpr, f: Frame): Boolean {
        for (op in r.callOperands(f.body, e)) {
            if (!op.writes) {
                continue
            }
            if (r.writesObjectOnly(f.body, e, op)) {
                if (model.calls[e]?.let { r.hasAnalysedBody(it) } == true) {
                    continue
                }
                return false
            }
            if (!own(op.place, f)) {
                return false
            }
        }
        return true
    }

    /** A place of the body's own storage: rooted at its own local, `mut` parameter, `MutView` parameter's elements or value `this`, through value steps only. */
    private fun own(p: Place?, f: Frame): Boolean {
        p ?: return false
        var steps = p.path()
        when (val root = p.root()) {
            is Place.Local -> if (f.locals != null && root.sym !in f.locals) {
                return false
            }
            is Place.Param -> {
                if (root.sym !in f.params) {
                    return false
                }
                if (!root.sym.byRef) {
                    // A MutView parameter's elements: the storage its caller lent, which that call site names as written.
                    if (!r.facts.isMutView(root.sym.type)) {
                        return false
                    }
                    if (steps.firstOrNull() is PathStep.IndexStep) {
                        steps = steps.drop(1)
                    }
                }
            }
            is Place.This -> if (!f.valueThis) {
                return false
            }
            else -> return false
        }
        return steps.none { r.isReferenceStep(it) }
    }
}
