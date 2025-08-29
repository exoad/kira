package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

abstract class GenericParameter(open val name: String) : Expr() {
    abstract override fun toString(): String
}