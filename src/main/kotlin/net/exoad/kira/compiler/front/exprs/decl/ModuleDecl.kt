package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.StringLiteral

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
