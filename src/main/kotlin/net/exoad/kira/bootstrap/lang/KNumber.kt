package net.exoad.kira.bootstrap.lang

import net.exoad.kira.bootstrap.*
import net.exoad.kira.compiler.Token
import net.exoad.kira.compiler.elements.IntegerLiteral

@KiraClass("Num")
@KiraCannotInstantiate
@KiraCoerceLiteralToken([Token.Type.L_FLOAT, Token.Type.L_INTEGER])
sealed class KNumber<T : KNumber<T>>(open val value: Number) : KAny()
{
    @KiraIntrinsicFunction("__plus__", "+", [KNumber::class], KNumber::class)
    @KiraNoTailRecursion
    abstract fun plus(other: T): T

    @KiraIntrinsicFunction("__minus__", "-", [KNumber::class], KNumber::class)
    @KiraNoTailRecursion
    abstract fun minus(other: T): T

    @KiraIntrinsicFunction("__times__", "*", [KNumber::class], KNumber::class)
    @KiraNoTailRecursion
    abstract fun times(other: T): T

    @KiraIntrinsicFunction("__divide__", "/", [KNumber::class], KNumber::class)
    @KiraNoTailRecursion
    abstract fun divide(other: T): T

    @KiraIntrinsicFunction("__remainder__", "/", [KNumber::class], KNumber::class)
    @KiraNoTailRecursion
    abstract fun remainder(other: T): T

    override fun toString(): String
    {
        return value.toString()
    }

    @KiraClass("Int32")
    @KiraSubClass(KNumber::class)
    @KiraSugarLiteralInstantiate<Long, IntegerLiteral>(IntegerLiteral::class)
    @KiraCoerceLiteralToken([Token.Type.L_INTEGER])
    class KInt32(override val value: Long) : KNumber<KInt32>(value)
    {
        @KiraIntrinsicFunction("__plus__", "+", [KNumber::class], KNumber::class)
        @KiraNoTailRecursion
        override fun plus(@KiraRefLiteral(Int::class) other: KInt32): KInt32
        {
            @KiraPragmaUseStackInliner val res = value + other.value
            return KInt32(res)
        }

        @KiraIntrinsicFunction("__minus__", "-", [KNumber::class], KNumber::class)
        @KiraNoTailRecursion
        override fun minus(other: KInt32): KInt32
        {
            @KiraPragmaUseStackInliner val res = value - other.value
            return KInt32(res)
        }

        override fun times(other: KInt32): KInt32
        {
            @KiraPragmaUseStackInliner val res = @KiraVectorizeNoExtract(true) (value * other.value)
            return KInt32(res)
        }

        override fun divide(other: KInt32): KInt32
        {
            @KiraPragmaUseStackInliner val res = @KiraVectorizeNoExtract(true) (value / other.value)
            return KInt32(res)
        }

        override fun remainder(other: KInt32): KInt32
        {
            @KiraPragmaUseStackInliner val res = @KiraVectorizeNoExtract(true) (value % other.value)
            return KInt32(res)
        }
    }
}

