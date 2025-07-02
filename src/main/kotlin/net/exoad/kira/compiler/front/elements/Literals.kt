package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

abstract class Literal<T>(open val value: T) : Expr()

open class IntegerLiteral(override val value: Long) : Literal<Long>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIntegerLiteral(this)
    }

    override fun toString(): String
    {
        return "LInteger{ $value }"
    }
}

open class FloatLiteral(override val value: Double) : Literal<Double>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFloatLiteral(this)
    }

    override fun toString(): String
    {
        return "LFloat{ $value }"
    }
}

open class StringLiteral(override val value: String) : Literal<String>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitStringLiteral(this)
    }

    override fun toString(): String
    {
        return "LString{ $value }"
    }
}

open class BoolLiteral(override val value: Boolean) : Literal<Boolean>(value)
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