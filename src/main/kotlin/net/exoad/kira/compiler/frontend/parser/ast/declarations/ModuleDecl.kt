package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral

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
