package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.core.CompilerIntrinsic

abstract class FirstClassDecl(
    override val name: Expr,
    open val modifiers: List<Modifier>,
    override val attachedIntrinsics: List<CompilerIntrinsic> = emptyList(),
) : Decl(name) {

    /**
     * True when marked @_magic (stdlib / compiler-owned). Foreign @_opaque / @_extern
     * are not magic -- they still participate in C-as-IR lowering.
     */
    fun isMagic(): Boolean {
        return attachedIntrinsics.any { it.name == "_magic" || it.name == "magic" }
    }

    abstract fun isStub(): Boolean
}