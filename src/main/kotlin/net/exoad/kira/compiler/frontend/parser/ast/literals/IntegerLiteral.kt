package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class IntegerLiteral(override val value: Long) : DataLiteral<Long>(value), SimpleLiteral {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIntegerLiteral(this)
    }

    override fun toString(): String {
        return "LInteger{ $value }"
    }
}