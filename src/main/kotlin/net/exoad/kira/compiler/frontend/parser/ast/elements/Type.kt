package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class Type(
    open val identifier: Identifier,
    open val constraint: Type?,
    open val children: List<Type>
) : Expr() {
    constructor(name: Identifier) : this(name, null, emptyList())

    init {
        require(children.count { it is VariadicTypeParameter } < 2) {
            "Variadic Types count must not exceed 1!"
        }
        if (hasVariadic()) {
            require(children.last() is VariadicTypeParameter) {
                "Found variadic at index [${children.indexOfFirst { it is VariadicTypeParameter }}] other than last."
            }
        }
    }

    fun hasVariadic(): Boolean {
        return children.any { it is VariadicTypeParameter }
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitType(this)
    }

    override fun toString(): String {
        return "T<${identifier.value}${if (constraint != null) "++${constraint}" else ""} $children>"
    }
}

