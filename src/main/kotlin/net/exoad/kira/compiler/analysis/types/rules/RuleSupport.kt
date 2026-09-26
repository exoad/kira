package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeFacts
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/** What a [Body] is, for messages and for the passes that care (a static assert has no locals to speak of). */
internal enum class BodyKind { FUNCTION, INITIALLY, FINALLY, FIELD_DEFAULT, PARAM_DEFAULT, GLOBAL_INIT, STATIC_ASSERT }

/**
 * One typed body the rule passes walk: a function, method or trait default body, a class's
 * `initially`/`finally`, a field or parameter default, a global's initializer or a module
 * statement. [fn] is null for everything but a function body; [owner] is the type whose
 * members are reachable without a receiver; [thisMutable] follows phase C's BodyContext (a
 * struct's `mut fx`, and any class body).
 */
internal class Body(
    val kind: BodyKind,
    val module: ModuleSymbol,
    val fn: FnSymbol?,
    val owner: TypeSymbol?,
    val roots: List<ASTNode>,
    val thisMutable: Boolean,
    val what: String,
    val at: ASTNode?,
) {
    val isStructOwner: Boolean get() = (owner as? ClassSymbol)?.kind == ClassKind.STRUCT
}

/** Every [Body] of a program, in module and source order. */
internal object Bodies {
    fun of(program: TypedProgram): List<Body> {
        val out = mutableListOf<Body>()
        for (m in program.modules) {
            for (s in m.declarations) {
                when (s) {
                    is FnSymbol -> {
                        function(s, null, out)
                    }
                    is ClassSymbol -> {
                        s.initially?.let { out.add(Body(BodyKind.INITIALLY, m, null, s, it, true, "${s.name}'s initializer block", s.decl)) }
                        s.finally?.let { out.add(Body(BodyKind.FINALLY, m, null, s, it, true, "${s.name}'s finally block", s.decl)) }
                        s.fields.forEach { f ->
                            f.default?.let { out.add(Body(BodyKind.FIELD_DEFAULT, m, null, null, listOf(it), false, "the default of field '${f.name}'", f.decl)) }
                        }
                        s.methods.forEach { function(it, s, out) }
                    }
                    is TraitSymbol -> s.methods.forEach { function(it, s, out) }
                    is GlobalSymbol -> s.init?.let {
                        if (!(s.foreign is Foreign.Magic && m.isStdlib)) {
                            out.add(Body(BodyKind.GLOBAL_INIT, m, null, null, listOf(it), false, "the initializer of '${s.name}'", s.decl))
                        }
                    }
                    else -> {}
                }
            }
            for (st in m.statements) {
                out.add(Body(BodyKind.STATIC_ASSERT, m, null, null, listOf(st), false, "a module statement of ${m.uri}", st))
            }
        }
        return out
    }

    private fun function(fn: FnSymbol, owner: TypeSymbol?, out: MutableList<Body>) {
        fn.params.forEach { p ->
            p.default?.let { out.add(Body(BodyKind.PARAM_DEFAULT, fn.module, null, null, listOf(it), false, "the default of '${p.name}'", p.decl)) }
        }
        val body = fn.body ?: return
        val struct = (owner as? ClassSymbol)?.kind == ClassKind.STRUCT
        val what = if (owner != null) "${owner.name}.${fn.name}" else "'${fn.name}'"
        out.add(Body(BodyKind.FUNCTION, fn.module, fn, owner, body, !struct || fn.isMutMethod, what, fn.decl))
    }
}

/** A pre-order walk that tells each visit which lambdas enclose the node (outermost first). */
internal object AstScan {
    fun walk(roots: List<ASTNode>, visit: (node: ASTNode, lambdas: List<LambdaExpr>) -> Unit) {
        val stack = ArrayList<LambdaExpr>()
        fun go(n: ASTNode) {
            visit(n, stack)
            val push = n is LambdaExpr
            if (n is LambdaExpr) {
                stack.add(n)
            }
            for (k in AstTree.children(n)) {
                go(k)
            }
            if (push) {
                stack.removeAt(stack.size - 1)
            }
        }
        roots.forEach { go(it) }
    }

    /** The value expressions a value-position expression may evaluate to: itself, or each branch of an if-expression. */
    fun values(e: Expr): List<Expr> {
        if (e !is IfExpr) {
            return listOf(e)
        }
        val out = mutableListOf<Expr>()
        for (branch in listOf(e.thenBranch, e.elseBranch)) {
            val last = branch.lastOrNull() ?: continue
            if (last.javaClass == Statement::class.java) {
                out.addAll(values(last.expr))
            }
        }
        return out
    }
}

/**
 * What every rule pass shares: the program, its model, [TypeFacts], the stdlib binding flags,
 * and one way to report that never says the same code twice at one node (phase C's rule).
 */
internal class Rules(val program: TypedProgram) {
    val model: TypedModel = program.model
    val facts = TypeFacts(program.builtins)

    /** One reader per program, so the ten passes parse each manifest once. */
    val bindings: BindingFlags = synchronized(flagsByProgram) { flagsByProgram.getOrPut(program) { BindingFlags() } }

    private companion object {
        val flagsByProgram = java.util.WeakHashMap<TypedProgram, BindingFlags>()

        /** The magic classes the parameter column passes by value (CppTypeSpeller.BY_VALUE_MAGIC). */
        val BY_VALUE_MAGIC = setOf("View", "MutView", "Unsafe", "CStr")
    }

    private val reported = IdentityHashMap<ASTNode, MutableSet<String>>()

    init {
        program.diagnostics.forEach { d -> d.node?.let { reported.getOrPut(it) { HashSet() }.add(d.code) } }
    }

    fun report(code: String, message: String, node: ASTNode?, severity: Severity = Severity.ERROR) {
        if (node != null && !reported.getOrPut(node) { HashSet() }.add(code)) {
            return
        }
        program.report(code, message, node, severity)
    }

    /** The names of the magic methods that lend a view of their receiver (`p.from(4)`, `xs.slice(0, n)`, `xs.view()`). */
    val lenders: Set<String> = setOf("from", "slice", "view")

    /**
     * The place an expression denotes, for exclusivity: its own place, or the place a lent
     * view aliases (`xs.from(1)` aliases `xs`).
     */
    fun placeOf(e: Expr): Place? {
        model.places[e]?.let { return it }
        if (e is FunctionCallExpr) {
            val rc = model.calls[e] ?: return null
            val recv = rc.receiver ?: return null
            if (rc.kind == CallKind.MAGIC && rc.fn?.name in lenders) {
                return placeOf(recv)
            }
        }
        return null
    }

    /** True for a type whose copies share one object: a class, trait, `Ref`, `Weak`, `Unsafe`, or a stdlib handle class (Suite, Mutex, Thread, ...). */
    fun isReference(t: KType): Boolean {
        if (facts.isReference(t)) {
            return true
        }
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return sym.kind == ClassKind.MAGIC && sym.name !in Builtins.NOMINAL_PARAMS
    }

    /**
     * Whether a parameter of type [t] without `mut` is passed by `const T&` (design 5.1), so
     * the callee reads the caller's storage, not a copy: a `Str`, a container, a `Maybe`, a
     * tuple, a struct, a class or trait reference, an `Fx` value, a type parameter. The
     * scalars, `Bool`, `Char`, an enum, a `View`/`MutView`, an `Unsafe`, a `CStr` and an
     * opaque handle are copied. This is the C++ emitter's own `byValue` rule, negated; the
     * two must agree.
     */
    fun aliasesCaller(t: KType): Boolean = when (t) {
        is KType.Scalar, KType.Void, KType.Never, KType.NullT, KType.Error -> false
        is KType.Nominal -> when (val sym = t.sym) {
            is EnumSymbol -> false
            is ClassSymbol -> when (sym.kind) {
                ClassKind.OPAQUE -> false
                ClassKind.MAGIC -> sym.name !in BY_VALUE_MAGIC
                else -> true
            }
            else -> true
        }
        else -> true
    }

    /** A callee this pass can look into: a function, method, operator or constructor with a body, called statically (no vtable, binding or Fx value between). */
    fun hasAnalysedBody(rc: ResolvedCall): Boolean =
        rc.fn?.body != null && (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD || rc.kind == CallKind.OP_OVERLOAD || rc.kind == CallKind.CTOR)

    /** Whether the owner of a field is a reference type, so writing the field writes shared state. */
    fun ownerIsReference(f: FieldSymbol): Boolean = when (val o = f.owner) {
        is TraitSymbol -> true
        is ClassSymbol -> o.kind == ClassKind.CLASS || o.kind == ClassKind.OPAQUE ||
            (o.kind == ClassKind.MAGIC && (o.name !in Builtins.NOMINAL_PARAMS || o.name == "Ref" || o.name == "Weak" || o.name == "Unsafe"))
        else -> false
    }

    /** Phase C's rule for whether writing a place writes a variable the code may change (PhaseC.isMutablePlace). */
    fun isMutablePlace(p: Place, thisMutable: Boolean): Boolean = when (p) {
        is Place.Local -> p.sym.isMut
        is Place.Param -> p.sym.byRef
        is Place.Global -> p.sym.isMut
        is Place.This -> thisMutable
        is Place.Field -> {
            val owner = p.sym.owner
            if (owner is ClassSymbol && owner.kind == ClassKind.STRUCT) p.receiver?.let { isMutablePlace(it, thisMutable) } ?: true else p.sym.isMut
        }
        is Place.Index -> when (p.kind) {
            IndexKind.MUT_VIEW -> true
            IndexKind.VIEW, IndexKind.STR -> false
            else -> isMutablePlace(p.container, thisMutable)
        }
    }

    /** The variable a place starts from, when it is one. */
    fun rootSymbol(p: Place): Symbol? = when (val r = p.root()) {
        is Place.Local -> r.sym
        is Place.Param -> r.sym
        is Place.Global -> r.sym
        is Place.Field -> r.sym
        is Place.This -> null
        is Place.Index -> null
    }

    fun isView(t: KType?): Boolean = t != null && (facts.isView(t) || facts.isMutView(t))

    /**
     * The first view type a value of [t] carries, or null: [t] itself when it is a `View` or
     * `MutView`, else one held by a field of a struct, an element of a container (`Arr`,
     * `List`, `Set`, `Deque`, `Stack`, `Queue`, a `Map`'s key or value), a tuple, a `Maybe`, a
     * `Result` or a `Ref`. A class is never looked into (a class holding a view is refused at
     * its own declaration). A struct that contains itself ends the walk.
     */
    fun viewInside(t: KType?): KType? = inside(t, HashSet()) { isView(it) }

    fun holdsView(t: KType?): Boolean = viewInside(t) != null

    /** The first `MutView` a value of [t] carries (the same walk as [viewInside]), or null. */
    fun mutViewInside(t: KType?): KType? = inside(t, HashSet()) { facts.isMutView(it) }

    /** Whether a value of [t] is or holds a `MutView`: passing it lends write access to what the view was lent from. */
    fun holdsMutView(t: KType?): Boolean = mutViewInside(t) != null

    /** Whether a value of [t] is or holds a closure (the same walk as [viewInside]: an `Fx`, a `Maybe` or container of one, a struct with an `Fx` field): it holds what the closure captured. */
    fun holdsClosure(t: KType?): Boolean = inside(t, HashSet()) { it is KType.Fn } != null

    /** Whether a value of [t] borrows storage it does not own: it is or holds a view, or holds a closure that may have captured one. */
    fun holdsViewOrClosure(t: KType?): Boolean = holdsView(t) || holdsClosure(t)

    /**
     * A literal `Str` constant (design 5.1: `inline constexpr const char*`), which C++ converts
     * to a temporary `kira::Str` wherever a `Str` is taken (a `const kira::Str&` parameter, the
     * receiver of a `Str` method, R5). Phase C requires every module-level `Str` without `mut`
     * to be a compile-time literal.
     */
    fun isLiteralStrConstant(sym: Symbol?): Boolean = sym is GlobalSymbol && sym.isConstant && sym.type == KType.Str

    private fun inside(t: KType?, path: MutableSet<KType>, wanted: (KType) -> Boolean): KType? {
        if (t == null) {
            return null
        }
        if (wanted(t)) {
            return t
        }
        if (isView(t)) {
            return null
        }
        val nominal = t as? KType.Nominal ?: return null
        val sym = nominal.sym as? ClassSymbol ?: return null
        return when (sym.kind) {
            ClassKind.MAGIC -> nominal.typeArgs().firstNotNullOfOrNull { inside(it, path, wanted) }
            ClassKind.STRUCT -> {
                if (!path.add(t)) {
                    return null
                }
                val sub = sym.typeParams.zip(nominal.typeArgs()).toMap()
                val found = sym.fields.firstNotNullOfOrNull { inside(it.type.substitute(sub), path, wanted) }
                path.remove(t)
                found
            }
            else -> null
        }
    }

    /**
     * The places a call writes while it runs, as seen at the call site: the receiver of a
     * `mut fx`, a `MutView` receiver of a magic method without `pure: true` (`v.set(0, 9)`),
     * a `mut` argument, and an argument bound to a parameter that is or holds a `MutView`
     * (`fill(arr.view())` writes `arr`). A lent view stands for what it was lent from
     * ([placeOf]), and a view local for what was stored in it ([aliases]). Every operand with
     * a place is listed, written or not, so the caller can pair them.
     */
    fun callOperands(b: Body, e: FunctionCallExpr, aliases: ViewAliases): List<CallOperand> {
        val rc = model.calls[e] ?: return emptyList()
        val out = mutableListOf<CallOperand>()
        val fn = rc.fn
        val receiverType = rc.receiver?.let { model.types[it] }
        val receiverWrites = fn?.isMutMethod == true ||
            (receiverType != null && facts.isMutView(receiverType) && fn?.foreign is Foreign.Magic && !bindings.isPure(fn))
        if (rc.implicitThis) {
            b.owner?.let { out.add(CallOperand(Place.This(it), listOf(Place.This(it)), (e.name as? MemberAccessExpr)?.member ?: e.name, "this", receiverWrites, true, "the receiver")) }
        } else {
            rc.receiver?.let { recv ->
                placeOf(recv)?.let { out.add(CallOperand(it, aliases.expand(it, forWrite = receiverWrites), recv, KiraUnparser.text(recv), receiverWrites, true, "the receiver")) }
            }
        }
        rc.args.forEachIndexed { i, a ->
            val given = a as? ArgBinding.Given ?: return@forEachIndexed
            val place = placeOf(given.expr) ?: return@forEachIndexed
            val param = fn?.params?.getOrNull(i)
            val lendsWrite = !given.byRef && param != null && holdsMutView(param.type.substitute(rc.substitution))
            val how = if (given.byRef) "mut" else if (lendsWrite) "a MutView of" else ""
            val writes = given.byRef || lendsWrite
            out.add(CallOperand(place, aliases.expand(place, forWrite = writes), given.expr, KiraUnparser.text(given.expr), writes, false, how))
        }
        return out
    }

    /**
     * A place whose value another expression evaluated beside this one could change (D33):
     * one rooted at a `mut` global, a `mut` parameter, a parameter C++ passes by `const&`
     * ([aliasesCaller]: a struct, a `Str`, a container, whose storage is the caller's, so a
     * sibling's write to that storage reaches it), `this` (a class's is shared; a struct's is
     * the `const S&` or `S&` the caller's object was passed as, design 5.1), or reached
     * through a field of a reference (a class, trait, `Ref` or stdlib handle) or an element of
     * a view (which borrows storage the reader does not own). A local, a scalar parameter, a
     * constant and every value they hold by copy are private: only this expression's own
     * evaluation can change them.
     */
    fun isSharedPlace(p: Place): Boolean {
        val rootShared = when (val root = p.root()) {
            is Place.Local -> false
            is Place.Param -> root.sym.byRef || aliasesCaller(root.sym.type)
            is Place.Global -> root.sym.isMut
            is Place.This -> true
            is Place.Field -> false
            is Place.Index -> false
        }
        if (rootShared) {
            return true
        }
        return p.path().any { step ->
            when (step) {
                is PathStep.FieldStep -> ownerIsReference(step.sym)
                is PathStep.IndexStep -> step.kind == IndexKind.VIEW || step.kind == IndexKind.MUT_VIEW
            }
        }
    }

    /** A place as source text: `LOG`, `this.items`, `s.items[..]`. */
    fun describe(p: Place): String = when (p) {
        is Place.Local -> p.sym.name
        is Place.Param -> p.sym.name
        is Place.Global -> p.sym.name
        is Place.This -> "this"
        is Place.Field -> (p.receiver?.let { describe(it) + "." } ?: "") + p.sym.name
        is Place.Index -> describe(p.container) + "[" + (p.index?.let { KiraUnparser.text(it) } ?: "..") + "]"
    }

    /** The receiver of a `mut fx`: a value type needs a mutable place, a reference does not (D29). */
    fun needsMutablePlace(receiverType: KType): Boolean = !isReference(receiverType) && !receiverType.containsError()
}

/**
 * The two flags the rule passes read from the stdlib's `*.bind.yaml` manifests, keyed by a
 * magic callable's [Foreign.Magic.key]: `pure` (EffectsPass: no side effects, a result that
 * depends only on the receiver and arguments; it may still panic) and `constexpr`
 * (ConstEligibilityPass: usable in a constant expression when the receiver and arguments are
 * literal types). A missing manifest, key or flag is the conservative answer: not pure, not
 * constexpr. Phase C's MagicBindings reads `constexpr` from the same manifests by the same
 * rule (the file beside the module, the `cpp` map under the key); that reader is phase C's,
 * outside this package, and knows nothing of `pure`, so this one stays.
 */
internal class BindingFlags {
    private class Flags(val pure: Boolean, val constexpr: Boolean)

    private val manifests = HashMap<Path, Map<String, Flags>>()

    fun isPure(fn: FnSymbol): Boolean = flagsOf(fn)?.pure == true

    fun isConstexpr(fn: FnSymbol): Boolean = flagsOf(fn)?.constexpr == true

    private fun flagsOf(fn: FnSymbol): Flags? {
        val key = (fn.foreign as? Foreign.Magic)?.key?.takeIf { it.isNotEmpty() } ?: return null
        val manifest = manifestOf(fn.module) ?: return null
        return manifests.getOrPut(manifest) { parse(manifest) }[key]
    }

    private fun manifestOf(module: ModuleSymbol): Path? {
        val source = runCatching { Path.of(module.source.file) }.getOrNull() ?: return null
        val name = source.fileName?.toString()?.takeIf { it.endsWith(".kira") } ?: return null
        val manifest = source.resolveSibling(name.removeSuffix(".kira") + ".bind.yaml")
        return manifest.takeIf { Files.isRegularFile(it) }
    }

    private fun parse(path: Path): Map<String, Flags> {
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return emptyMap()
        val yaml = runCatching { Yaml().load<Any>(text) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val out = HashMap<String, Flags>()
        yaml.forEach { (key, value) ->
            val cpp = (value as? Map<*, *>)?.get("cpp") as? Map<*, *> ?: return@forEach
            out[key.toString()] = Flags(cpp["pure"] == true, cpp["constexpr"] == true)
        }
        return out
    }
}

/**
 * One operand of a call that denotes a place: [place] as written, [places] with what a view
 * local stands for added ([ViewAliases.expand]), and whether the call [writes] it. [how] is
 * the message's word for the write: `mut`, `a MutView of`, or `the receiver`.
 */
internal class CallOperand(
    val place: Place,
    val places: List<Place>,
    val at: ASTNode,
    val text: String,
    val writes: Boolean,
    val isReceiver: Boolean,
    val how: String,
)

/** [base] followed by [steps]; a field step stops the walk when [throughFields] is false (a view local's steps are index steps). */
internal fun rebase(base: Place, steps: List<PathStep>, throughFields: Boolean): Place {
    var p = base
    for (s in steps) {
        p = when (s) {
            is PathStep.IndexStep -> Place.Index(p, s.kind)
            is PathStep.FieldStep -> if (throughFields) Place.Field(p, s.sym) else return p
        }
    }
    return p
}

/**
 * What the view-typed and view-holding locals of one body stand for: `v: View<T> =
 * xs.from(1)` makes `v` stand for `xs`, `w: W = W { mv = arr.view() }` makes `w` stand for
 * `arr`, and `u: View<T> = v` makes `u` stand for what `v` does. Assignments add to a
 * local's sources, and so does a mutator handed a view (`vs.add(arr.view())` makes `vs`
 * stand for `arr`). A call's view result stands for its view or view-holding arguments (a
 * container coerced to a view among them) and such a receiver (over-approximate: whichever
 * of them it returned).
 */
internal class ViewAliases private constructor(
    private val r: Rules,
    private val sources: IdentityHashMap<LocalSymbol, MutableSet<Place>>,
) {
    /** The places a view-valued expression stands for, each expanded: its own place, or what a call's view result was lent from. */
    fun standsFor(e: Expr): List<Place> {
        val out = LinkedHashSet<Place>()
        lent(r, e, out)
        return out.flatMap { expand(it) }
    }

    /**
     * [p], and [p] rebased onto everything the local it starts from stands for. Reading the
     * local reads all of it; writing it ([forWrite]) writes only what it holds a `MutView`
     * of (`mv.set(0, 9)` writes `arr`, `vs.add(v)` on a `List<View<T>>` writes `vs` alone).
     */
    fun expand(p: Place, forWrite: Boolean = false): List<Place> {
        val out = mutableListOf(p)
        val root = p.root() as? Place.Local ?: return out
        if (!sources.containsKey(root.sym)) {
            return out
        }
        val seen = HashSet<LocalSymbol>()
        fun go(sym: LocalSymbol) {
            if (!seen.add(sym)) {
                return
            }
            if (forWrite && !r.holdsMutView(sym.type)) {
                return
            }
            for (s in sources[sym].orEmpty()) {
                val root = s.root()
                if (root is Place.Local && sources.containsKey(root.sym)) {
                    go(root.sym)
                } else {
                    out.add(rebase(s, p.path(), throughFields = false))
                }
            }
        }
        go(root.sym)
        return out
    }

    companion object {
        private val memo = java.util.WeakHashMap<Body, ViewAliases>()

        fun of(r: Rules, b: Body): ViewAliases = synchronized(memo) { memo.getOrPut(b) { build(r, b) } }

        /** A value that is or holds a view: typed as one, or a container coerced to a view where one is expected. */
        private fun viewish(r: Rules, e: Expr): Boolean {
            val t = r.model.types[e]
            return r.isView(t) || r.holdsView(t) || r.model.coercions[e] is Coercion.ToView
        }

        /**
         * A value a call's view result may have been lent from: a view or view holder, or a
         * place whose value the callee takes by reference ([Rules.aliasesCaller]: a `Str`, a
         * container, a struct) and may return a view of (`half(xs)` returning `xs.view()`).
         */
        private fun lendable(r: Rules, e: Expr): Boolean =
            viewish(r, e) || (r.placeOf(e) != null && r.model.types[e]?.let { r.aliasesCaller(it) } == true)

        /** The places the view-valued [e] was lent from, into [out]. */
        private fun lent(r: Rules, e: Expr, out: MutableSet<Place>) {
            val model = r.model
            for (v in AstScan.values(e)) {
                val p = r.placeOf(v)
                if (p != null) {
                    out.add(p)
                    continue
                }
                when (v) {
                    is ObjectInitExpr -> {
                        v.positionalArgs.forEach { lent(r, it, out) }
                        v.namedArgs.forEach { lent(r, it.value, out) }
                    }
                    is ArrayLiteral -> v.value.forEach { lent(r, it, out) }
                    is FunctionCallExpr -> {
                        val rc = model.calls[v] ?: continue
                        rc.receiver?.takeIf { lendable(r, it) }?.let { lent(r, it, out) }
                        for (a in rc.args) {
                            val given = a as? ArgBinding.Given ?: continue
                            if (lendable(r, given.expr)) {
                                lent(r, given.expr, out)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        private fun build(r: Rules, b: Body): ViewAliases {
            val model = r.model
            val sources = IdentityHashMap<LocalSymbol, MutableSet<Place>>()
            fun tracked(s: Symbol?): LocalSymbol? = (s as? LocalSymbol)?.takeIf { r.isView(it.type) || r.holdsView(it.type) }
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is VariableDecl -> tracked(model.declSyms[n])?.let { local -> n.value?.let { lent(r, it, sources.getOrPut(local) { LinkedHashSet() }) } }
                    is AssignmentExpr -> tracked((model.places[n.target]?.root() as? Place.Local)?.sym)?.let { lent(r, n.value, sources.getOrPut(it) { LinkedHashSet() }) }
                    is PlaceAssignmentExpr -> if (n.operator == null) {
                        tracked((model.places[n.target]?.root() as? Place.Local)?.sym)?.let { lent(r, n.value, sources.getOrPut(it) { LinkedHashSet() }) }
                    }
                    is FunctionCallExpr -> {
                        // A mutator handed a view stores it in its receiver (`vs.add(arr.view())`, `out.set(0, v)`).
                        val rc = model.calls[n] ?: return@walk
                        val recv = rc.receiver ?: return@walk
                        if (rc.fn?.isMutMethod != true) {
                            return@walk
                        }
                        val local = tracked((r.placeOf(recv)?.root() as? Place.Local)?.sym) ?: return@walk
                        for (a in rc.args) {
                            val given = a as? ArgBinding.Given ?: continue
                            if (!given.byRef && viewish(r, given.expr)) {
                                lent(r, given.expr, sources.getOrPut(local) { LinkedHashSet() })
                            }
                        }
                    }
                    else -> {}
                }
            }
            return ViewAliases(r, sources)
        }
    }
}

/**
 * The places a function writes that its caller cannot see at the call site: globals and, for
 * a method, fields reached through `this`, whether written by its own body or by the
 * functions it calls (a fixpoint over the call graph; a callee's `this` writes are seen
 * through the receiver they were called on). A write to a `mut` parameter or through a
 * `MutView` parameter is the caller's to see ([Rules.callOperands]) and is left out; a write
 * through a view local stands for the storage the view was lent from ([ViewAliases]). Paths
 * deeper than [MAX_DEPTH] are cut to their root, which overlaps everything under it.
 *
 * A call dispatched at run time (a virtual method, a trait method) runs whichever override
 * the object has, so it writes what the method and every override of it write. A call handed
 * a lambda may run it, so the lambda's body writes at the call (`run(fx() { LOG.add(1) })`
 * writes `LOG`); so does a call of an `Fx` local through the lambdas stored in it (`g()`).
 * Captures are by value and immutable, so a lambda writes nothing of the enclosing body but
 * what a captured view lends. A lambda that reaches a call through a field, a global or an
 * `Fx` parameter is not followed, and nothing else charges it either: writing it down runs
 * nothing, and the walk skips lambda bodies. `for x in LOG { h.f() }` with `h.f` a lambda
 * that adds to `LOG` is a known gap of the loop rule; the writes of every lambda in the
 * program are the only sound answer, and that refuses every callback called in a loop.
 */
internal class HiddenWrites private constructor(
    private val r: Rules,
    private val table: IdentityHashMap<FnSymbol, MutableSet<Place>>,
    private val overriders: IdentityHashMap<FnSymbol, MutableList<FnSymbol>>,
) {
    /** What the call [e] writes beyond what its operands show, seen from the caller's body [b]. */
    fun of(b: Body, e: FunctionCallExpr, aliases: ViewAliases): List<Place> = of(b, e, aliases, IdentityHashMap())

    /** [visiting]: the lambdas whose writes are being collected up the stack (two `Fx` locals reassigned into each other's lambdas would otherwise recurse without end). */
    private fun of(b: Body, e: FunctionCallExpr, aliases: ViewAliases, visiting: IdentityHashMap<LambdaExpr, Boolean>): List<Place> {
        val rc = r.model.calls[e] ?: return emptyList()
        val out = LinkedHashSet<Place>()
        for (fn in callees(rc)) {
            val hidden = table[fn] ?: continue
            for (w in hidden.toList()) {
                when (w.root()) {
                    is Place.Global -> out.add(w)
                    is Place.This -> if (rc.implicitThis) {
                        if (b.owner != null) {
                            out.add(rebase(Place.This(b.owner), w.path(), throughFields = true))
                        }
                    } else {
                        val recv = rc.receiver?.let { r.placeOf(it) } ?: continue
                        aliases.expand(recv).forEach { out.add(rebase(it, w.path(), throughFields = true)) }
                    }
                    else -> {}
                }
            }
        }
        for (l in lambdasRun(b, e, rc)) {
            if (visiting.put(l, true) == null) {
                out.addAll(lambdaWrites(b, l, aliases, visiting))
                visiting.remove(l)
            }
        }
        return out.toList()
    }

    /** The functions the call may run: its callee and, dispatched at run time, every override of it. */
    private fun callees(rc: ResolvedCall): List<FnSymbol> {
        val fn = rc.fn ?: return emptyList()
        return if (rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT) listOf(fn) + overriders[fn].orEmpty() else listOf(fn)
    }

    /** The lambdas the call may run: its lambda arguments, the lambdas stored in an `Fx` local it is handed, and those in the `Fx` local it calls. */
    private fun lambdasRun(b: Body, e: FunctionCallExpr, rc: ResolvedCall): List<LambdaExpr> {
        val out = mutableListOf<LambdaExpr>()
        fun value(v: Expr) {
            for (x in AstScan.values(v)) {
                when (x) {
                    is LambdaExpr -> out.add(x)
                    is Identifier -> (r.model.refs[x] as? LocalSymbol)?.let { out.addAll(lambdasIn(b)[it].orEmpty()) }
                    else -> {}
                }
            }
        }
        for (a in rc.args) {
            (a as? ArgBinding.Given)?.let { value(it.expr) }
        }
        if (rc.kind == CallKind.FN_VALUE) {
            value(e.name)
        }
        return out
    }

    private val lambdasInBody = IdentityHashMap<Body, IdentityHashMap<LocalSymbol, MutableList<LambdaExpr>>>()

    /** The lambdas each local of [b] was given (at its declaration or by assignment). */
    private fun lambdasIn(b: Body): IdentityHashMap<LocalSymbol, MutableList<LambdaExpr>> = lambdasInBody.getOrPut(b) {
        val model = r.model
        val out = IdentityHashMap<LocalSymbol, MutableList<LambdaExpr>>()
        fun store(local: Symbol?, v: Expr?) {
            val sym = local as? LocalSymbol ?: return
            v?.let { AstScan.values(it).filterIsInstance<LambdaExpr>().forEach { l -> out.getOrPut(sym) { ArrayList() }.add(l) } }
        }
        AstScan.walk(b.roots) { n, _ ->
            when (n) {
                is VariableDecl -> store(model.declSyms[n], n.value)
                is AssignmentExpr -> store((model.places[n.target] as? Place.Local)?.sym, n.value)
                is PlaceAssignmentExpr -> if (n.operator == null) store((model.places[n.target] as? Place.Local)?.sym, n.value)
                else -> {}
            }
        }
        out
    }

    /** The places the body of [l] (its own lambdas included) writes, seen from the body [b] that wrote it down. */
    private fun lambdaWrites(b: Body, l: LambdaExpr, aliases: ViewAliases, visiting: IdentityHashMap<LambdaExpr, Boolean>): List<Place> {
        val model = r.model
        val out = LinkedHashSet<Place>()
        val body = l.def.body ?: return emptyList()
        AstScan.walk(body) { n, _ ->
            when (n) {
                is AssignmentExpr -> model.places[n.target]?.let { out.addAll(aliases.expand(it, forWrite = true)) }
                is CompoundAssignmentExpr -> model.places[n.left]?.let { out.addAll(aliases.expand(it, forWrite = true)) }
                is PlaceAssignmentExpr -> model.places[n.target]?.let { out.addAll(aliases.expand(it, forWrite = true)) }
                is FunctionCallExpr -> {
                    for (op in r.callOperands(b, n, aliases)) {
                        if (op.writes) {
                            out.addAll(op.places)
                        }
                    }
                    out.addAll(of(b, n, aliases, visiting))
                }
                else -> {}
            }
        }
        return out.toList()
    }

    companion object {
        private const val MAX_DEPTH = 6

        fun of(r: Rules, bodies: List<Body>): HiddenWrites {
            val model = r.model
            val table = IdentityHashMap<FnSymbol, MutableSet<Place>>()
            val fnBodies = bodies.filter { it.fn != null }
            fnBodies.forEach { table[it.fn!!] = LinkedHashSet() }
            // Every method with a body, under each method it overrides (a trait method, a superclass method, and theirs).
            val overriders = IdentityHashMap<FnSymbol, MutableList<FnSymbol>>()
            for (b in fnBodies) {
                val fn = b.fn!!
                var base = fn.overrides
                val seen = IdentityHashMap<FnSymbol, Boolean>()
                while (base != null && seen.put(base, true) == null) {
                    overriders.getOrPut(base) { ArrayList() }.add(fn)
                    base = base.overrides
                }
            }
            val hidden = HiddenWrites(r, table, overriders)
            fun add(set: MutableSet<Place>, p: Place) {
                val root = p.root()
                if (root !is Place.Global && root !is Place.This) {
                    return
                }
                set.add(if (p.path().size > MAX_DEPTH) root else p)
            }
            var changed = true
            while (changed) {
                changed = false
                for (b in fnBodies) {
                    val set = table[b.fn!!]!!
                    val aliases = ViewAliases.of(r, b)
                    val before = set.size
                    AstScan.walk(b.roots) { n, lambdas ->
                        if (lambdas.isNotEmpty()) {
                            return@walk
                        }
                        when (n) {
                            is AssignmentExpr -> model.places[n.target]?.let { aliases.expand(it, forWrite = true).forEach { p -> add(set, p) } }
                            is CompoundAssignmentExpr -> model.places[n.left]?.let { aliases.expand(it, forWrite = true).forEach { p -> add(set, p) } }
                            is PlaceAssignmentExpr -> model.places[n.target]?.let { aliases.expand(it, forWrite = true).forEach { p -> add(set, p) } }
                            is FunctionCallExpr -> {
                                for (op in r.callOperands(b, n, aliases)) {
                                    if (op.writes) {
                                        op.places.forEach { add(set, it) }
                                    }
                                }
                                hidden.of(b, n, aliases).forEach { add(set, it) }
                            }
                            else -> {}
                        }
                    }
                    if (set.size > before) {
                        changed = true
                    }
                }
            }
            return hidden
        }
    }
}
