package net.exoad.kira.dialect

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.source.SourceContext

/**
 * Stage 4's tree proof: the old text's tree (linked, as typed) equals the new one's but where [allowed] says an old node
 * took one of stage 4's shapes: a type gained `Mutable` (a value type's struct became a class with its traits as
 * parents), a field gained it, a `mut fx` lost its `mut` (a magic one took `@_mutates`), a `mut` parameter, argument or
 * Fx parameter lost it. `mut x: T` and `x: Mutable<T>` are one tree, modifiers compared as a set.
 */
class Stage4Proof(private val oc: SourceContext, private val nc: SourceContext, private val allowed: Map<ASTNode, String>) {
    fun compare(): String? = list("root", oc.ast.statements, nc.ast.statements)

    private fun list(path: String, o: List<*>, n: List<*>): String? {
        if (o.size != n.size) return "$path: ${o.size} entries became ${n.size}"
        for (i in o.indices) value("$path[$i]", o[i], n[i])?.let { return it }
        return null
    }

    private fun value(path: String, o: Any?, n: Any?): String? {
        if (o is ASTNode && n is ASTNode) return node(path, o, n)
        if (o is List<*> && n is List<*>) return list(path, o, n)
        if (o is Array<*> && n is Array<*>) return list(path, o.toList(), n.toList())
        if (o is Map<*, *> && n is Map<*, *>) {
            if (o.keys != n.keys) return "$path: keys ${o.keys} became ${n.keys}"
            for (k in o.keys) value("$path[$k]", o[k], n[k])?.let { return it }
            return null
        }
        if (o != null && n != null && o.javaClass == n.javaClass && AstWalk.isAstPart(o)) {
            for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
            return null
        }
        if (o is net.exoad.kira.source.SourceLocation && n is net.exoad.kira.source.SourceLocation) return null
        if (o == n) return null
        return "$path: '$o' became '$n'"
    }

    private fun node(path: String, o: ASTNode, n: ASTNode): String? {
        val shape = allowed[o]
        when {
            shape == "type" -> return typeDecl(path, o, n)
            o is VariableDecl && n is VariableDecl -> return variable(path, o, n, shape == "field")
            o is FunctionDecl && n is FunctionDecl && shape != null -> return function(path, o, n, shape == "mutates")
            o is FunctionDeclParameterExpr && n is FunctionDeclParameterExpr && shape == "param" -> {
                if (Modifier.MUTABLE in n.modifiers) return "$path: the parameter kept its mut"
                return fields(path, listOf("name" to (o.name to n.name), "type" to (o.typeSpecifier to n.typeSpecifier), "default" to (o.defaultValue to n.defaultValue))) ?: modifiers(path, o.modifiers - Modifier.MUTABLE, n.modifiers)
            }
            o is FunctionCallPositionalParameterExpr && n is FunctionCallPositionalParameterExpr && shape == "arg" ->
                return if (n.isMut) "$path: the argument kept its mut" else value("$path.value", o.value, n.value)
            o is FunctionCallNamedParameterExpr && n is FunctionCallNamedParameterExpr && shape == "arg" ->
                return if (n.isMut) "$path: the argument kept its mut" else value("$path.name", o.name, n.name) ?: value("$path.value", o.value, n.value)
            o is Type && n is Type && shape == "fxparam" -> {
                if (n.isMutParam) return "$path: the Fx parameter kept its mut"
                return fields(path, listOf("identifier" to (o.identifier to n.identifier), "constraint" to (o.constraint to n.constraint), "children" to (o.children to n.children)))
            }
        }
        if (o.javaClass != n.javaClass) return "$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}"
        marks(path, o, n)?.let { return it }
        for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
        return null
    }

    private fun fields(path: String, parts: List<Pair<String, Pair<Any?, Any?>>>): String? {
        for ((name, pair) in parts) value("$path.$name", pair.first, pair.second)?.let { return it }
        return null
    }

    private fun modifiers(path: String, o: Collection<Modifier>, n: Collection<Modifier>): String? =
        if (o.toSet() == n.toSet()) null else "$path.modifiers: $o became $n"

    /** A type that became Mutable: its modifiers gain MUTABLE; a value type became a class whose parents are its traits. */
    private fun typeDecl(path: String, o: ASTNode, n: ASTNode): String? {
        marks(path, o, n, extra = emptySet())?.let { return it }
        val (oName, oMods, oMembers, oParents, oInit) = parts(o) ?: return "$path: ${o.javaClass.simpleName} is no type"
        val (nName, nMods, nMembers, nParents, nInit) = parts(n) ?: return "$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}"
        if (o is StructDecl && n !is ClassDecl) return "$path: a value type became ${n.javaClass.simpleName}, not a Mutable class"
        if (o !is StructDecl && o.javaClass != n.javaClass) return "$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}"
        if (Modifier.MUTABLE !in nMods) return "$path: the type is not Mutable"
        return value("$path.name", oName, nName) ?: modifiers(path, oMods + Modifier.MUTABLE, nMods)
            ?: value("$path.members", oMembers, nMembers) ?: value("$path.parents", oParents, nParents) ?: value("$path.initially", oInit, nInit)
    }

    private data class Parts(val name: Any?, val mods: List<Modifier>, val members: Any?, val parents: Any?, val initially: Any?)

    private fun parts(d: ASTNode): Parts? = when (d) {
        is ClassDecl -> Parts(d.name, d.modifiers, d.members, d.parents, d.initially)
        is StructDecl -> Parts(d.name, d.modifiers, d.members, d.traits, d.initially)
        is TraitDecl -> Parts(d.name, d.modifiers.toList(), d.members, d.parents, null)
        else -> null
    }

    /** A binding: its modifiers as a set (`mut x` and `x: Mutable<..>` put MUTABLE apart); [gains] a field that became Mutable. */
    private fun variable(path: String, o: VariableDecl, n: VariableDecl, gains: Boolean): String? {
        marks(path, o, n)?.let { return it }
        val want = if (gains) o.modifiers + Modifier.MUTABLE else o.modifiers
        return value("$path.name", o.name, n.name) ?: value("$path.type", o.type, n.type) ?: modifiers(path, want, n.modifiers) ?: value("$path.value", o.value, n.value)
    }

    /** A `mut fx` that lost its `mut`: a stdlib magic one ([mutates]) took `@_mutates`. */
    private fun function(path: String, o: FunctionDecl, n: FunctionDecl, mutates: Boolean): String? {
        marks(path, o, n, extra = if (mutates) setOf("_mutates") else emptySet())?.let { return it }
        if (Modifier.MUTABLE in n.modifiers) return "$path: the function kept its mut"
        return value("$path.name", o.name, n.name) ?: modifiers(path, o.modifiers - Modifier.MUTABLE, n.modifiers) ?: value("$path.def", o.def, n.def) ?: value("$path.generics", o.generics, n.generics)
    }

    /** The intrinsics on both nodes by name ([extra] the new one may add), and their parentheses. */
    private fun marks(path: String, o: ASTNode, n: ASTNode, extra: Set<String> = emptySet()): String? {
        val om = oc.intrinsicInvocationsOf(o).map { it.intrinsicKey.name }
        val nm = nc.intrinsicInvocationsOf(n).map { it.intrinsicKey.name }.filterNot { it in extra && it !in om }
        if (om != nm) return "$path: marks $om became $nm"
        if ((o in oc.astParenthesized) != (n in nc.astParenthesized)) return "$path: parentheses changed"
        return null
    }
}
