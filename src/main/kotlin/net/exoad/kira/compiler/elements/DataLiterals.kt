package net.exoad.kira.compiler.elements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

abstract class DataLiteral<T>(open val value: T) : Literal()

open class IntegerLiteral(override val value: Long) : DataLiteral<Long>(value), SimpleLiteral
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

open class FloatLiteral(override val value: Double) : DataLiteral<Double>(value), SimpleLiteral
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

open class StringLiteral(override val value: String) : DataLiteral<String>(value), SimpleLiteral
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

/**
 * A static array that cannot be resized like [Array]
 */
open class ArrayLiteral(override val value: Array<Expr>) : DataLiteral<Array<Expr>>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitArrayLiteral(this)
    }

    override fun toString(): String
    {
        return "LArray{ $value }"
    }
}

/**
 * Akin to [List] or [java.util.ArrayList] where it is a dynamic array
 */
open class ListLiteral(override val value: List<Expr>) : DataLiteral<List<Expr>>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitListLiteral(this)
    }

    override fun toString(): String
    {
        return "LList{ $value }"
    }
}

open class MapLiteral(override val value: Map<Expr, Expr>, val mutable: Boolean) : DataLiteral<Map<Expr, Expr>>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitMapLiteral(this)
    }

    override fun toString(): String
    {
        return "LMap${if(mutable) "[[ MUTABLE ]]" else ""}{ $value }"
    }
}

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