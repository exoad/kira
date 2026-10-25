package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/** One piece of an [InterpolatedStringLiteral]: literal text, or a `${...}` hole. */
sealed class InterpolationPart {
    /** Decoded literal text (escapes already resolved). */
    class Text(val text: String) : InterpolationPart() {
        override fun toString(): String = "Text(\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\")"
    }

    /** The expression inside `${ }`. */
    class Hole(val expr: Expr) : InterpolationPart() {
        override fun toString(): String = "Hole($expr)"
    }
}

/**
 * A string literal with at least one `${expr}` hole. A string without `${`
 * stays a plain [StringLiteral]. Empty text runs are not recorded.
 */
open class InterpolatedStringLiteral(val parts: List<InterpolationPart>) : Literal() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitInterpolatedStringLiteral(this)
    }

    override fun toString(): String {
        return "LInterp{ ${parts.joinToString(", ")} }"
    }
}
