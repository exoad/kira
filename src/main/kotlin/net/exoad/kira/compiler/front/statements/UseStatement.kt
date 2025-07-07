package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.StringLiteral

open class UseStatement(val uri: StringLiteral) : Statement(uri)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitUseStatement(this)
    }

    override fun toString(): String
    {
        return "UseStatement{ $uri }"
    }
}