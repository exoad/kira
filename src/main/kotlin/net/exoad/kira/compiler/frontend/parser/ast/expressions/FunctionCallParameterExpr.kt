package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class FunctionCallNamedParameterExpr(val name: Identifier, val value: Expr) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallNamedParameterExpr(this)
    }

    override fun toString(): String {
        return "FxNamedParam{ $name -> $value }"
    }
}

open class FunctionCallPositionalParameterExpr(val position: Int, val value: Expr) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionCallPositionalParameterExpr(this)
    }

    override fun toString(): String {
        return "FxPosParam{ $position -> $value }"
    }
}