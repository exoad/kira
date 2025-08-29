package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

class ModuleDecl(val uri: StringLiteral) : Decl(uri) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitModuleDecl(this)
    }

    fun getName(): String {
        return uri.value.split(":").last()
    }

    fun getAuthor(): String {
        return uri.value.split(":").first()
    }

    override fun toString(): String {
        return "__MOD__{ $uri }"
    }
}
