package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.Builtins
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.Place
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
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
    fun viewInside(t: KType?): KType? = viewInside(t, HashSet())

    fun holdsView(t: KType?): Boolean = viewInside(t) != null

    private fun viewInside(t: KType?, path: MutableSet<KType>): KType? {
        if (t == null) {
            return null
        }
        if (isView(t)) {
            return t
        }
        val nominal = t as? KType.Nominal ?: return null
        val sym = nominal.sym as? ClassSymbol ?: return null
        return when (sym.kind) {
            ClassKind.MAGIC -> nominal.typeArgs().firstNotNullOfOrNull { viewInside(it, path) }
            ClassKind.STRUCT -> {
                if (!path.add(t)) {
                    return null
                }
                val sub = sym.typeParams.zip(nominal.typeArgs()).toMap()
                val found = sym.fields.firstNotNullOfOrNull { viewInside(it.type.substitute(sub), path) }
                path.remove(t)
                found
            }
            else -> null
        }
    }

    /**
     * A place whose value another expression evaluated beside this one could change (D33):
     * one rooted at a `mut` global, a `mut` parameter or the `this` of a class, or reached
     * through a field of a reference (a class, trait, `Ref` or stdlib handle) or an element of
     * a view (which borrows storage the reader does not own). A local, a by-value parameter,
     * a constant, a struct receiver and every value they hold by copy are private: only this
     * expression's own evaluation can change them.
     */
    fun isSharedPlace(p: Place): Boolean {
        val rootShared = when (val root = p.root()) {
            is Place.Local -> false
            is Place.Param -> root.sym.byRef
            is Place.Global -> root.sym.isMut
            is Place.This -> (root.owner as? ClassSymbol)?.kind != ClassKind.STRUCT
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
