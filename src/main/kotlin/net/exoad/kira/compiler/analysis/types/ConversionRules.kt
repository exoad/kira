package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr

/**
 * `x as T`, by the conversion table of design 5.3, R13. Each cast gets a [ConversionKind] in
 * `TypedModel.conversions`; anything the table does not list is an error.
 *
 * | from | to | kind |
 * |---|---|---|
 * | an integer | an integer (Size included) | INT_WRAP (wraps) |
 * | an integer | a float | INT_TO_FLOAT |
 * | a float | an integer | FLOAT_TO_INT_SAT (saturates, NaN to 0: D9) |
 * | a float | a float | FLOAT_RESIZE |
 * | Char | an integer | CHAR_TO_INT (the code unit, unsigned) |
 * | an integer | Char | INT_TO_CHAR |
 * | anything showable | Str | TO_STR (`kira::text`) |
 *
 * The operand is typed on its own (a bare literal is an Int32 or a Float64), since the
 * conversion names its target explicitly.
 */
internal class ConversionRules(private val c: PhaseC) {
    fun cast(e: TypeCastExpr, ctx: BodyContext, scope: Scope): KType {
        val from = c.exprs.synth(e.value, ctx, scope)
        val to = c.typeOf(e.type)
        if (from.containsError() || to.containsError()) {
            return to
        }
        val kind = kindOf(from, to)
        if (kind == null) {
            c.report(
                "types.cast.invalid",
                "There is no conversion from ${from.display()} to ${to.display()}. " + hint(from, to),
                e,
            )
            return to
        }
        c.model.conversions[e] = kind
        return to
    }

    /** The conversion `from as to` performs, or null when the table has none. */
    fun kindOf(from: KType, to: KType): ConversionKind? {
        val f = from.prim
        val t = to.prim
        return when {
            to == KType.Str && c.facts.isShowable(from) -> ConversionKind.TO_STR
            f != null && t != null && f.isInteger && t.isInteger -> ConversionKind.INT_WRAP
            f != null && t != null && f.isInteger && t.isFloat -> ConversionKind.INT_TO_FLOAT
            f != null && t != null && f.isFloat && t.isInteger -> ConversionKind.FLOAT_TO_INT_SAT
            f != null && t != null && f.isFloat && t.isFloat -> ConversionKind.FLOAT_RESIZE
            f == Prim.CHAR && t != null && t.isInteger -> ConversionKind.CHAR_TO_INT
            f != null && f.isInteger && t == Prim.CHAR -> ConversionKind.INT_TO_CHAR
            else -> null
        }
    }

    private fun hint(from: KType, to: KType): String {
        return when {
            from == KType.BOOL || to == KType.BOOL -> "Bool converts to Str only; compare it or use an if-expression."
            to == KType.Str -> "Only scalars, Char, Bool and Str have a text form."
            else -> "`as` converts between numbers, Char and integers, and a showable value to Str (R13)."
        }
    }
}
