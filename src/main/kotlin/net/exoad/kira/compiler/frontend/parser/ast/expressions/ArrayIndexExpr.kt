package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.core.CompilerIntrinsic

open class ArrayIndexExpr(
    val originExpr: Expr,
    val indexExpr: Expr,
    override val attachedIntrinsics: List<CompilerIntrinsic> = emptyList()
) : Expr(attachedIntrinsics) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitArrayIndexExpr(this)
    }

    override fun toString(): String {
        return "ArrayIndex{ $originExpr [ $indexExpr ] }"
    }
}
