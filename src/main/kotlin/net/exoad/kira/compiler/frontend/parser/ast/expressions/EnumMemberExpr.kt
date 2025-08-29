package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.literals.DataLiteral

/**
 * An enumerated constant within an enum.
 *
 * [value] although is of [DataLiteral], the parser makes sure that this also implements [net.exoad.kira.compiler.frontend.parser.ast.literals.SimpleLiteral]
 * due to the nature and grammar of the language. we do not allow complex literals like [net.exoad.kira.compiler.frontend.parser.ast.literals.MapLiteral], [net.exoad.kira.compiler.frontend.parser.ast.literals.ListLiteral]
 */
open class EnumMemberExpr(val name: Identifier, val value: DataLiteral<*>?) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitEnumMemberExpr(this)
    }

    override fun toString(): String {
        return "EnumMember{ $name ${if (value != null) "-> $value" else ""} }"
    }
}