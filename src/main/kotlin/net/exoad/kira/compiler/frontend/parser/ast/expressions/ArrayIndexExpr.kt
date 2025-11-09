package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class ArrayIndexExpr(val originExpr: Expr, val indexExpr: Expr) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitArrayIndexExpr(this)
    }

    override fun toString(): String {
        return "ArrayIndex{ $originExpr [ $indexExpr ] }"
    }
}

