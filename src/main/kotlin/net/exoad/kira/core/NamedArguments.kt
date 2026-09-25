package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr

/**
 * Binds a call's arguments to its callee's parameters by position and by
 * name. The one place that rule lives: the semantic analyzer reports an
 * [Binding.Unbound] as a diagnostic, and each backend emits the
 * [Binding.Ordered] list. A backend that meets an Unbound binding (only
 * possible when analysis was skipped) refuses to emit rather than reorder
 * silently -- which is what the old code did: it appended named arguments
 * in the order written and ignored the names.
 *
 * Rules: positional arguments fill the leading parameters; each named
 * argument must name a parameter the callee declares, that parameter must
 * not already be filled, and every parameter must end up filled (there are
 * no default parameters yet). A call without named arguments always binds.
 */
object NamedArguments {
    sealed class Binding {
        /** Arguments in the callee's parameter order. */
        class Ordered(val arguments: List<Expr>) : Binding()

        /** Why the call cannot be bound; a diagnostic message. */
        class Unbound(val message: String) : Binding()
    }

    /**
     * [parameterNames] is null when the callee could not be resolved, which
     * is an error only if the call actually uses names.
     */
    fun bind(call: FunctionCallExpr, calleeName: String, parameterNames: List<String>?): Binding {
        val positional = call.positionalParameters.map { it.value }
        if (call.namedParameters.isEmpty()) {
            return Binding.Ordered(positional)
        }
        if (parameterNames == null) {
            return Binding.Unbound(
                "Named arguments need a callee the compiler can resolve, and '$calleeName' is not a " +
                    "known function or method here."
            )
        }
        if (positional.size > parameterNames.size) {
            return Binding.Unbound(
                "'$calleeName' takes ${parameterNames.size} parameter(s), but ${positional.size} " +
                    "positional argument(s) were given."
            )
        }
        val slots = arrayOfNulls<Expr>(parameterNames.size)
        positional.forEachIndexed { index, expr -> slots[index] = expr }
        for (named in call.namedParameters) {
            val name = named.name.value
            val index = parameterNames.indexOf(name)
            if (index < 0) {
                return Binding.Unbound(
                    "'$calleeName' has no parameter named '$name'. Its parameters are: " +
                        parameterNames.joinToString(", ") + "."
                )
            }
            if (slots[index] != null) {
                return Binding.Unbound("Parameter '$name' of '$calleeName' is given more than once.")
            }
            slots[index] = named.value
        }
        val missing = parameterNames.filterIndexed { index, _ -> slots[index] == null }
        if (missing.isNotEmpty()) {
            return Binding.Unbound(
                "Call to '$calleeName' leaves parameter(s) without an argument: ${missing.joinToString(", ")}."
            )
        }
        return Binding.Ordered(slots.map { it!! })
    }
}
