package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr

/**
 * What the C and JS backends know about the program's user classes, read from the AST alone
 * (these backends do not read the typer's model). Built once per emit from the non-generic,
 * non-magic class declarations.
 *
 * It answers the questions the member-operator lowering asks:
 *  - which method a class declares or inherits by name, operators included (`_op_add_`);
 *  - whether a class is immutable by 1.1's predicate: no `mut` field and no `mut fx`, own or
 *    inherited;
 *  - the full field list, inherited fields first, which construction, `==` and `copy` all use;
 *  - the hierarchy a class sits in, for the synthesized `==`.
 *
 * Single inheritance only: the first parent that is itself a class of the table is the parent;
 * the others are traits.
 */
class UserClassTable(decls: List<ClassDecl>) {
    class Info(
        val name: String,
        val decl: ClassDecl,
        val parent: String?,
        val ownFields: List<VariableDecl>,
        val methods: Map<String, FunctionDecl>,
    )

    private val infos = linkedMapOf<String, Info>()

    init {
        val names = decls.mapNotNull { nameOf(it.name) }.toSet()
        for (decl in decls) {
            val name = nameOf(decl.name) ?: continue
            val parent = decl.parents.firstNotNullOfOrNull { p -> nameOf(p)?.takeIf { it in names && it != name } }
            val methods = linkedMapOf<String, FunctionDecl>()
            decl.members.filterIsInstance<FunctionDecl>().forEach { fn ->
                val fnName = methodNameOf(fn)
                if (fnName != null) methods[fnName] = fn
            }
            infos[name] = Info(name, decl, parent, decl.members.filterIsInstance<VariableDecl>(), methods)
        }
    }

    val classNames: Set<String> get() = infos.keys

    fun has(name: String?): Boolean = name != null && infos.containsKey(name)

    fun info(name: String?): Info? = if (name == null) null else infos[name]

    fun parentOf(name: String): String? = infos[name]?.parent

    /** The class, then its parents, nearest first. */
    fun lineage(name: String): List<String> {
        val out = mutableListOf<String>()
        var cur: String? = name
        while (cur != null && infos.containsKey(cur) && cur !in out) {
            out.add(cur)
            cur = infos[cur]!!.parent
        }
        return out
    }

    fun rootOf(name: String): String = lineage(name).last()

    /** Every field, inherited ones first, each class's in declaration order. */
    fun allFields(name: String): List<VariableDecl> =
        lineage(name).asReversed().flatMap { infos[it]!!.ownFields }

    /** The declaring class and the method, found on the class or its nearest ancestor. */
    fun findMethod(name: String, methodName: String): Pair<String, FunctionDecl>? {
        for (cls in lineage(name)) {
            infos[cls]!!.methods[methodName]?.let { return cls to it }
        }
        return null
    }

    /** 1.1: mutable when this class or an ancestor declares a `mut` field or a `mut fx`. */
    fun isMutable(name: String): Boolean = lineage(name).any { cls ->
        val info = infos[cls]!!
        info.ownFields.any { Modifier.MUTABLE in it.modifiers } ||
            info.methods.values.any { Modifier.MUTABLE in it.modifiers }
    }

    fun isImmutable(name: String): Boolean = has(name) && !isMutable(name)

    fun isExtended(name: String): Boolean = infos.values.any { it.parent == name }

    fun children(name: String): List<String> = infos.values.filter { it.parent == name }.map { it.name }

    /** The class and every class below it. */
    fun descendants(name: String): List<String> {
        val out = mutableListOf(name)
        var i = 0
        while (i < out.size) {
            children(out[i]).forEach { if (it !in out) out.add(it) }
            i++
        }
        return out
    }

    /** True when the class has a parent or a child: its objects need a dynamic class tag. */
    fun inHierarchy(name: String): Boolean = has(name) && (parentOf(name) != null || isExtended(name))

    /**
     * `==` by 1.2.5 for a class that declares or inherits no `@_op_eq_`: fields for an immutable
     * class, identity for a mutable one.
     */
    fun synthesizesEq(name: String): Boolean =
        has(name) && findMethod(name, EQ) == null && isImmutable(name)

    /** `copy` (1.2.3): an immutable class nothing extends and that does not declare `copy`. */
    fun hasCopy(name: String): Boolean =
        has(name) && isImmutable(name) && !isExtended(name) && findMethod(name, "copy") == null

    companion object {
        const val EQ = "_op_eq_"
        const val NEQ = "_op_neq_"
        const val GET = "_op_get_"
        const val SET = "_op_set_"

        fun nameOf(type: Type): String? = (type.identifier as? Identifier)?.value

        /** `_op_add_` for `fx @_op_add_`, the plain name otherwise. Anonymous methods have none. */
        fun methodNameOf(fn: FunctionDecl): String? = when (val n = fn.name) {
            is Identifier -> n.value
            is IntrinsicExpr -> n.intrinsicKey.name
            else -> null
        }
    }
}
