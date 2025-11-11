package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class FunctionCallExpr(
    val name: Expr,
    val positionalParameters: List<FunctionCallPositionalParameterExpr>,
    val namedParameters: List<FunctionCallNamedParameterExpr>,
) : Expr() {
    // this piece check is kind of faulty, most often it will report `name` as `null` which is not really possible
//    init {
//        require(name is IntrinsicExpr || name is Identifier) {
//            "Function can only be named using identifiers or intrinsic markers!"
//        }
//    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallExpr(this)
    }

    override fun toString(): String {
        return "FxCall{ $name -> {$positionalParameters} {$namedParameters} }"
    }
}