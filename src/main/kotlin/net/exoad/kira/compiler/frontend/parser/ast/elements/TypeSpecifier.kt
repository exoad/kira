package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

class TypeSpecifier(
    val name: String,
    val genericParameters: Array<GenericParameter> = emptyArray(),
) : Expr() {
    fun hasVariadicParameters(): Boolean {
        return genericParameters.any { it is VariadicGenericParameter }
    }

    fun getVariadicParameter(): GenericParameter? {
        return genericParameters.find { it is VariadicGenericParameter }
    }

    fun validateVariadicConstraints(): Boolean {
        return genericParameters.count { it is VariadicGenericParameter } <= 1
    }

    override fun toString(): String {
        return "T< '$name'${
            if (genericParameters.isNotEmpty()) {
                "<${genericParameters.joinToString(", ") { it.toString() }}>"
            } else ""
        } >"
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeSpecifier(this)
    }
}