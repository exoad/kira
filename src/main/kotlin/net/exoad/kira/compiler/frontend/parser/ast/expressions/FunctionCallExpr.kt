package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class FunctionCallExpr(
    val name: Identifier,
    val positionalParameters: List<FunctionCallPositionalParameterExpr>,
    val namedParameters: List<FunctionCallNamedParameterExpr>,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallExpr(this)
    }

    override fun toString(): String {
        return "FxCall{ $name -> {$positionalParameters} {$namedParameters} }"
    }
}