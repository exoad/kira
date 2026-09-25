package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * Phase B constant folding: module-level initializers ([GlobalSymbol.constValue]), parameter
 * and field defaults, and the enum values. Each expression that folds gets a
 * `TypedModel.consts` entry.
 *
 * It folds integer, float, bool, char and string literals; `null`; references to other
 * constants; unary `- + ! ~`; arithmetic, bitwise, shift, comparison and logical operators on
 * constants of one type; `+` of two Str constants; `E.ENTRY`; numeric `as` conversions; and
 * `Arr` literals of constants. A literal takes the type the context expects (the
 * declaration's type, or the other operand's), else Int32 or Float64, as in phase C.
 *
 * What it reports, because nothing later would: signed overflow and division by zero while
 * folding (`types.const.overflow`, `types.const.div-by-zero`). What it leaves to phase C (W2.1):
 * a literal out of range for its declared type, a non-constant initializer, a Str constant
 * that is not a literal. Those simply do not fold here ([valueOf] is null).
 *
 * `Size` folds under the portable 32-bit range (Prim.portableBits): a Size constant that
 * needs 64 bits does not fold.
 */
class ConstEval(private val program: TypedProgram, private val resolver: TypeResolver) {
    private val model = program.model
    private val done = IdentityHashMap<GlobalSymbol, Boolean>()
    private val inProgress = IdentityHashMap<GlobalSymbol, Boolean>()

    /** The folded value of [global] (computing it on first use), or null when it does not fold. */
    fun valueOf(global: GlobalSymbol): ConstValue? {
        if (done.containsKey(global)) {
            return global.constValue
        }
        if (inProgress.containsKey(global)) {
            program.report(
                "types.const.cycle",
                "The initializer of '${global.name}' depends on itself.",
                global.decl,
            )
            return null
        }
        if (global.isMut || global.init == null) {
            done[global] = true
            return magicValue(global)?.also { global.constValue = it }
        }
        inProgress[global] = true
        try {
            val declared = globalType(global)
            val value = magicValue(global) ?: fold(global.init, declared, global.module)
            global.constValue = value?.takeIf { fitsDeclared(it, declared) }
            return global.constValue
        } finally {
            inProgress.remove(global)
            done[global] = true
        }
    }

    /** The declared type of [global], resolving it when phase B has not reached it yet. */
    fun globalType(global: GlobalSymbol): KType {
        if (global.type != KType.Error) {
            return global.type
        }
        val decl = global.decl as? VariableDecl ?: return KType.Error
        global.type = resolver.resolve(decl.type, TypeScope(global.module))
        return global.type
    }

    /** kira:core's `true`, `false` and `null` are magic globals whose initializers are placeholders. */
    private fun magicValue(global: GlobalSymbol): ConstValue? {
        if (global.foreign !is Foreign.Magic || !global.module.isStdlib) {
            return null
        }
        return when (global.name) {
            "true" -> ConstValue.BoolConst(true)
            "false" -> ConstValue.BoolConst(false)
            "null" -> ConstValue.NullConst
            else -> null
        }
    }

    private fun fitsDeclared(value: ConstValue, declared: KType): Boolean = declared != KType.Error && compatible(value, declared)

    /** Folds [e] against [expected] (null: no context) from inside [module]; null when it is not constant. */
    fun fold(e: Expr, expected: KType?, module: ModuleSymbol): ConstValue? {
        val value = try {
            foldUncached(e, expected, module)
        } catch (_: StackOverflowError) {
            null
        }
        if (value != null) {
            model.consts[e] = value
        }
        return value
    }

    private fun foldUncached(e: Expr, expected: KType?, module: ModuleSymbol): ConstValue? = when (e) {
        is IntegerLiteral -> intLiteral(e.value, expected)
        is FloatLiteral -> floatLiteral(e.value, expected)
        is StringLiteral -> ConstValue.StrConst(e.value)
        is CharLiteral -> ConstValue.CharConst(e.value)
        is NullLiteral -> ConstValue.NullConst
        is IntrinsicExpr -> null
        is Identifier -> identifier(e, expected, module)
        is UnaryExpr -> unary(e, expected, module)
        is BinaryExpr -> binary(e, expected, module)
        is MemberAccessExpr -> enumEntry(e, module)
        is TypeCastExpr -> cast(e, module)
        is ArrayLiteral -> array(e, expected, module)
        else -> null
    }

    /** The prim a literal takes: the expected type's (a Maybe's inner type), else [default]; null when it cannot be one. */
    private fun literalTarget(expected: KType?, default: Prim): Prim? {
        if (expected == null) {
            return default
        }
        val t = if (isMaybeOf(expected)) maybeInner(expected) ?: return null else expected
        return t.prim
    }

    private fun intLiteral(v: Long, expected: KType?): ConstValue? {
        val target = literalTarget(expected, Prim.INT32)?.takeIf { it.isInteger } ?: return null
        val big = BigInteger.valueOf(v)
        return if (target.fits(big)) ConstValue.IntConst.of(big, target) else null
    }

    private fun floatLiteral(v: Double, expected: KType?): ConstValue? {
        val target = literalTarget(expected, Prim.FLOAT64)?.takeIf { it.isFloat } ?: return null
        return floatOf(v, target)
    }

    private fun floatOf(v: Double, prim: Prim): ConstValue.FloatConst =
        ConstValue.FloatConst(if (prim == Prim.FLOAT32) v.toFloat().toDouble() else v, prim)

    private fun isMaybeOf(t: KType): Boolean = t is KType.Nominal && t.sym.name == "Maybe" && (t.sym as? ClassSymbol)?.kind == ClassKind.MAGIC

    private fun maybeInner(t: KType): KType? = (t as? KType.Nominal)?.typeArgs()?.firstOrNull()

    private fun identifier(e: Identifier, expected: KType?, module: ModuleSymbol): ConstValue? {
        val found = program.graph.lookup(module, e.value) { it is GlobalSymbol }
        val global = when (found) {
            is ModuleGraph.Lookup.Found -> found.symbol as GlobalSymbol
            is ModuleGraph.Lookup.NotVisible -> found.symbol as GlobalSymbol
            is ModuleGraph.Lookup.Ambiguous -> found.first.symbol as GlobalSymbol
            ModuleGraph.Lookup.Missing -> return null
        }
        if (!global.isConstant) {
            return null
        }
        val value = valueOf(global) ?: return null
        return value.takeIf { expected == null || compatible(it, expected) }
    }

    /** [value] can stand where [expected] is wanted: the same type, or a Maybe of it. */
    private fun compatible(value: ConstValue, expected: KType): Boolean = when {
        value.type == expected -> true
        isMaybeOf(expected) -> value is ConstValue.NullConst || value.type == maybeInner(expected)
        else -> false
    }

    private fun unary(e: UnaryExpr, expected: KType?, module: ModuleSymbol): ConstValue? {
        // `-128` is one literal: fold the sign in before the range check, so Int8's minimum folds.
        val operand = e.operand
        if (e.operator == UnaryOp.NEG && operand is IntegerLiteral) {
            return intLiteral(-operand.value, expected)
        }
        if (e.operator == UnaryOp.NEG && operand is FloatLiteral) {
            return floatLiteral(-operand.value, expected)
        }
        val v = fold(operand, expected, module) ?: return null
        return when (e.operator) {
            UnaryOp.POS -> v.takeIf { it is ConstValue.IntConst || it is ConstValue.FloatConst }
            UnaryOp.NEG -> when (v) {
                is ConstValue.IntConst -> checkedInt(v.value.negate(), v.prim, e)
                is ConstValue.FloatConst -> floatOf(-v.value, v.prim)
                else -> null
            }
            UnaryOp.NOT -> (v as? ConstValue.BoolConst)?.let { ConstValue.BoolConst(!it.value) }
            UnaryOp.BIT_NOT -> (v as? ConstValue.IntConst)?.let { wrap(v.value.not(), v.prim) }
        }
    }

    private val arithmetic = setOf(BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD)
    private val bitwise = setOf(BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR)
    private val shifts = setOf(BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR)
    private val comparisons = setOf(
        BinaryOp.EQUALS, BinaryOp.NOT_EQUAL, BinaryOp.LESS_THAN, BinaryOp.LESS_THAN_OR_EQUAL,
        BinaryOp.GREATER_THAN, BinaryOp.GREATER_THAN_OR_EQUAL,
    )

    private fun isBareLiteral(e: Expr): Boolean = e is IntegerLiteral || e is FloatLiteral ||
        (e is UnaryExpr && (e.operand is IntegerLiteral || e.operand is FloatLiteral))

    private fun binary(e: BinaryExpr, expected: KType?, module: ModuleSymbol): ConstValue? {
        val op = e.operator
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            val l = fold(e.leftExpr, KType.BOOL, module) as? ConstValue.BoolConst ?: return null
            val r = fold(e.rightExpr, KType.BOOL, module) as? ConstValue.BoolConst ?: return null
            return ConstValue.BoolConst(if (op == BinaryOp.AND) l.value && r.value else l.value || r.value)
        }
        if (op in shifts) {
            val l = fold(e.leftExpr, expected, module) as? ConstValue.IntConst ?: return null
            val r = fold(e.rightExpr, null, module) as? ConstValue.IntConst ?: return null
            return shift(l, r, op, e)
        }
        // Operand typing: a literal side takes the other side's type (design 3.3).
        val operandExpected = if (op in comparisons) null else expected
        val (l, r) = if (isBareLiteral(e.leftExpr) && !isBareLiteral(e.rightExpr)) {
            val r0 = fold(e.rightExpr, operandExpected, module) ?: return null
            (fold(e.leftExpr, r0.type, module) ?: return null) to r0
        } else {
            val l0 = fold(e.leftExpr, operandExpected, module) ?: return null
            l0 to (fold(e.rightExpr, l0.type, module) ?: return null)
        }
        if (l.type != r.type) {
            return null
        }
        return when {
            op in comparisons -> compare(l, r, op)
            l is ConstValue.StrConst && r is ConstValue.StrConst -> if (op == BinaryOp.ADD) ConstValue.StrConst(l.value + r.value) else null
            l is ConstValue.IntConst && r is ConstValue.IntConst -> when (op) {
                in arithmetic -> intArithmetic(l, r, op, e)
                in bitwise -> wrap(
                    when (op) {
                        BinaryOp.CONJUNCTIVE_AND -> l.value.and(r.value)
                        BinaryOp.CONJUNCTIVE_OR -> l.value.or(r.value)
                        else -> l.value.xor(r.value)
                    }, l.prim
                )
                else -> null
            }
            l is ConstValue.FloatConst && r is ConstValue.FloatConst -> when (op) {
                BinaryOp.ADD -> floatOf(l.value + r.value, l.prim)
                BinaryOp.SUB -> floatOf(l.value - r.value, l.prim)
                BinaryOp.MUL -> floatOf(l.value * r.value, l.prim)
                BinaryOp.DIV -> floatOf(l.value / r.value, l.prim)
                else -> null
            }
            else -> null
        }
    }

    private fun intArithmetic(l: ConstValue.IntConst, r: ConstValue.IntConst, op: BinaryOp, e: Expr): ConstValue? {
        val a = l.value
        val b = r.value
        if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && b.signum() == 0) {
            program.report("types.const.div-by-zero", "This constant expression divides by zero.", e)
            return null
        }
        val exact = when (op) {
            BinaryOp.ADD -> a.add(b)
            BinaryOp.SUB -> a.subtract(b)
            BinaryOp.MUL -> a.multiply(b)
            // Kira's integer `/` and `%` truncate toward zero, like C++.
            BinaryOp.DIV -> a.divide(b)
            else -> a.rem(b)
        }
        return checkedInt(exact, l.prim, e)
    }

    /** Signed overflow is an error (D8); unsigned wraps. */
    private fun checkedInt(exact: BigInteger, prim: Prim, e: Expr): ConstValue? {
        if (prim.fits(exact)) {
            return ConstValue.IntConst.of(exact, prim)
        }
        if (!prim.signed && prim != Prim.SIZE) {
            return wrap(exact, prim)
        }
        program.report(
            "types.const.overflow",
            "This constant expression overflows ${prim.kiraName} (the exact value is $exact)" +
                if (prim == Prim.SIZE) "; Size is checked against 32 bits, the Pico's width." else ".",
            e,
        )
        return null
    }

    /** Two's-complement wrap into [prim]'s width. */
    private fun wrap(v: BigInteger, prim: Prim): ConstValue? {
        if (prim == Prim.SIZE) {
            return if (prim.fits(v)) ConstValue.IntConst.of(v, prim) else null
        }
        val bits = prim.bits
        val mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        var w = v.and(mask)
        if (prim.signed && w.testBit(bits - 1)) {
            w = w.subtract(BigInteger.ONE.shiftLeft(bits))
        }
        return ConstValue.IntConst.of(w, prim)
    }

    private fun shift(l: ConstValue.IntConst, r: ConstValue.IntConst, op: BinaryOp, e: Expr): ConstValue? {
        val n = r.value
        if (n.signum() < 0 || n >= BigInteger.valueOf(l.prim.bits.toLong())) {
            program.report(
                "types.const.overflow",
                "A shift by $n is outside 0..${l.prim.bits - 1} for ${l.prim.kiraName}.",
                e,
            )
            return null
        }
        val k = n.toInt()
        return when (op) {
            BinaryOp.SHL -> if (l.prim.signed) checkedInt(l.value.shiftLeft(k), l.prim, e) else wrap(l.value.shiftLeft(k), l.prim)
            BinaryOp.SHR -> ConstValue.IntConst.of(l.value.shiftRight(k), l.prim)
            else -> {
                val unsigned = if (l.value.signum() < 0) l.value.add(BigInteger.ONE.shiftLeft(l.prim.bits)) else l.value
                wrap(unsigned.shiftRight(k), l.prim)
            }
        }
    }

    private fun compare(l: ConstValue, r: ConstValue, op: BinaryOp): ConstValue? {
        val c: Int = when {
            l is ConstValue.IntConst && r is ConstValue.IntConst -> l.value.compareTo(r.value)
            l is ConstValue.FloatConst && r is ConstValue.FloatConst -> {
                if (l.value.isNaN() || r.value.isNaN()) {
                    return ConstValue.BoolConst(op == BinaryOp.NOT_EQUAL)
                }
                l.value.compareTo(r.value)
            }
            l is ConstValue.CharConst && r is ConstValue.CharConst -> l.value.compareTo(r.value)
            l is ConstValue.BoolConst && r is ConstValue.BoolConst && (op == BinaryOp.EQUALS || op == BinaryOp.NOT_EQUAL) ->
                if (l.value == r.value) 0 else 1
            l is ConstValue.StrConst && r is ConstValue.StrConst && (op == BinaryOp.EQUALS || op == BinaryOp.NOT_EQUAL) ->
                if (l.value == r.value) 0 else 1
            l is ConstValue.EnumConst && r is ConstValue.EnumConst && (op == BinaryOp.EQUALS || op == BinaryOp.NOT_EQUAL) ->
                if (l.entry === r.entry) 0 else 1
            else -> return null
        }
        return ConstValue.BoolConst(
            when (op) {
                BinaryOp.EQUALS -> c == 0
                BinaryOp.NOT_EQUAL -> c != 0
                BinaryOp.LESS_THAN -> c < 0
                BinaryOp.LESS_THAN_OR_EQUAL -> c <= 0
                BinaryOp.GREATER_THAN -> c > 0
                else -> c >= 0
            }
        )
    }

    private fun enumEntry(e: MemberAccessExpr, module: ModuleSymbol): ConstValue? {
        val owner = e.origin as? Identifier ?: return null
        val member = e.member as? Identifier ?: return null
        val found = program.graph.lookup(module, owner.value) { it is EnumSymbol }
        val enum = when (found) {
            is ModuleGraph.Lookup.Found -> found.symbol as EnumSymbol
            is ModuleGraph.Lookup.NotVisible -> found.symbol as EnumSymbol
            is ModuleGraph.Lookup.Ambiguous -> found.first.symbol as EnumSymbol
            ModuleGraph.Lookup.Missing -> return null
        }
        val entry = enum.entry(member.value) ?: return null
        return ConstValue.EnumConst(entry)
    }

    /** `x as T` between numeric prims, per the conversion table (R13, D9). */
    private fun cast(e: TypeCastExpr, module: ModuleSymbol): ConstValue? {
        val target = resolver.resolve(e.type, TypeScope(module)).prim ?: return null
        val v = fold(e.value, null, module) ?: return null
        return when (v) {
            is ConstValue.IntConst -> when {
                target.isInteger -> wrap(v.value, target)
                target.isFloat -> floatOf(v.value.toDouble(), target)
                target == Prim.CHAR -> ConstValue.CharConst(v.value.and(BigInteger.valueOf(0xFF)).toInt())
                else -> null
            }
            is ConstValue.FloatConst -> when {
                target.isFloat -> floatOf(v.value, target)
                target.isInteger -> saturate(v.value, target)
                else -> null
            }
            is ConstValue.CharConst -> if (target.isInteger) wrap(BigInteger.valueOf(v.value.toLong()), target) else null
            is ConstValue.EnumConst -> {
                val base = v.entry.value
                if (base is ConstValue.IntConst && target.isInteger) wrap(base.value, target) else null
            }
            else -> null
        }
    }

    /** D9: float to integer saturates, NaN to 0. */
    private fun saturate(v: Double, prim: Prim): ConstValue? {
        if (v.isNaN()) {
            return ConstValue.IntConst.of(BigInteger.ZERO, prim)
        }
        val lo = prim.minValue ?: return null
        val hi = prim.maxValue ?: return null
        if (v.isInfinite()) {
            return ConstValue.IntConst.of(if (v > 0) hi else lo, prim)
        }
        val truncated = java.math.BigDecimal(v).toBigInteger()
        val clamped = truncated.max(lo).min(hi)
        return ConstValue.IntConst.of(clamped, prim)
    }

    private fun array(e: ArrayLiteral, expected: KType?, module: ModuleSymbol): ConstValue? {
        val arr = expected as? KType.Nominal ?: return null
        if (arr.sym.name != Builtins.ARR || (arr.sym as? ClassSymbol)?.kind != ClassKind.MAGIC) {
            return null
        }
        val element = arr.typeArgs().firstOrNull() ?: return null
        val values = e.value.map { fold(it, element, module) ?: return null }
        return ConstValue.ArrConst(values, arr)
    }
}
