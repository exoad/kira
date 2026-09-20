package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class Identifier(open val value: String) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIdentifier(this)
    }

    override fun toString(): String {
        return "'${value.replace("'","\\'") }'"
    }

    fun length(): Int {
        return value.length
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as Identifier
        return value == other.value
    }
}
