package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

class ModuleDecl(val uri: StringLiteral) : Decl(uri) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitModuleDecl(this)
    }

    fun getPackageName(): String {
        return uri.value.split(":").first()
    }

    fun getModuleName(): String {
        return uri.value.split(":").last().split(".").last()
    }

    fun getIncrementals(): List<String> {
        return uri.value.split(":").last().split(".").dropLast(1)
    }

    override fun toString(): String {
        return "Module(pkg=${getPackageName()}, uri=${getIncrementals().joinToString { "$it." }}${getModuleName()})"
    }
}
