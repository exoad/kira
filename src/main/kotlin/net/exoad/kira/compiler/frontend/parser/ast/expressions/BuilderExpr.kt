package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/** What a [BuilderExpr] builds; [INFER] is stage 1's `[...]` literal, which only kira-dialect reads. */
enum class Builder(val intrinsic: String) {
    LIST("listOf"),
    ARR("arrOf"),
    SET("setOf"),
    MAP("mapOf"),
    INFER("[...]");

    companion object {
        fun byIntrinsic(name: String): Builder? = entries.firstOrNull { it != INFER && it.intrinsic == name }
    }
}

/**
 * `@listOf(a, b)`, `@arrOf(a, b)`, `@setOf(a, b)`, `@mapOf(k1, v1, k2, v2)`, each with optional
 * type arguments (`@listOf<Int32>()`): a List, Arr, Set or Map of its [elements], a Map's keys and
 * values in turn.
 */
open class BuilderExpr(val kind: Builder, val typeArgs: List<Type>, val elements: List<Expr>) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitBuilderExpr(this)
    }

    override fun toString(): String {
        return "Builder{ ${kind.intrinsic}${if (typeArgs.isEmpty()) "" else typeArgs.toString()} -> $elements }"
    }
}
