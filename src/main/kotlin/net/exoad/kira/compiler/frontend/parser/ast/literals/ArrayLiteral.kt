package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/**
 * A static array that cannot be resized like [Array]
 */
open class ArrayLiteral(override val value: Array<Expr>) : DataLiteral<Array<Expr>>(value) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitArrayLiteral(this)
    }

    override fun toString(): String {
        return "LArray{ $value }"
    }
}