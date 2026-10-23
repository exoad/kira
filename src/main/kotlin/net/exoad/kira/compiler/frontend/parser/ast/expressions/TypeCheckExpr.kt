package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/** `value == Identity` (or, [negated], `value != Identity`): whether the value conforms to the identity. */
class TypeCheckExpr(val value: Expr, val type: Type, val negated: Boolean = false) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeCheckExpr(this)
    }

    override fun toString(): String {
        return "TypeCheck(value=$value, type=$type${if (negated) ", negated" else ""})"
    }
}