package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariantDecl
import java.util.IdentityHashMap

/**
 * The last step of phase B (W2.9 1.1 and 1.2.1): every class's [ClassSymbol.isImmutable], every
 * user class's [ClassSymbol.shape] and [ClassSymbol.valueWhyNot], and the synthesized `copy`
 * (1.2.3, Q7). It runs once parents, overrides and [ClassSymbol.isSubclassed] are known.
 *
 * A user class is a value unless it is mutable, a variant, or one of 1.2.1's conditions holds:
 * (a) it names a class parent, (b) a class of the program extends it, (c) it implements a trait
 * (the interim rule until boxing is built: a trait value is a reference), (d) it has `finally`,
 * (e) it holds itself by value (directly, or through a value class, a struct, a `Maybe`, a
 * `Result`, a tuple, an `Arr`, or a container C++ needs complete where the holder is defined),
 * which C++ cannot lay out. An `@_extern` class is the C++ type its declaration states: a value
 * when immutable, whatever (b) to (e) say.
 */
internal class ClassShapes(private val program: TypedProgram) {
    private val classes: List<ClassSymbol> = program.modules.flatMap { m -> m.declarations.filterIsInstance<ClassSymbol>() }

    fun run() {
        classes.forEach { c -> c.isImmutable = mutability(c, IdentityHashMap()) == null }
        val candidates = classes.filter { it.kind == ClassKind.USER && it.foreign !is Foreign.Extern && whyRef(it) == null }.toSet()
        for (c in classes) {
            if (c.kind != ClassKind.USER) {
                continue
            }
            val why = runCatching { whyRef(c) ?: if (c in candidates) cycle(c, candidates) else null }
                .getOrElse { "the shape of ${c.name} could not be decided (${it.javaClass.simpleName})" }
            c.shape = if (why == null) Shape.VALUE else Shape.REF
            c.valueWhyNot = why
        }
        classes.forEach { c -> valueLost(c) }
        classes.forEach { c -> externExtended(c) }
        classes.forEach { c -> copy(c) }
    }

    /** `ffi.extern.extended` (1.2.10): a Kira class extending an `@_extern` class, whose hierarchy C++ owns. */
    private fun externExtended(c: ClassSymbol) {
        val parent = c.superclass?.sym as? ClassSymbol ?: return
        if (parent.foreign !is Foreign.Extern || c.foreign is Foreign.Extern) {
            return
        }
        program.report(
            "ffi.extern.extended",
            "${c.name} cannot extend ${parent.name}: ${parent.name} is an extern class, a C++ type whose hierarchy C++ owns; hold one in a field instead.",
            (c.decl as? ClassDecl)?.name ?: c.decl,
        )
    }

    /** Why [c] is not immutable (its own or an ancestor's `mut` field or `mut fx`), or null. */
    private fun mutability(c: ClassSymbol, seen: IdentityHashMap<ClassSymbol, Boolean>): String? {
        if (seen.put(c, true) != null) {
            return null
        }
        c.fields.firstOrNull { it.isMut }?.let { return "field `${it.name}` is `mut`" }
        c.methods.firstOrNull { it.isMutMethod }?.let { return "method `${it.name}` is a `mut fx`" }
        val parent = c.superclass?.sym as? ClassSymbol ?: return null
        return mutability(parent, seen)?.let { "it inherits from ${parent.name}, whose $it" }
    }

    /** Conditions (a) to (d), and mutability: the reason [c] is a reference, or null. Externs are exempt from (b) to (e). */
    private fun whyRef(c: ClassSymbol): String? {
        if (c.decl is VariantDecl) {
            return "${c.name} is a variant"
        }
        mutability(c, IdentityHashMap())?.let { return "${c.name} is mutable ($it)" }
        c.superclass?.let { return "${c.name} extends ${it.sym.name}" }
        if (c.foreign is Foreign.Extern) {
            return null
        }
        if (c.isSubclassed) {
            val sub = classes.firstOrNull { it.superclass?.sym === c }
            return "${c.name} is extended by ${sub?.name ?: "a class"}${sub?.takeIf { it.module !== c.module }?.let { " (module ${it.module.uri})" } ?: ""}"
        }
        c.traits.firstOrNull()?.let { return "${c.name} implements the trait ${it.sym.name} (a trait value is a reference until boxing is built)" }
        if (c.finally != null) {
            return "${c.name} has a finally block"
        }
        return null
    }

    /** (e): the field through which [c] holds itself by value, among [candidates] and structs; null when it does not. */
    private fun cycle(c: ClassSymbol, candidates: Set<ClassSymbol>): String? {
        for (f in c.fields) {
            if (embeds(f.type, c, candidates, HashSet(), 0)) {
                return "${c.name} holds itself through field `${f.name}`"
            }
        }
        return null
    }

    /**
     * Whether a value of [t] holds [target] by value: [target] itself, a candidate class or a
     * struct whose fields do (under the instance's type arguments), or the elements of a
     * `Maybe`, `Result`, tuple, `Arr`, `Set`, `Deque`, `Stack`, `Queue` or a `Map`'s key, the
     * containers CppPlacement needs complete where the holder is defined. A reference, a
     * `List`, a `Map`'s value, a `Weak`, a `Ref` and an `Unsafe` hold nothing by value. A
     * generic expansion past [MAX_DEPTH] is taken as a cycle: C++ could not instantiate it.
     */
    private fun embeds(t: KType, target: ClassSymbol, candidates: Set<ClassSymbol>, path: MutableSet<KType>, depth: Int): Boolean {
        val n = t as? KType.Nominal ?: return false
        val sym = n.sym as? ClassSymbol ?: return false
        if (depth > MAX_DEPTH) {
            return true
        }
        return when {
            sym === target -> true
            sym.kind == ClassKind.MAGIC -> {
                val args = n.typeArgs()
                when (sym.name) {
                    "Weak", "Unsafe", "Ref", "List", "Fx" -> false
                    "Map" -> args.firstOrNull()?.let { embeds(it, target, candidates, path, depth + 1) } ?: false
                    else -> args.any { embeds(it, target, candidates, path, depth + 1) }
                }
            }
            sym.kind == ClassKind.STRUCT || sym in candidates -> {
                if (!path.add(n)) {
                    return false
                }
                val sub = sym.typeParams.zip(n.typeArgs()).toMap()
                val found = sym.fields.any { embeds(it.type.substitute(sub), target, candidates, path, depth + 1) }
                path.remove(n)
                found
            }
            else -> false
        }
    }

    /**
     * `cpp.value.lost` (1.2.1, rule b is whole-program): a subclass in one module makes a `pub`
     * class of another a reference, so the other module's C++ header changes from far away.
     */
    private fun valueLost(c: ClassSymbol) {
        val parent = c.superclass?.sym as? ClassSymbol ?: return
        if (parent.kind != ClassKind.USER || parent.module === c.module || !parent.isPub || parent.foreign != null || !parent.isImmutable) {
            return
        }
        if (parent.superclass != null || parent.traits.isNotEmpty() || parent.finally != null || parent.decl is VariantDecl) {
            return
        }
        val name = (c.decl as? ClassDecl)?.name ?: c.decl
        program.report(
            "cpp.value.lost",
            "${parent.module.cppNamespace}::${parent.name} is now a shared reference: C++ callers see kira::Rc<${parent.name}>, since ${c.name} " +
                "(${c.module.uri}) extends it; without a subclass it is a value",
            name,
            Severity.WARNING,
        )
    }

    /**
     * The synthesized `copy` (Q7): on an immutable user class, one parameter per field of the
     * chain (inherited ones first, in declaration order), each defaulting to the receiver's.
     * It is recorded on every immutable class, extended ones included, so a call on one is
     * `types.copy.extended` rather than an unknown member; a class that declares a `copy` of
     * its own is `types.copy.declared`.
     */
    private fun copy(c: ClassSymbol) {
        if (c.kind != ClassKind.USER || c.foreign != null || c.decl is VariantDecl) {
            return
        }
        c.methods.firstOrNull { it.name == COPY }?.let { own ->
            program.report(
                "types.copy.declared",
                "${c.name} declares its own `copy`; every immutable class has one already (Kotlin's data-class copy), so name this method otherwise.",
                own.decl ?: c.decl,
            )
            return
        }
        if (!c.isImmutable) {
            return
        }
        val fields = chainFields(c)
        val params = fields.mapIndexed { i, (f, type) -> ParamSymbol(f.name, c.module, f.decl, type, index = i) }
        val fn = FnSymbol(COPY, c.module, null, emptyList(), params, c.selfType, owner = c, hasBody = false)
        fn.isPub = true
        params.forEach { it.fn = fn }
        c.copyMethod = fn
    }

    companion object {
        const val COPY = "copy"
        private const val MAX_DEPTH = 48

        /** Every field of [c]'s superclass chain, root first, with its type under the chain's type arguments. */
        fun chainFields(c: ClassSymbol): List<Pair<FieldSymbol, KType>> {
            val links = mutableListOf<Pair<ClassSymbol, Map<TypeParamSymbol, KType>>>()
            var cur: ClassSymbol? = c
            var sub: Map<TypeParamSymbol, KType> = emptyMap()
            val seen = IdentityHashMap<ClassSymbol, Boolean>()
            while (cur != null && seen.put(cur, true) == null) {
                links.add(cur to sub)
                val sup = cur.superclass ?: break
                val supSym = sup.sym as? ClassSymbol ?: break
                val s = sub
                sub = supSym.typeParams.zip(sup.typeArgs().map { it.substitute(s) }).toMap()
                cur = supSym
            }
            return links.asReversed().flatMap { (cls, s) -> cls.fields.map { it to it.type.substitute(s) } }
        }
    }
}
