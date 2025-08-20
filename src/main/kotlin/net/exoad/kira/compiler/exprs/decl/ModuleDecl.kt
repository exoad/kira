package net.exoad.kira.compiler.exprs.decl

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.StringLiteral

class ModuleDecl(val uri: StringLiteral) : Decl(uri)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitModuleDecl(this)
    }

    override fun toString(): String
    {
        return "ModuleDecl{ $uri }"
    }
}
