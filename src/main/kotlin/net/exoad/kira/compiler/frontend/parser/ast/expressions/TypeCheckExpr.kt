package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier

class TypeCheckExpr(val value: Expr, val type: TypeSpecifier) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeCheckExpr(this)
    }

    override fun toString(): String {
        return "TypeCheck{ $value -> $type }"
    }
}