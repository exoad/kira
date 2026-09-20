package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class FloatLiteral(override val value: Double) : DataLiteral<Double>(value), SimpleLiteral {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFloatLiteral(this)
    }

    override fun toString(): String {
        return "LFloat{ $value }"
    }
}