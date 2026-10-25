package net.exoad.kira.dialect

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

// Reflective access to an AST: every node's own fields, for the converter's proof and analysis.
object AstWalk {
    private val fieldCache = HashMap<Class<*>, List<Field>>()

    // The instance fields of [c] and its superclasses up to ASTNode, in declaration order.
    fun fieldsOf(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
        val out = mutableListOf<Field>()
        var k: Class<*>? = c
        val chain = mutableListOf<Class<*>>()
        while (k != null && k != Any::class.java) {
            chain.add(0, k)
            k = k.superclass
        }
        for (cls in chain) {
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                f.isAccessible = true
                out.add(f)
            }
        }
        out
    }

    // A value of the AST that is no node (an interpolation's piece): walked by its fields too.
    fun isAstPart(v: Any): Boolean = v !is ASTNode && !v.javaClass.isEnum &&
        v.javaClass.name.startsWith("net.exoad.kira.compiler.frontend.parser.ast.")

    // Every node reachable from [root], each once, in a depth-first order.
    fun all(root: ASTNode): List<ASTNode> {
        val seen = IdentityHashMap<Any, Boolean>()
        val out = mutableListOf<ASTNode>()
        fun visit(v: Any?) {
            when (v) {
                null -> {}
                is ASTNode -> {
                    if (seen.put(v, true) != null) return
                    out.add(v)
                    for (f in fieldsOf(v.javaClass)) visit(f.get(v))
                }
                is Collection<*> -> v.forEach { visit(it) }
                is Array<*> -> v.forEach { visit(it) }
                is Map<*, *> -> v.forEach { (k, x) -> visit(k); visit(x) }
                is Pair<*, *> -> { visit(v.first); visit(v.second) }
                else -> if (isAstPart(v) && seen.put(v, true) == null) {
                    for (f in fieldsOf(v.javaClass)) visit(f.get(v))
                }
            }
        }
        visit(root)
        return out
    }
}
