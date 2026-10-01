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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
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
 *   or that runs an `Fx` field as it is made (a `Thread` starts its `body`),
 *   may throw (a `throw` is caught by a `try` around the call, so which operand ran first
 *   decides what a sibling's print or write left behind), or may drop the last handle of an
 *   object whose `finally` is IMPURE ([Drops]);
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
 * called) and of every handle it may drop. The computation starts from "every function is
 * PURE and no `finally` is IMPURE" and raises both until nothing changes, so mutually
 * recursive functions that do nothing impure stay pure. "Pure" never licenses dropping a
 * call: the emitter spills or emits it in place, and only that.
 */
internal class EffectsPass : RulePass {
    override val name: String = "effects"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val bodies = Bodies.of(program)
        val fnBodies = bodies.filter { it.fn != null }
        val finallies = bodies.filter { it.kind == BodyKind.FINALLY }
        val fns = IdentityHashMap<FnSymbol, Effect>()
        fnBodies.forEach { fns[it.fn!!] = Effect.PURE }
        val drops = Drops(r, assumeEveryFinally = false)
        val effects = Effects(r, fns, drops)
        var changed = true
        while (changed) {
            changed = false
            for (b in finallies) {
                val cls = b.owner as? ClassSymbol ?: continue
                if (cls !in drops.impure && effects.body(b) == Effect.IMPURE) {
                    drops.add(cls)
                    changed = true
                }
            }
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
        synchronized(finals) { finals[program] = drops.impure }
        Confinement(r, effects, drops).solve(bodies)
        val exprs = ExprEffects(effects)
        for (b in bodies) {
            b.roots.forEach { root -> AstTree.walk(root) { n -> if (n is Expr && n !is Type) exprs.of(n) } }
        }
    }

    companion object {
        /** Per program, the classes whose `finally` this pass found IMPURE. */
        private val finals = WeakHashMap<TypedProgram, Set<ClassSymbol>>()

        /**
         * The effect of single nodes of [r]'s program after this pass ran (ViewPass: decision 4b's
         * "any impure call"). Run without it, every function is IMPURE and so is every `finally`.
         */
        fun of(r: Rules): Effects {
            val impure = synchronized(finals) { finals[r.program] }
            val drops = Drops(r, assumeEveryFinally = impure == null)
            impure?.forEach { drops.add(it) }
            return Effects(r, r.model.fnEffects, drops)
        }
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
            var out = effects.declared(n)
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
 * The effect of a body or of one node, under the functions' effects [fns] so far and the
 * classes [drops] knows to have an IMPURE `finally`.
 */
internal class Effects(val r: Rules, private val fns: Map<FnSymbol, Effect>, private val drops: Drops) {
    private val model = r.model

    /** Constructions whose defaults are being walked: a class whose default builds itself adds nothing new. */
    private val constructing: MutableSet<ClassSymbol> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** The join of every node of [b] (outside its lambdas, which run only when called), with the parameters and locals it drops. */
    fun body(b: Body): Effect {
        // A by-value parameter is destroyed in the callee (MSVC) and may be the last handle a temporary argument had.
        if (b.fn?.params?.any { !it.byRef && drops.mayDrop(it.type) } == true) {
            return Effect.IMPURE
        }
        var e = Effect.PURE
        AstScan.walk(b.roots) { n, lambdas ->
            if (e == Effect.IMPURE || lambdas.isNotEmpty()) {
                return@walk
            }
            e = maxOf(e, node(n), declared(n))
        }
        return e
    }

    /** Whether some class of the program has an IMPURE `finally` (without one, nothing [dropsHere]). */
    val anyImpureFinally: Boolean get() = drops.impure.isNotEmpty()

    /** A value of [t] may be the last handle of an object whose `finally` is IMPURE. */
    fun mayDrop(t: KType?): Boolean = drops.mayDrop(t)

    /**
     * [n] itself (its children and callees aside) may drop the last handle of an object whose
     * `finally` is IMPURE: a local it declares, the value an assignment replaces, a temporary
     * call result, construction or array literal, what a closure captures. A `finally` may write
     * anything its object reaches, so ExclusivityPass counts it as a write of every shared place.
     */
    fun dropsHere(n: ASTNode): Boolean {
        if (declared(n) == Effect.IMPURE) {
            return true
        }
        return when (n) {
            is AssignmentExpr -> drops.mayDrop(model.types[n.target] ?: (model.places[n.target] as? Place.Local)?.sym?.type)
            is CompoundAssignmentExpr -> drops.mayDrop(model.types[n.left])
            is PlaceAssignmentExpr -> drops.mayDrop(model.types[n.target])
            is FunctionCallExpr -> drops.mayDrop(model.types[n] ?: model.calls[n]?.returnType)
            is ObjectInitExpr -> drops.mayDrop(model.inits[n]?.type)
            is ArrayLiteral -> drops.mayDrop(model.types[n])
            is LambdaExpr -> closure(n) == Effect.IMPURE
            else -> false
        }
    }

    /** A local declared at [n] (a `val`, a loop variable) is dropped at the end of its scope: IMPURE when that may run an IMPURE `finally`. */
    fun declared(n: ASTNode): Effect {
        val t = (model.declSyms[n] as? LocalSymbol)?.type
            ?: ((n as? ForIterationStatement)?.let { model.loops[it]?.variable } as? LocalSymbol)?.type
            ?: return Effect.PURE
        return if (drops.mayDrop(t)) Effect.IMPURE else Effect.PURE
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
            is AssignmentExpr -> write(model.places[n.target], model.types[n.target])
            is CompoundAssignmentExpr -> write(model.places[n.left], model.types[n.left])
            is PlaceAssignmentExpr -> write(model.places[n.target], model.types[n.target])
            is ObjectInitExpr -> {
                val ri = model.inits[n] ?: return Effect.IMPURE
                val given = ri.fields.filterIsInstance<FieldInit.Given>().mapTo(HashSet()) { it.field }
                construction(ri.cls, given, ri.type, ri.fields.map { it.field })
            }
            // A temporary array of handles is dropped at the end of its full-expression.
            is ArrayLiteral -> if (drops.mayDrop(model.types[n])) Effect.IMPURE else Effect.PURE
            is LambdaExpr -> closure(n)
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
     * is), and with every defaulted parameter's default. A [result] that may hold the last
     * handle of an object with an IMPURE `finally` is a temporary the caller drops: IMPURE.
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
            // A copy (W2.9 1.2.3) is a construction that reads its receiver (an operand of its own): its class's `initially` runs again.
            CallKind.COPY -> if (((rc.returnType as? KType.Nominal)?.sym as? ClassSymbol)?.let { CallReach.chainHasInitially(it) } != false) Effect.IMPURE else Effect.PURE
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE, CallKind.PRINT -> Effect.IMPURE
        }
        if (e == Effect.IMPURE) {
            return e
        }
        if (rc.kind == CallKind.CTOR) {
            e = maxOf(e, construction((rc.returnType as? KType.Nominal)?.sym as? ClassSymbol, emptySet(), rc.returnType))
        }
        if (drops.mayDrop(result ?: rc.returnType)) {
            return Effect.IMPURE
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
     * starts `body`, as an `Fx` value call would), or when the object may be the last handle of
     * one with an IMPURE `finally` (a temporary is dropped at the end of its full-expression, a
     * by-value argument in the callee); else the join of the defaults of the fields it leaves
     * out ([given] are written at the construction).
     */
    private fun construction(cls: ClassSymbol?, given: Set<FieldSymbol>, type: KType?, listed: List<FieldSymbol> = emptyList()): Effect {
        if (drops.mayDrop(type) || CallReach.runsAtConstruction(cls).isNotEmpty()) {
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

    /** A closure copies what it captures; dropping the copy may be the last handle. */
    private fun closure(l: LambdaExpr): Effect {
        val held = model.captures[l].orEmpty().any { cap ->
            when (cap) {
                is Capture.Value -> drops.mayDrop(typeOf(cap.symbol))
                is Capture.Field -> drops.mayDrop(selfOf(cap.field.owner)) || drops.mayDrop(cap.field.type)
                is Capture.This -> drops.mayDrop(selfOf(cap.owner))
            }
        }
        return if (held) Effect.IMPURE else Effect.PURE
    }

    private fun typeOf(s: net.exoad.kira.compiler.analysis.types.Symbol): KType? = when (s) {
        is LocalSymbol -> s.type
        is ParamSymbol -> s.type
        is GlobalSymbol -> s.type
        is FieldSymbol -> s.type
        else -> null
    }

    private fun selfOf(owner: net.exoad.kira.compiler.analysis.types.TypeSymbol): KType? = when (owner) {
        is ClassSymbol -> owner.selfType
        else -> null
    }

    /** A write: PURE only to the writer's own storage, and only when the value it replaces cannot be the last handle of an object with an IMPURE `finally`. */
    private fun write(p: Place?, type: KType?): Effect {
        val t = type ?: when (p) {
            is Place.Local -> p.sym.type
            else -> null
        }
        return if (localWrite(p) && !drops.mayDrop(t)) Effect.PURE else Effect.IMPURE
    }

    /** The join of every node of [e] (lambda bodies aside). */
    private fun tree(e: Expr): Effect {
        var out = Effect.PURE
        AstScan.walk(listOf(e)) { n, lambdas ->
            if (out != Effect.IMPURE && lambdas.isEmpty()) {
                out = maxOf(out, node(n), declared(n))
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
        return p.path().all { step ->
            when (step) {
                is PathStep.FieldStep -> !r.ownerIsReference(step.sym)
                is PathStep.IndexStep -> step.kind != IndexKind.MUT_VIEW && step.kind != IndexKind.VIEW
            }
        }
    }

}

/**
 * The classes whose `finally` is IMPURE ([impure], raised by EffectsPass's fixpoint), and which
 * values may hold the last handle of such an object, so that dropping them may run it
 * ([mayDrop]). A class's object runs its own `finally` and its superclasses', and drops its
 * fields; a class-typed value may be any subclass; a trait-typed value, a type parameter, an
 * `Fx` (a closure holds what it captured) and a stdlib handle may be any class at all. When
 * [assumeEveryFinally], every class with a `finally` counts (the answer without EffectsPass).
 */
internal class Drops(private val r: Rules, assumeEveryFinally: Boolean) {
    val impure: MutableSet<ClassSymbol> = java.util.Collections.newSetFromMap(IdentityHashMap())
    private val subclasses = IdentityHashMap<ClassSymbol, MutableList<ClassSymbol>>()
    private val memo = HashMap<KType, Boolean>()

    init {
        for (m in r.program.modules) {
            for (s in m.declarations) {
                val cls = s as? ClassSymbol ?: continue
                (cls.superclass?.sym as? ClassSymbol)?.let { subclasses.getOrPut(it) { ArrayList() }.add(cls) }
                if (assumeEveryFinally && cls.finally != null) {
                    impure.add(cls)
                }
            }
        }
    }

    fun add(cls: ClassSymbol) {
        if (impure.add(cls)) {
            memo.clear()
        }
    }

    /** Dropping a value of [t] may run an IMPURE `finally`; an unknown type may. */
    fun mayDrop(t: KType?): Boolean {
        if (impure.isEmpty()) {
            return false
        }
        if (t == null) {
            return true
        }
        return memo.getOrPut(t) { holds(t, HashSet()) }
    }

    /**
     * [path]: the objects being expanded on the way down, each under its substitution, so a
     * class that holds itself adds nothing; a sibling field or type argument is expanded afresh
     * (round 2's minor: one shared `seen` set judged `PairAB { a: Box<Int32>, b: Box<Dropper> }`
     * unable to hold a Dropper because Box had been seen under `T = Int32`).
     */
    private fun holds(t: KType, path: MutableSet<Pair<ClassSymbol, Map<net.exoad.kira.compiler.analysis.types.TypeParamSymbol, KType>?>>): Boolean = when (t) {
        is KType.Scalar, KType.Str, KType.Void, KType.Never, KType.NullT -> false
        is KType.Param, is KType.Fn, KType.Error -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is TraitSymbol -> true
            is ClassSymbol -> when (sym.kind) {
                // A container, Maybe, tuple, Ref or Weak holds its type arguments; a handle without any (a Thread) may hold a closure.
                ClassKind.MAGIC -> t.typeArgs().any { holds(it, path) } || (t.typeArgs().isEmpty() && r.isReference(t))
                // C++ owns an opaque object: its destructor is not a Kira finally (the FFI contract).
                ClassKind.OPAQUE -> false
                else -> {
                    val sub = sym.typeParams.zip(t.typeArgs()).toMap()
                    family(sym).any { c -> objectHolds(c, if (c === sym) sub else null, path) }
                }
            }
            else -> false
        }
    }

    /** An object of exactly [c] (its superclasses' parts included) runs an IMPURE `finally` or holds a value that may. */
    private fun objectHolds(
        c: ClassSymbol,
        sub: Map<net.exoad.kira.compiler.analysis.types.TypeParamSymbol, KType>?,
        path: MutableSet<Pair<ClassSymbol, Map<net.exoad.kira.compiler.analysis.types.TypeParamSymbol, KType>?>>,
    ): Boolean {
        val key = c to sub
        if (!path.add(key)) {
            return false
        }
        try {
            var k: ClassSymbol? = c
            val chain = HashSet<ClassSymbol>()
            while (k != null && chain.add(k)) {
                if (k in impure) {
                    return true
                }
                // A field of a subclass or a superclass is judged over its own type parameters: a type parameter may be anything.
                if (k.fields.any { f -> holds(if (k === c && sub != null) f.type.substitute(sub) else f.type, path) }) {
                    return true
                }
                k = k.superclass?.sym as? ClassSymbol
            }
            return false
        } finally {
            path.remove(key)
        }
    }

    /** [c] and every class below it. */
    private fun family(c: ClassSymbol): List<ClassSymbol> {
        val out = ArrayList<ClassSymbol>()
        val stack = ArrayDeque<ClassSymbol>().apply { add(c) }
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<ClassSymbol, Boolean>())
        while (stack.isNotEmpty()) {
            val k = stack.removeLast()
            if (seen.add(k)) {
                out.add(k)
                subclasses[k]?.let { stack.addAll(it) }
            }
        }
        return out
    }
}

/**
 * CONFINED (50-round4 2.3), EffectsPass's second fixpoint: which bodies, run, write nothing
 * their caller can see but through their own `mut` operands, and run no code the checker does
 * not see. It fills `TypedModel.fnConfined` (every function with a body), `lambdaConfined`
 * (every lambda literal's body) and `defaultConfined` (every field default), and sets
 * `dropsImpureFinally`; `CallReach.confined` reads them, and nothing else answers the question.
 *
 * A body is CONFINED when every node outside its lambdas (which run only where they are handed
 * over, and are charged there) is one of: a read (PURE or READS), a throw, a write whose place
 * is its own ([own]: rooted at one of its locals, one of its `mut` parameters, the elements of
 * one of its `MutView` parameters, or, in a value class's `mut fx`, its `this`, through value
 * steps only), a CONFINED call ([CallReach.confined]) whose written operands are its own, a
 * call of one of its own `Fx` parameters (the call site that handed the `Fx` in charged it)
 * whose arguments row 1 passes (a `T` argument never does: it may be an `Fx` no call site saw),
 * or a CONFINED construction ([CallReach.construction], which charges an `Fx` field the
 * construction runs, a `Thread`'s `body`, as the call site of a lambda it runs would); and nothing
 * in it, a by-value parameter included, may drop the
 * last handle of an object whose `finally` is IMPURE. The computation starts from "every body
 * is CONFINED" and lowers until nothing changes, so mutually recursive functions that write
 * only their own storage stay CONFINED.
 */
internal class Confinement(private val r: Rules, private val effects: Effects, private val drops: Drops) {
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
        val known = CallReach.Known({ fns[it] == true }, { lambdas[it] == true }, { defaults[it] == true }, { drops.mayDrop(it) }, params.filterTo(HashSet()) { it.type is KType.Fn && !it.byRef })
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
                if (fns[fn] == true && (fn.params.any { !it.byRef && drops.mayDrop(it.type) } || !runs(b.roots, f))) {
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
        model.dropsImpureFinally = { drops.mayDrop(it) }
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
        if (effects.dropsHere(n)) {
            return false
        }
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
