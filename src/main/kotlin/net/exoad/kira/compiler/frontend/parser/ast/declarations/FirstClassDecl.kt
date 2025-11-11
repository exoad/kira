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
     * A magic declaration is one that is intrinsified by the compiler itself
     */
    fun isMagic(): Boolean {
        // TODO: Make this actually point to a magic declarative intrinsic
        return attachedIntrinsics.any { true }
    }

    abstract fun isStub(): Boolean
}