package net.exoad.kira.compiler.backend.transpiler

import net.exoad.kira.compiler.frontend.ASTVisitor

abstract class KiraTranspiler(val canonicalName: String, val targetLanguage: String, val fileExtension: String) :
    ASTVisitor()
{
    abstract fun transpile()
}