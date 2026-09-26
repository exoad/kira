package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Literals take the type their context expects (design 3.3), and are range-checked against
 * it:
 *
 * - an integer literal fits any integer prim in range (`0xFFFFFFFF` is a UInt32 and an error
 *   as an Int32), and with no integer context it is an Int32. `Size` is checked against 32
 *   bits, the Pico's width, so a constant that fits is valid on every target;
 * - a float literal is a Float32 or a Float64 from context, else a Float64;
 * - an integer literal where a float is expected is that float (`value > 0` with a Float64
 *   `value`), provided the float holds it exactly; a float literal is never an integer;
 * - `-128` is one literal: the sign is part of the range check, so Int8's minimum is valid.
 *
 * Every literal also gets its value in `TypedModel.consts`.
 */
internal class LiteralTyper(private val c: PhaseC) {
    /** The prim a literal should take from [hint]: its own, or a Maybe's inner type's. */
    private fun target(hint: KType?): Prim? {
        val t = hint?.let { c.facts.maybeInner(it) ?: it } ?: return null
        return t.prim
    }

    /** [e] (negated when [negate]) as an integer literal under [hint]. [at] is where to report and record. */
    fun integer(e: IntegerLiteral, hint: KType?, negate: Boolean = false, at: Expr = e): KType {
        val value = BigInteger.valueOf(e.value).let { if (negate) it.negate() else it }
        val prim = target(hint)?.takeIf { it.isInteger || it.isFloat } ?: Prim.INT32
        if (prim.isFloat) {
            val limit = BigInteger.ONE.shiftLeft(if (prim == Prim.FLOAT32) 24 else 53)
            if (value.abs() > limit) {
                c.report(
                    "types.literal.range",
                    "$value is not exactly a ${prim.kiraName}: a ${prim.kiraName} holds integers exactly only up to $limit; write it as a float literal.",
                    at,
                )
            }
            c.model.consts[at] = ConstValue.FloatConst(round(value.toDouble(), prim), prim)
            return KType.Scalar(prim)
        }
        if (!prim.fits(value)) {
            val why = if (prim == Prim.SIZE) " (Size is checked against 32 bits, the Pico's width)" else ""
            val hintText = if (target(hint) == null) "; with no type from its context an integer literal is an Int32" else ""
            c.report(
                "types.literal.range",
                "$value does not fit ${prim.kiraName}, which holds ${prim.minValue}..${prim.maxValue}$why$hintText.",
                at,
            )
        } else {
            c.model.consts[at] = ConstValue.IntConst.of(value, prim)
        }
        return KType.Scalar(prim)
    }

    fun float(e: FloatLiteral, hint: KType?, negate: Boolean = false, at: Expr = e): KType {
        val v = if (negate) -e.value else e.value
        val wanted = target(hint)
        if (wanted != null && wanted.isInteger) {
            c.report(
                "types.literal.float-to-int",
                "A float literal cannot be a ${wanted.kiraName}; write an integer literal, or convert with `as ${wanted.kiraName}`.",
                at,
            )
            return KType.Error
        }
        val prim = wanted?.takeIf { it.isFloat } ?: Prim.FLOAT64
        if (prim == Prim.FLOAT32 && v.isFinite() && kotlin.math.abs(v) > Float.MAX_VALUE.toDouble()) {
            c.report("types.literal.range", "$v is beyond the largest Float32 (${Float.MAX_VALUE}).", at)
        }
        c.model.consts[at] = ConstValue.FloatConst(round(v, prim), prim)
        return KType.Scalar(prim)
    }

    fun char(e: CharLiteral): KType {
        c.model.consts[e] = ConstValue.CharConst(e.value)
        return KType.CHAR
    }

    fun string(e: StringLiteral): KType {
        c.model.consts[e] = ConstValue.StrConst(e.value)
        return KType.Str
    }

    private fun round(v: Double, prim: Prim): Double = if (prim == Prim.FLOAT32) v.toFloat().toDouble() else v

    /** A literal, or a sign applied to one: the operand side that takes the other side's type. */
    fun isBareLiteral(e: Expr): Boolean = e is IntegerLiteral || e is FloatLiteral ||
        (e is UnaryExpr && (e.operator == UnaryOp.NEG || e.operator == UnaryOp.POS) && isBareLiteral(e.operand))

    /**
     * Built of literals only (`1000000 * 1000000`, `(1 + 2)`): such an operand takes the other
     * operand's type, as a single literal does.
     */
    fun isLiteralOnly(e: Expr): Boolean = when (e) {
        is IntegerLiteral, is FloatLiteral -> true
        is UnaryExpr -> e.operator != UnaryOp.NOT && isLiteralOnly(e.operand)
        is BinaryExpr -> e.operator in ARITHMETIC_OR_BITS && isLiteralOnly(e.leftExpr) && isLiteralOnly(e.rightExpr)
        else -> false
    }

    companion object {
        val ARITHMETIC = setOf(BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD)
        val BITS = setOf(BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR)
        val SHIFTS = setOf(BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR)
        val ARITHMETIC_OR_BITS = ARITHMETIC + BITS + SHIFTS
        val EQUALITY = setOf(BinaryOp.EQUALS, BinaryOp.NOT_EQUAL)
        val ORDERING = setOf(BinaryOp.LESS_THAN, BinaryOp.LESS_THAN_OR_EQUAL, BinaryOp.GREATER_THAN, BinaryOp.GREATER_THAN_OR_EQUAL)
    }
}

/**
 * Constant folding over the facts phase C already recorded: an expression folds when its
 * operands folded, under the types the typer gave them. A value is recorded in
 * `TypedModel.consts`; phase C's value replaces any phase-B value of another type for the same
 * node, so the table never disagrees with `TypedModel.types`.
 *
 * Semantics are ConstEval's: signed overflow is an error and unsigned arithmetic wraps (D8),
 * integer division truncates, float to integer saturates with NaN to 0 (D9), and a shift by a
 * count outside the width is an error.
 */
internal class ConstFolder(private val c: PhaseC) {
    private val model get() = c.model

    /** Folds [e] (already typed) from its operands' constants; records and returns the value, or null. */
    fun fold(e: Expr): ConstValue? {
        val type = model.types[e] ?: return null
        val value = when (e) {
            is IntegerLiteral, is FloatLiteral, is CharLiteral, is StringLiteral -> model.consts[e]
            is Identifier -> identifier(e)
            is MemberAccessExpr -> (model.members[e] as? MemberRef.EnumEntry)?.let { ConstValue.EnumConst(it.entry) }
            is UnaryExpr -> unary(e)
            is BinaryExpr -> binary(e)
            is TypeCastExpr -> cast(e)
            is ArrayLiteral -> {
                // A literal of another length than its Arr<T, N> is not a value of that type.
                val size = (type as? KType.Nominal)?.takeIf { c.facts.isArr(it) }?.constArgs()?.firstOrNull()
                if (size != null && size != e.value.size.toLong()) {
                    model.consts.remove(e)
                    return null
                }
                val values = e.value.map { model.consts[it] ?: return drop(e, type) }
                ConstValue.ArrConst(values, type)
            }
            else -> null
        } ?: return drop(e, type)
        if (value.type != type && !(value is ConstValue.ArrConst)) {
            return drop(e, type)
        }
        model.consts[e] = value
        return value
    }

    /** No value for [e]: removes a stale one of another type (never one that agrees with [type]). */
    private fun drop(e: Expr, type: KType): ConstValue? {
        val existing = model.consts[e] ?: return null
        if (existing.type != type && existing !is ConstValue.ArrConst) {
            model.consts.remove(e)
            return null
        }
        return existing
    }

    private fun identifier(e: Identifier): ConstValue? {
        val g = model.refs[e] as? GlobalSymbol ?: return null
        if (!g.isConstant) {
            return null
        }
        return g.constValue
    }

    private fun unary(e: UnaryExpr): ConstValue? {
        model.consts[e]?.let { if (it.type == model.types[e]) return it }
        val v = model.consts[e.operand] ?: return null
        return when (e.operator) {
            UnaryOp.POS -> v.takeIf { it is ConstValue.IntConst || it is ConstValue.FloatConst }
            UnaryOp.NEG -> when (v) {
                is ConstValue.IntConst -> checked(v.value.negate(), v.prim, e)
                is ConstValue.FloatConst -> ConstValue.FloatConst(-v.value, v.prim)
                else -> null
            }
            UnaryOp.NOT -> (v as? ConstValue.BoolConst)?.let { ConstValue.BoolConst(!it.value) }
            UnaryOp.BIT_NOT -> (v as? ConstValue.IntConst)?.let { wrap(it.value.not(), it.prim) }
        }
    }

    private fun binary(e: BinaryExpr): ConstValue? {
        val l = model.consts[e.leftExpr] ?: return null
        val r = model.consts[e.rightExpr] ?: return null
        val op = e.operator
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            val a = l as? ConstValue.BoolConst ?: return null
            val b = r as? ConstValue.BoolConst ?: return null
            return ConstValue.BoolConst(if (op == BinaryOp.AND) a.value && b.value else a.value || b.value)
        }
        if (op in LiteralTyper.SHIFTS) {
            val a = l as? ConstValue.IntConst ?: return null
            val n = (r as? ConstValue.IntConst)?.value ?: return null
            if (n.signum() < 0 || n >= BigInteger.valueOf(a.prim.bits.toLong())) {
                c.report("types.const.overflow", "A shift by $n is outside 0..${a.prim.bits - 1} for ${a.prim.kiraName}.", e)
                return null
            }
            val k = n.toInt()
            return when (op) {
                BinaryOp.SHL -> if (a.prim.signed) checked(a.value.shiftLeft(k), a.prim, e) else wrap(a.value.shiftLeft(k), a.prim)
                BinaryOp.SHR -> ConstValue.IntConst.of(a.value.shiftRight(k), a.prim)
                else -> {
                    val unsigned = if (a.value.signum() < 0) a.value.add(BigInteger.ONE.shiftLeft(a.prim.bits)) else a.value
                    wrap(unsigned.shiftRight(k), a.prim)
                }
            }
        }
        if (l.type != r.type) {
            return null
        }
        if (op in LiteralTyper.EQUALITY || op in LiteralTyper.ORDERING) {
            return compare(l, r, op)
        }
        return when {
            l is ConstValue.StrConst && r is ConstValue.StrConst -> if (op == BinaryOp.ADD) ConstValue.StrConst(l.value + r.value) else null
            l is ConstValue.IntConst && r is ConstValue.IntConst -> when (op) {
                in LiteralTyper.ARITHMETIC -> {
                    if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && r.value.signum() == 0) {
                        c.report("types.const.div-by-zero", "This constant expression divides by zero.", e)
                        return null
                    }
                    val exact = when (op) {
                        BinaryOp.ADD -> l.value.add(r.value)
                        BinaryOp.SUB -> l.value.subtract(r.value)
                        BinaryOp.MUL -> l.value.multiply(r.value)
                        BinaryOp.DIV -> l.value.divide(r.value)
                        else -> l.value.rem(r.value)
                    }
                    checked(exact, l.prim, e)
                }
                BinaryOp.CONJUNCTIVE_AND -> wrap(l.value.and(r.value), l.prim)
                BinaryOp.CONJUNCTIVE_OR -> wrap(l.value.or(r.value), l.prim)
                BinaryOp.XOR -> wrap(l.value.xor(r.value), l.prim)
                else -> null
            }
            l is ConstValue.FloatConst && r is ConstValue.FloatConst -> {
                val v = when (op) {
                    BinaryOp.ADD -> l.value + r.value
                    BinaryOp.SUB -> l.value - r.value
                    BinaryOp.MUL -> l.value * r.value
                    BinaryOp.DIV -> l.value / r.value
                    else -> return null
                }
                ConstValue.FloatConst(if (l.prim == Prim.FLOAT32) v.toFloat().toDouble() else v, l.prim)
            }
            else -> null
        }
    }

    private fun compare(l: ConstValue, r: ConstValue, op: BinaryOp): ConstValue? {
        val cmp: Int = when {
            l is ConstValue.IntConst && r is ConstValue.IntConst -> l.value.compareTo(r.value)
            l is ConstValue.FloatConst && r is ConstValue.FloatConst -> {
                if (l.value.isNaN() || r.value.isNaN()) {
                    return ConstValue.BoolConst(op == BinaryOp.NOT_EQUAL)
                }
                l.value.compareTo(r.value)
            }
            l is ConstValue.CharConst && r is ConstValue.CharConst -> l.value.compareTo(r.value)
            op !in LiteralTyper.EQUALITY -> return null
            l is ConstValue.BoolConst && r is ConstValue.BoolConst -> if (l.value == r.value) 0 else 1
            l is ConstValue.StrConst && r is ConstValue.StrConst -> if (l.value == r.value) 0 else 1
            l is ConstValue.EnumConst && r is ConstValue.EnumConst -> if (l.entry === r.entry) 0 else 1
            else -> return null
        }
        return ConstValue.BoolConst(
            when (op) {
                BinaryOp.EQUALS -> cmp == 0
                BinaryOp.NOT_EQUAL -> cmp != 0
                BinaryOp.LESS_THAN -> cmp < 0
                BinaryOp.LESS_THAN_OR_EQUAL -> cmp <= 0
                BinaryOp.GREATER_THAN -> cmp > 0
                else -> cmp >= 0
            }
        )
    }

    private fun cast(e: TypeCastExpr): ConstValue? {
        val v = model.consts[e.value] ?: return null
        val kind = model.conversions[e] ?: return null
        val target = model.types[e] ?: return null
        val prim = target.prim
        return when (kind) {
            ConversionKind.INT_WRAP -> (v as? ConstValue.IntConst)?.let { i -> prim?.let { wrap(i.value, it) } }
            ConversionKind.INT_TO_FLOAT -> (v as? ConstValue.IntConst)?.let { i ->
                prim?.let { ConstValue.FloatConst(if (it == Prim.FLOAT32) i.value.toFloat().toDouble() else i.value.toDouble(), it) }
            }
            ConversionKind.FLOAT_TO_INT_SAT -> (v as? ConstValue.FloatConst)?.let { f -> prim?.let { saturate(f.value, it) } }
            ConversionKind.FLOAT_RESIZE -> (v as? ConstValue.FloatConst)?.let { f ->
                prim?.let { ConstValue.FloatConst(if (it == Prim.FLOAT32) f.value.toFloat().toDouble() else f.value, it) }
            }
            ConversionKind.CHAR_TO_INT -> (v as? ConstValue.CharConst)?.let { ch -> prim?.let { wrap(BigInteger.valueOf(ch.value.toLong()), it) } }
            ConversionKind.INT_TO_CHAR -> (v as? ConstValue.IntConst)?.let { ConstValue.CharConst(it.value.and(BigInteger.valueOf(0xFF)).toInt()) }
            ConversionKind.ENUM_TO_BASE -> (v as? ConstValue.EnumConst)?.entry?.value
            ConversionKind.TO_STR -> null
        }
    }

    /** Signed overflow is an error (D8); unsigned wraps; Size is checked against 32 bits. */
    private fun checked(exact: BigInteger, prim: Prim, e: Expr): ConstValue? {
        if (prim.fits(exact)) {
            return ConstValue.IntConst.of(exact, prim)
        }
        if (!prim.signed && prim != Prim.SIZE) {
            return wrap(exact, prim)
        }
        c.report(
            "types.const.overflow",
            "This constant expression overflows ${prim.kiraName} (the exact value is $exact)" +
                if (prim == Prim.SIZE) "; Size is checked against 32 bits, the Pico's width." else ".",
            e,
        )
        return null
    }

    private fun wrap(v: BigInteger, prim: Prim): ConstValue? {
        if (prim == Prim.SIZE) {
            return if (prim.fits(v)) ConstValue.IntConst.of(v, prim) else null
        }
        val bits = prim.bits
        var w = v.and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE))
        if (prim.signed && w.testBit(bits - 1)) {
            w = w.subtract(BigInteger.ONE.shiftLeft(bits))
        }
        return ConstValue.IntConst.of(w, prim)
    }

    private fun saturate(v: Double, prim: Prim): ConstValue? {
        if (v.isNaN()) {
            return ConstValue.IntConst.of(BigInteger.ZERO, prim)
        }
        val lo = prim.minValue ?: return null
        val hi = prim.maxValue ?: return null
        if (v.isInfinite()) {
            return ConstValue.IntConst.of(if (v > 0) hi else lo, prim)
        }
        return ConstValue.IntConst.of(BigDecimal(v).toBigInteger().max(lo).min(hi), prim)
    }
}
