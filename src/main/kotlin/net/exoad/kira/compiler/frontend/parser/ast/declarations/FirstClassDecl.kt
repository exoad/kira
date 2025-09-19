package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.core.Intrinsic

abstract class FirstClassDecl(
    override val name: Expr,
    open val modifiers: List<Modifier>,
    open val attachedIntrinsics: List<IntrinsicExpr> = emptyList(),
) : Decl(name) {

    /**
     * A magic declaration is one that is intrinsified by the compiler itself
     */
    fun isMagic(): Boolean {
        return attachedIntrinsics.any { it.intrinsicKey == Intrinsic.MAGIC }
    }

    abstract fun isStub(): Boolean
}