package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class BoolLiteral(override val value: Boolean) : DataLiteral<Boolean>(value), SimpleLiteral {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitBoolLiteral(this)
    }

    override fun toString(): String {
        return "LBool{ $value }"
    }
}