package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/**
 * Akin to [List] or [java.util.ArrayList] where it is a dynamic array
 */
open class ListLiteral(override val value: List<Expr>) : DataLiteral<List<Expr>>(value) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitListLiteral(this)
    }

    override fun toString(): String {
        return "LList{ $value }"
    }
}