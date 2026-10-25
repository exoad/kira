package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

/**
 * `'c'` (design D3): one byte, 0..255. The parser decodes the escapes
 * `\n \t \r \\ \' \0` before building this node.
 */
open class CharLiteral(override val value: Int) : DataLiteral<Int>(value), SimpleLiteral {
    init {
        require(value in 0..255) { "A Char literal holds one byte (0..255), not $value" }
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitCharLiteral(this)
    }

    override fun toString(): String {
        return "LChar{ $value }"
    }
}
