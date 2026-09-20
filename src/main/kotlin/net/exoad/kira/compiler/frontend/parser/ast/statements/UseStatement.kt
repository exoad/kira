package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

open class UseStatement(val uri: StringLiteral) : Statement(uri) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitUseStatement(this)
    }

    override fun toString(): String {
        return "UseStatement(uri=$uri)"
    }
}