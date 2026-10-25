package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/**
 * `value == Identity` (or, [negated], `value != Identity`): whether the value conforms to the identity.
 * [checks] is the test block after it (stage 3b), `reply == HttpReply { status == 200 }`, its checks ANDed.
 */
class TypeCheckExpr(val value: Expr, val type: Type, val negated: Boolean = false, val checks: List<Expr>? = null) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeCheckExpr(this)
    }

    override fun toString(): String {
        return "TypeCheck(value=$value, type=$type${if (negated) ", negated" else ""}${checks?.let { ", checks=$it" } ?: ""})"
    }
}
