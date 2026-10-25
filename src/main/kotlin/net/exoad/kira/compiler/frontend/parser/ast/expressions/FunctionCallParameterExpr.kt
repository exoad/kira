package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

/**
 * `name = value` at a call site. [isMut] is the call-site marker `f(name = mut y)`
 * (design D4): the argument is passed for mutation.
 */
open class FunctionCallNamedParameterExpr(
    val name: Identifier,
    val value: Expr,
    val isMut: Boolean = false,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallNamedParameterExpr(this)
    }

    override fun toString(): String {
        return "FxNamedParam{ $name -> ${if (isMut) "mut " else ""}$value }"
    }
}

/**
 * The [position]-th positional argument. [isMut] is the call-site marker
 * `f(x, mut y)` (design D4).
 */
open class FunctionCallPositionalParameterExpr(
    val position: Int,
    val value: Expr,
    val isMut: Boolean = false,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallPositionalParameterExpr(this)
    }

    override fun toString(): String {
        return "FxPosParam{ $position -> ${if (isMut) "mut " else ""}$value }"
    }
}
