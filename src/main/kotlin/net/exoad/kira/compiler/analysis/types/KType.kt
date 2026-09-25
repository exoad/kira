package net.exoad.kira.compiler.analysis.types

import java.math.BigInteger

/**
 * The scalar types Kira knows without a declaration. A stdlib `@_magic class Int32`
 * (kira:core) names the same prim; so does the bare name when the stdlib does not
 * declare it (UInt8..UInt64, Size and Char arrive with the stdlib surface package, and
 * the typer must not require them).
 */
enum class Prim(val kiraName: String, val bits: Int, val signed: Boolean, val isFloat: Boolean) {
    INT8("Int8", 8, true, false),
    INT16("Int16", 16, true, false),
    INT32("Int32", 32, true, false),
    INT64("Int64", 64, true, false),
    UINT8("UInt8", 8, false, false),
    UINT16("UInt16", 16, false, false),
    UINT32("UInt32", 32, false, false),
    UINT64("UInt64", 64, false, false),
    SIZE("Size", 64, false, false),               // pointer width: 32 on the Pico. Never assume 64 in ConstEval range checks.
    FLOAT32("Float32", 32, true, true),
    FLOAT64("Float64", 64, true, true),
    BOOL("Bool", 1, false, false),
    CHAR("Char", 8, false, false);

    val isInteger: Boolean get() = !isFloat && this != BOOL && this != CHAR

    /** R1: arithmetic on this type needs a narrowing cast in C++ (it promotes to int). */
    val promotesInCpp: Boolean get() = isInteger && bits < 32

    /**
     * The width a compile-time range check may assume. `Size` counts as 32 bits: the Pico's
     * pointer width, so a constant that fits is valid on every target.
     */
    val portableBits: Int get() = if (this == SIZE) 32 else bits

    /** Smallest value of an integer prim under [portableBits]; null for Bool, Char and floats. */
    val minValue: BigInteger?
        get() = when {
            !isInteger -> null
            signed -> BigInteger.ONE.shiftLeft(portableBits - 1).negate()
            else -> BigInteger.ZERO
        }

    /** Largest value of an integer prim under [portableBits]; null for Bool, Char and floats. */
    val maxValue: BigInteger?
        get() = when {
            !isInteger -> null
            signed -> BigInteger.ONE.shiftLeft(portableBits - 1).subtract(BigInteger.ONE)
            else -> BigInteger.ONE.shiftLeft(portableBits).subtract(BigInteger.ONE)
        }

    /** True when [value] is representable in this integer prim on every target. */
    fun fits(value: BigInteger): Boolean {
        val lo = minValue ?: return false
        val hi = maxValue ?: return false
        return value >= lo && value <= hi
    }

    companion object {
        private val byName = entries.associateBy { it.kiraName }

        fun byKiraName(name: String): Prim? = byName[name]
    }
}

/**
 * A resolved Kira type. The typed model stores these; the C++ backend spells them
 * (CppTypeSpeller, design table 5.1) and never re-derives a type from the AST.
 *
 * - [Nominal] covers every declared type: user classes, structs, traits and enums, and the
 *   magic containers (List, Map, Arr, Maybe, TupleN, View, ...). Its [Nominal.sym] is a
 *   [TypeSymbol] compared by identity.
 * - `Arr` resolves by arity: `Arr<T>` has one [TypeArg.Ty]; `Arr<T, N>` adds a [TypeArg.Const].
 * - `Fx<TupleN<A, B>, R>` resolves to [Fn], never to a Nominal: its parameters are the tuple's
 *   elements, and a `mut` element (`Tuple1<mut T>`, D24) is a by-reference [FnParam].
 * - An alias is inlined: `alias Frame as Arr<UInt8, 32>` makes `Frame` the Arr type itself
 *   (TypedModel.aliasRefs remembers which Type nodes were spelled through an alias).
 * - [Error] marks a fact the typer could not establish. A diagnostic always accompanies it,
 *   and the C++ emitter refuses any Error it meets.
 */
sealed interface KType {
    data class Scalar(val prim: Prim) : KType {
        override fun toString(): String = display()
    }

    data object Str : KType {
        override fun toString(): String = display()
    }

    data object Void : KType {
        override fun toString(): String = display()
    }

    data object Never : KType {
        override fun toString(): String = display()
    }

    data object NullT : KType {
        override fun toString(): String = display()
    }

    data class Nominal(val sym: TypeSymbol, val args: List<TypeArg> = emptyList()) : KType {   // user and magic types
        override fun toString(): String = display()
    }

    data class Param(val sym: TypeParamSymbol) : KType {
        override fun toString(): String = display()
    }

    data class Fn(val params: List<FnParam>, val ret: KType) : KType {                            // Fx<TupleN<...>, R>
        override fun toString(): String = display()
    }

    data object Error : KType {
        override fun toString(): String = display()
    }

    companion object {
        val INT8 = Scalar(Prim.INT8)
        val INT16 = Scalar(Prim.INT16)
        val INT32 = Scalar(Prim.INT32)
        val INT64 = Scalar(Prim.INT64)
        val UINT8 = Scalar(Prim.UINT8)
        val UINT16 = Scalar(Prim.UINT16)
        val UINT32 = Scalar(Prim.UINT32)
        val UINT64 = Scalar(Prim.UINT64)
        val SIZE = Scalar(Prim.SIZE)
        val FLOAT32 = Scalar(Prim.FLOAT32)
        val FLOAT64 = Scalar(Prim.FLOAT64)
        val BOOL = Scalar(Prim.BOOL)
        val CHAR = Scalar(Prim.CHAR)
    }
}

data class FnParam(val type: KType, val byRef: Boolean) {
    override fun toString(): String = if (byRef) "mut ${type.display()}" else type.display()
}

sealed interface TypeArg {
    data class Ty(val t: KType) : TypeArg {
        override fun toString(): String = t.display()
    }

    data class Const(val n: Long) : TypeArg {
        override fun toString(): String = n.toString()
    }
}

/** The type in Kira's own spelling: `Int32`, `Map<Str, List<Int32>>`, `Arr<UInt8, 32>`, `Fx<Tuple1<mut Str>, Void>`. */
fun KType.display(): String = when (this) {
    is KType.Scalar -> prim.kiraName
    KType.Str -> "Str"
    KType.Void -> "Void"
    KType.Never -> "Never"
    KType.NullT -> "Null"
    KType.Error -> "<error>"
    is KType.Param -> sym.name
    is KType.Nominal -> if (args.isEmpty()) sym.name else "${sym.name}<${args.joinToString(", ")}>"
    is KType.Fn -> "Fx<Tuple${params.size}${if (params.isEmpty()) "" else "<${params.joinToString(", ")}>"}, ${ret.display()}>"
}

/** True when this type is, or contains, [KType.Error]. */
fun KType.containsError(): Boolean = when (this) {
    KType.Error -> true
    is KType.Nominal -> args.any { it is TypeArg.Ty && it.t.containsError() }
    is KType.Fn -> ret.containsError() || params.any { it.type.containsError() }
    else -> false
}

/** The prim of a [KType.Scalar], else null. */
val KType.prim: Prim? get() = (this as? KType.Scalar)?.prim

/** The type arguments of a Nominal that are types (skipping `Arr`'s size), in order. */
fun KType.Nominal.typeArgs(): List<KType> = args.mapNotNull { (it as? TypeArg.Ty)?.t }

/** The constant arguments of a Nominal (`Arr<T, N>`'s N), in order. */
fun KType.Nominal.constArgs(): List<Long> = args.mapNotNull { (it as? TypeArg.Const)?.n }

/**
 * Replaces every [KType.Param] found in [substitution]. A type parameter the map does not
 * mention stays a Param (a method's own type parameters inside a class substitution).
 */
fun KType.substitute(substitution: Map<TypeParamSymbol, KType>): KType {
    if (substitution.isEmpty()) {
        return this
    }
    return when (this) {
        is KType.Param -> substitution[sym] ?: this
        is KType.Nominal -> KType.Nominal(sym, args.map { arg ->
            when (arg) {
                is TypeArg.Ty -> TypeArg.Ty(arg.t.substitute(substitution))
                is TypeArg.Const -> arg
            }
        })
        is KType.Fn -> KType.Fn(params.map { FnParam(it.type.substitute(substitution), it.byRef) }, ret.substitute(substitution))
        else -> this
    }
}
