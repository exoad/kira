package net.exoad.kira.compiler.analysis.types

import java.math.BigInteger

/**
 * A compile-time value: what ConstEval folds a global initializer, an enum entry or a
 * default to, and what TypedModel.consts records per expression.
 */
sealed interface ConstValue {
    /** The value's Kira type. */
    val type: KType

    /**
     * An integer of [prim]. [bits] holds the value itself for every prim but `UInt64`, whose
     * values above `Long.MAX_VALUE` are stored as their two's-complement bit pattern; read it
     * through [value] to get the mathematical number.
     */
    data class IntConst(val bits: Long, val prim: Prim) : ConstValue {
        init {
            require(prim.isInteger) { "IntConst needs an integer prim, not $prim" }
        }

        override val type: KType get() = KType.Scalar(prim)

        val value: BigInteger
            get() = if (!prim.signed && bits < 0) BigInteger.valueOf(bits).add(BigInteger.ONE.shiftLeft(64)) else BigInteger.valueOf(bits)

        override fun toString(): String = "${value}:${prim.kiraName}"

        companion object {
            /** [value] as an IntConst of [prim]; the caller has range-checked it. */
            fun of(value: BigInteger, prim: Prim): IntConst = IntConst(value.toLong(), prim)
        }
    }

    /** A float of [prim]; a Float32 value is already rounded to single precision. */
    data class FloatConst(val value: Double, val prim: Prim) : ConstValue {
        init {
            require(prim.isFloat) { "FloatConst needs a float prim, not $prim" }
        }

        override val type: KType get() = KType.Scalar(prim)
        override fun toString(): String = "${value}:${prim.kiraName}"
    }

    data class BoolConst(val value: Boolean) : ConstValue {
        override val type: KType get() = KType.BOOL
        override fun toString(): String = value.toString()
    }

    /** A `Char`: one code unit, 0..255. */
    data class CharConst(val value: Int) : ConstValue {
        override val type: KType get() = KType.CHAR
        override fun toString(): String = "'${escape(value)}'"

        private fun escape(c: Int): String = when (c) {
            '\n'.code -> "\\n"
            '\t'.code -> "\\t"
            '\r'.code -> "\\r"
            '\\'.code -> "\\\\"
            '\''.code -> "\\'"
            0 -> "\\0"
            in 32..126 -> c.toChar().toString()
            else -> "\\x%02x".format(c)
        }
    }

    /** A `Str` that folded to one literal (concatenations of literal constants included). */
    data class StrConst(val value: String) : ConstValue {
        override val type: KType get() = KType.Str
        override fun toString(): String = "\"" + value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""
    }

    data class EnumConst(val entry: EnumEntrySymbol) : ConstValue {
        override val type: KType get() = KType.Nominal(entry.owner)
        override fun toString(): String = "${entry.owner.name}.${entry.name}"
    }

    /**
     * An `Arr` literal of constants. [type] is the `Arr` type it was folded against: `Arr<T>`,
     * or `Arr<T, N>` for a fixed-size declaration.
     */
    data class ArrConst(val elements: List<ConstValue>, override val type: KType) : ConstValue {
        /** The element type `T`. */
        val element: KType get() = (type as? KType.Nominal)?.typeArgs()?.firstOrNull() ?: KType.Error
        override fun toString(): String = elements.joinToString(", ", "[", "]")
    }

    /** `null`, the only value of `Null`. */
    data object NullConst : ConstValue {
        override val type: KType get() = KType.NullT
        override fun toString(): String = "null"
    }
}
