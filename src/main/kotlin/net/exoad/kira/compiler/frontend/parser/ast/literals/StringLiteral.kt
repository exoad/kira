package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class StringLiteral(override val value: String) : DataLiteral<String>(value), SimpleLiteral {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitStringLiteral(this)
    }

    override fun toString(): String {
        // escape newlines and quotes for safer single-line representation
        val safe = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
        return "LStr{ \"$safe\" }"
    }
}