package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

class TypeCheckExpr(val value: Expr, val type: Type) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeCheckExpr(this)
    }

    override fun toString(): String {
        return "TypeCheck{ $value -> $type }"
    }
}