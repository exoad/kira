package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/** What an `a[i]` indexes into; it decides the checked accessor and the overlap rule. */
enum class IndexKind { ARR, LIST, VIEW, MUT_VIEW, MAP, STR, OTHER }

/**
 * An assignable location (TypedModel.places): the target of an assignment, a `mut` argument,
 * the receiver of a `mut fx`. Exclusivity (D37) compares places by [root] and [path]:
 * two places overlap when they share a root and one path is a prefix of the other.
 *
 * [This] is the implicit receiver inside a method, so `count = count + 1` in a `mut fx` is
 * `Field(This(owner), count)`.
 */
sealed interface Place {
    data class Local(val sym: LocalSymbol) : Place
    data class Param(val sym: ParamSymbol) : Place

    /**
     * A field reached through [receiver]. A null receiver means the object is not itself a
     * place (a call result, a fresh construction), so the field cannot alias anything else.
     */
    data class Field(val receiver: Place?, val sym: FieldSymbol) : Place
    data class Global(val sym: GlobalSymbol) : Place

    /** An element of [container]. [index] is the index expression, when there is one. */
    data class Index(val container: Place, val kind: IndexKind, val index: Expr? = null) : Place {
        override fun equals(other: Any?): Boolean =
            other is Index && other.container == container && other.kind == kind && other.index === index

        override fun hashCode(): Int = 31 * container.hashCode() + kind.hashCode()
    }

    /** The receiver of the enclosing method. */
    data class This(val owner: TypeSymbol) : Place

    /** The variable the place starts from: a Local, Param, Global or This (or a receiver-less Field). */
    fun root(): Place = when (this) {
        is Field -> receiver?.root() ?: this
        is Index -> container.root()
        else -> this
    }

    /** The field and index steps from [root] to this place, outermost first. */
    fun path(): List<PathStep> = when (this) {
        is Field -> (receiver?.path() ?: emptyList()) + PathStep.FieldStep(sym)
        is Index -> container.path() + PathStep.IndexStep(kind)
        else -> emptyList()
    }

    /**
     * True when writing one place may change the other: the same root, and one path a prefix
     * of the other. Index steps always match (any two indices may be equal).
     */
    fun overlaps(other: Place): Boolean {
        if (!sameRoot(root(), other.root())) {
            return false
        }
        val a = path()
        val b = other.path()
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (!a[i].matches(b[i])) {
                return false
            }
        }
        return true
    }

    companion object {
        private fun sameRoot(a: Place, b: Place): Boolean = when {
            a is Field && a.receiver == null -> a === b
            a is This && b is This -> a.owner === b.owner
            else -> a == b
        }
    }
}

/** One step of a [Place.path]. */
sealed interface PathStep {
    data class FieldStep(val sym: FieldSymbol) : PathStep
    data class IndexStep(val kind: IndexKind) : PathStep

    fun matches(other: PathStep): Boolean = when (this) {
        is FieldStep -> other is FieldStep && other.sym === sym
        is IndexStep -> other is IndexStep
    }
}
