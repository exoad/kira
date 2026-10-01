package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import java.util.IdentityHashMap

/**
 * `types.weak.immutable` (W2.9 1.2.11, Q5): `Weak<C>` needs an object with identity, a mutable
 * class or a trait. An immutable class has none Kira can observe (the implementation may copy
 * it, and copies cannot be told apart), so a `Weak` of one would watch a copy. The check is
 * wherever `Weak<X>` is formed: a type a declaration or a construction writes (an alias's target
 * included), an instantiation of a generic class whose fields hold `Weak<T>`, and a call of a
 * generic function whose signature or body names `Weak<T>`, each with `X` an immutable class.
 */
internal class WeakPass : RulePass {
    override val name: String = "weak"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        val model = r.model
        for ((node, t) in model.typeRefs) {
            val held = weakOf(t)
            when {
                held != null -> report(r, held, node)
                t is KType.Nominal -> instantiates(t)?.let { report(r, it, node) }
            }
        }
        for ((call, rc) in model.calls) {
            val fn = rc.fn ?: continue
            if (rc.substitution.isEmpty()) {
                continue
            }
            val immutable = rc.substitution.filterValues { immutableClass(it) != null }
            if (immutable.isEmpty()) {
                continue
            }
            val named = weakParams(fn, model)
            immutable.keys.firstOrNull { it in named }?.let { p -> report(r, immutableClass(rc.substitution[p]!!)!!, call.name) }
        }
    }

    private fun report(r: Rules, cls: ClassSymbol, at: ASTNode) {
        r.report(
            "types.weak.immutable",
            "Weak needs an object with identity: ${cls.name} is immutable, so a copy of it is the same object; take a Weak of the mutable object that holds it.",
            at,
        )
    }

    /** The immutable user class [t] is, or null. */
    private fun immutableClass(t: KType): ClassSymbol? {
        val sym = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return null
        return sym.takeIf { it.kind == ClassKind.USER && it.isImmutable }
    }

    private fun isWeak(t: KType): Boolean = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.let { it.kind == ClassKind.MAGIC && it.name == "Weak" } == true

    /** The immutable class of `Weak<X>` when [t] is exactly that, else null. */
    private fun weakOf(t: KType): ClassSymbol? = if (isWeak(t)) (t as KType.Nominal).typeArgs().firstOrNull()?.let { immutableClass(it) } else null

    /** Whether [t] names `Weak<P>` for one of [params] anywhere inside it. */
    private fun namesWeakOf(t: KType, params: Set<TypeParamSymbol>, seen: MutableSet<ClassSymbol>): Boolean = when (t) {
        is KType.Nominal -> {
            val inner = t.typeArgs()
            (isWeak(t) && inner.any { it is KType.Param && it.sym in params }) ||
                inner.any { namesWeakOf(it, params, seen) } ||
                fieldsNameWeakOf(t, params, seen)
        }
        is KType.Fn -> t.params.any { namesWeakOf(it.type, params, seen) } || namesWeakOf(t.ret, params, seen)
        else -> false
    }

    /** A generic user class whose fields, under [t]'s type arguments, name `Weak<P>` for one of [params]. */
    private fun fieldsNameWeakOf(t: KType.Nominal, params: Set<TypeParamSymbol>, seen: MutableSet<ClassSymbol>): Boolean {
        val cls = t.sym as? ClassSymbol ?: return false
        if (cls.kind == ClassKind.MAGIC || cls.typeParams.isEmpty() || !seen.add(cls)) {
            return false
        }
        val sub = cls.typeParams.zip(t.typeArgs()).toMap()
        val found = cls.fields.any { namesWeakOf(it.type.substitute(sub), params, seen) }
        seen.remove(cls)
        return found
    }

    /** An instantiation `C<X>` of a generic user class whose fields hold `Weak<T>` at an immutable `X`: that class, else null. */
    private fun instantiates(t: KType.Nominal): ClassSymbol? {
        val cls = t.sym as? ClassSymbol ?: return null
        if (cls.kind == ClassKind.MAGIC || cls.typeParams.isEmpty()) {
            return null
        }
        cls.typeParams.zip(t.typeArgs()).forEach { (p, arg) ->
            val imm = immutableClass(arg) ?: return@forEach
            if (cls.fields.any { namesWeakOf(it.type, setOf(p), java.util.Collections.newSetFromMap(IdentityHashMap())) }) {
                return imm
            }
        }
        return null
    }

    /** The type parameters (the function's and its owner's) that [fn]'s signature or body names inside a `Weak`. */
    private fun weakParams(fn: FnSymbol, model: net.exoad.kira.compiler.analysis.types.TypedModel): Set<TypeParamSymbol> {
        val params = (fn.typeParams + ((fn.owner as? ClassSymbol)?.typeParams ?: emptyList())).toSet()
        if (params.isEmpty()) {
            return emptySet()
        }
        val types = mutableListOf<KType>()
        types += fn.params.map { it.type }
        types += fn.ret
        fn.decl?.let { d -> AstTree.walk(d) { n -> if (n is Type) model.typeRefs[n]?.let { types += it } } }
        return params.filter { p -> types.any { namesWeakOf(it, setOf(p), java.util.Collections.newSetFromMap(IdentityHashMap())) } }.toSet()
    }
}
