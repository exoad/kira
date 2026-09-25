package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import java.util.IdentityHashMap

/**
 * Which free-function names a call written in a given module reaches before
 * the ambient `kira:*` magic names, in the typer's scope order (design 3.2):
 * the module's own members first, then the `pub` members of the modules it
 * `use`s, then the ambient stdlib.
 *
 * A call to a name outside that set is the stdlib's, however many other
 * modules of the unit spell a function the same way: a private `floor` in
 * `app:util` is not `app:main`'s `floor`, and a user `min` is never the `min`
 * the stdlib's own `clamp` body (in `kira:math`) calls. Both backends ask
 * this one table, so C and JS agree on which function a call reaches.
 *
 * Only non-magic declarations count. A module's own `@_magic` signature has
 * no body to call, so a name that resolves to one is lowered through the
 * binding table exactly as an ambient name is.
 */
class ModuleFunctionScopes private constructor(
    private val modules: Map<String, ModuleFunctions>,
    private val ownerOf: IdentityHashMap<Decl, String>,
) {
    private class ModuleFunctions {
        /** Every non-magic function the module declares, private ones included. */
        val all = linkedSetOf<String>()
        /** The `pub` subset, which a `use` of this module brings into scope. */
        val pub = linkedSetOf<String>()
        /** The module URIs this module `use`s. */
        val uses = linkedSetOf<String>()
    }

    /** The URI of the module that declares [decl] at top level, or null. */
    fun moduleOf(decl: Decl): String? {
        return ownerOf[decl]
    }

    /**
     * True when a call to [name] written in the module [callerUri] reaches a
     * function the unit declares and emits, rather than the ambient magic
     * name of the same spelling. A caller with no known module reaches
     * nothing: the binding table serves it, as it did before shadowing
     * existed.
     */
    fun resolves(callerUri: String?, name: String): Boolean {
        val caller = modules[callerUri ?: return false] ?: return false
        if (name in caller.all) {
            return true
        }
        return caller.uses.any { used -> modules[used]?.pub?.contains(name) == true }
    }

    companion object {
        /**
         * Reads every source of [unit], stdlib modules included (they are
         * callers too: `clamp` is written in Kira), and asks [isMagic] which
         * function declarations are signatures only.
         */
        fun collect(unit: CompilationUnit, isMagic: (FunctionDecl) -> Boolean): ModuleFunctionScopes {
            val modules = linkedMapOf<String, ModuleFunctions>()
            val ownerOf = IdentityHashMap<Decl, String>()
            unit.allSources().forEach { source ->
                val uri = runCatching { source.getModuleUri() }.getOrNull() ?: return@forEach
                val statements = runCatching { source.ast.statements }.getOrNull() ?: return@forEach
                val module = modules.getOrPut(uri) { ModuleFunctions() }
                statements.forEach { stmt ->
                    if (stmt is UseStatement) {
                        module.uses.add(stmt.uri.value)
                        return@forEach
                    }
                    val decl: Any? = when (stmt) {
                        is Decl -> stmt
                        is Statement -> stmt.expr
                        else -> null
                    }
                    if (decl is Decl) {
                        ownerOf[decl] = uri
                    }
                    if (decl is FunctionDecl && !isMagic(decl)) {
                        val name = nameOf(decl.name) ?: return@forEach
                        module.all.add(name)
                        if (decl.modifiers.contains(Modifier.PUBLIC)) {
                            module.pub.add(name)
                        }
                    }
                }
            }
            return ModuleFunctionScopes(modules, ownerOf)
        }

        private fun nameOf(expr: Expr): String? {
            return when (expr) {
                is Identifier -> expr.value
                is IntrinsicExpr -> expr.intrinsicKey.name
                else -> null
            }
        }
    }
}
