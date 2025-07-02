package net.exoad.kira.compiler.back

import net.exoad.kira.compiler.front.ASTVisitor

abstract class KiraTranspiler(val canonicalName: String, val targetLanguage: String, val fileExtension: String) :
    ASTVisitor()
{
    abstract fun transpile()
}