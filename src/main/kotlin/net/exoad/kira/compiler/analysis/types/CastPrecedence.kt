package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr

/**
 * `as` binds tighter than every binary operator (Kotlin's order). A cast outside parentheses in the right operand
 * of a shift or bitwise operator read differently before, so it is warned about; a mismatch it causes says so.
 */
internal class CastPrecedence(private val program: TypedProgram) {
    private val parenthesized get() = program.parenthesized

    /** The first cast in [e]'s operator tree that no parentheses enclose. */
    fun bareCast(e: Expr): TypeCastExpr? = when {
        e in parenthesized -> null
        e is TypeCastExpr -> e
        e is BinaryExpr -> bareCast(e.leftExpr) ?: bareCast(e.rightExpr)
        else -> null
    }

    /** For a mismatch at [e]: a cast in its right operand that converts less than it may seem to, and the parentheses for more. */
    fun hint(e: Expr): String? {
        val b = e as? BinaryExpr ?: return null
        val right = b.rightExpr
        if (right is TypeCastExpr && right !in parenthesized) {
            val whole = KiraUnparser.text(BinaryExpr(b.leftExpr, right.value, b.operator))
            return "`as` binds to its nearest operand, so ${KiraUnparser.text(right)} converts " +
                "${KiraUnparser.operand(right.value, BinaryOp.TYPE_CAST.precedence)} alone; " +
                "to convert the whole, write ($whole) as ${KiraUnparser.type(right.type)}"
        }
        val lead = leadingCast(right) ?: return null
        val rightText = KiraUnparser.text(right)
        val leadText = KiraUnparser.text(lead)
        if (!rightText.startsWith(leadText)) {
            return null
        }
        val sym = b.operator.symbol.joinToString("") { it.rep.toString() }
        val first = KiraUnparser.text(BinaryExpr(b.leftExpr, lead, b.operator))
        return "`as` binds to its nearest operand, so the right side of $sym is $rightText; " +
            "to apply $sym first, write ($first)${rightText.removePrefix(leadText)}"
    }

    private fun leadingCast(e: Expr): TypeCastExpr? = when {
        e in parenthesized -> null
        e is TypeCastExpr -> e
        e is BinaryExpr -> leadingCast(e.leftExpr)
        else -> null
    }

    fun warn() {
        val found = program.model.types.keys
            .filterIsInstance<BinaryExpr>()
            .filter { it.operator in WARNED }
            .mapNotNull { b -> bareCast(b.rightExpr)?.let { b to it } }
        found.map { (b, cast) -> Triple(b, cast, program.locate(cast)) }
            .sortedWith(compareBy({ it.third?.first?.file ?: "" }, { it.third?.second?.lineNumber ?: -1 }, { it.third?.second?.column ?: -1 }))
            .forEach { (b, cast) -> program.report("types.cast.precedence", message(b, cast), cast, Severity.WARNING) }
    }

    private fun message(b: BinaryExpr, cast: TypeCastExpr): String {
        val op = b.operator
        val sym = op.symbol.joinToString("") { it.rep.toString() }
        val castText = KiraUnparser.text(cast)
        val right = if (b.rightExpr === cast) "($castText)" else "(${KiraUnparser.text(b.rightExpr).replaceFirst(castText, "($castText)")})"
        val reading = "${KiraUnparser.operand(b.leftExpr, op.precedence)} $sym $right"
        val head = "`as` binds to its nearest operand: ${KiraUnparser.text(b)} reads as $reading"
        if (b.rightExpr !== cast) {
            return "$head; parenthesize it to say which you mean."
        }
        val whole = KiraUnparser.text(BinaryExpr(b.leftExpr, cast.value, op))
        return "$head; write that, or ($whole) as ${KiraUnparser.type(cast.type)}, to say which you mean."
    }

    companion object {
        private val WARNED = setOf(
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR, BinaryOp.CONJUNCTIVE_AND, BinaryOp.CONJUNCTIVE_OR, BinaryOp.XOR,
        )
    }
}
