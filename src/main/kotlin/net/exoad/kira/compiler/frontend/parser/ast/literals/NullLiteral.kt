package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor

private val nullRep = Any()

object NullLiteral : DataLiteral<Any>(nullRep)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitNullLiteral(this)
    }

    override fun toString(): String
    {
        return "LNull{ }"
    }
}