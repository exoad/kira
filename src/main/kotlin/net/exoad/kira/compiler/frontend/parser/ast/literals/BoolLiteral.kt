package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor

open class BoolLiteral(override val value: Boolean) : DataLiteral<Boolean>(value), SimpleLiteral
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBoolLiteral(this)
    }

    override fun toString(): String
    {
        return "LBool{ $value }"
    }
}