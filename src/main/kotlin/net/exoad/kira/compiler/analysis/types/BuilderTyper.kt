package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Builder
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

/**
 * `@listOf`, `@arrOf`, `@setOf` and `@mapOf`: a List, Arr, Set or Map of the elements, which
 * are one `TupleN<T>` (a Map's keys and values come in turn). The element type is the type
 * argument, else the expected type's, else the first element's; then every element is checked
 * against it, and with neither a type argument nor an expected type each element has exactly
 * the first one's type (no conversion). An empty builder needs one of the two. `@arrOf` under
 * an expected `Arr<T, N>` is that type and holds exactly N elements.
 *
 * [Builder.INFER] is stage 1's `[...]`, typed by stage 1's rule (a List where a List is
 * expected, else an Arr); kira-dialect reads what it became off [TypedModel.inferred].
 */
internal class BuilderTyper(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    fun builder(e: BuilderExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType = when (e.kind) {
        Builder.INFER -> legacy(e, hint, ctx, scope)
        Builder.MAP -> map(e, hint, ctx, scope)
        else -> sequence(e, hint, ctx, scope)
    }

    private fun sequence(e: BuilderExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val name = "@${e.kind.intrinsic}"
        val explicit = typeArgs(e, 1) ?: return elementsOnly(e, ctx, scope)
        val wanted = hint?.takeIf { isKind(it, e.kind) } as? KType.Nominal
        val element = explicit.firstOrNull() ?: wanted?.let { facts.elementOf(it) }
        val t = if (element != null) {
            e.elements.forEachIndexed { i, v -> c.exprs.check(v, element, ctx, scope, "element ${i + 1} of $name") }
            element
        } else {
            byFirst(e, e.elements, name, "element", ctx, scope) ?: return KType.Error
        }
        if (t.containsError()) {
            return KType.Error
        }
        return when (e.kind) {
            Builder.ARR -> {
                val fixed = wanted?.takeIf { facts.isFixedArr(it) && facts.elementOf(it) == t }
                val size = fixed?.constArgs()?.firstOrNull()
                if (size != null && e.elements.size.toLong() != size) {
                    c.report(
                        "types.const.arr-size",
                        "This @arrOf has ${e.elements.size} element${if (e.elements.size == 1) "" else "s"}, but ${fixed.display()} holds exactly $size.",
                        e,
                    )
                }
                fixed ?: facts.arrOf(t)
            }
            Builder.SET -> {
                key(e, t, "a Set's element")
                facts.setOf(t)
            }
            else -> facts.listOf(t)
        }
    }

    private fun map(e: BuilderExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val explicit = typeArgs(e, 2) ?: return elementsOnly(e, ctx, scope)
        if (e.elements.size % 2 != 0) {
            c.report(
                "types.builder.pairs",
                "@mapOf takes each key and then its value, @mapOf(k1, v1, k2, v2); this one has ${e.elements.size} elements.",
                e,
            )
            elementsOnly(e, ctx, scope)
            return KType.Error
        }
        val wanted = (hint?.takeIf { facts.isMap(it) } as? KType.Nominal)?.typeArgs()
        val keys = e.elements.filterIndexed { i, _ -> i % 2 == 0 }
        val values = e.elements.filterIndexed { i, _ -> i % 2 == 1 }
        val k = side(e, keys, explicit.getOrNull(0) ?: wanted?.getOrNull(0), "key", ctx, scope) ?: return KType.Error
        val v = side(e, values, explicit.getOrNull(1) ?: wanted?.getOrNull(1), "value", ctx, scope) ?: return KType.Error
        if (k.containsError() || v.containsError()) {
            return KType.Error
        }
        key(e, k, "a Map's key")
        return facts.mapOf(k, v)
    }

    /** A Map's keys or values against [known], else by the first of them; null when empty and unknown. */
    private fun side(e: BuilderExpr, items: List<Expr>, known: KType?, what: String, ctx: BodyContext, scope: Scope): KType? {
        if (known == null) {
            return byFirst(e, items, "@mapOf", what, ctx, scope)
        }
        items.forEachIndexed { i, x -> c.exprs.check(x, known, ctx, scope, "$what ${i + 1} of @mapOf") }
        return known
    }

    /** The first element's type, every other element exactly of it; null (reported) when there is none. */
    private fun byFirst(e: BuilderExpr, items: List<Expr>, name: String, what: String, ctx: BodyContext, scope: Scope): KType? {
        if (items.isEmpty()) {
            c.report(
                "types.builder.untyped",
                "An empty $name needs its ${if (name == "@mapOf") "key and value types" else "element type"}: from its context " +
                    "(`x: ${example(name)} = $name()`) or written ($name<${if (name == "@mapOf") "Str, Int32" else "Int32"}>()).",
                e,
            )
            return null
        }
        val first = c.exprs.synth(items[0], ctx, scope)
        items.drop(1).forEachIndexed { i, x ->
            val t = c.exprs.type(x, first.takeIf { !it.containsError() }, ctx, scope)
            if (!t.containsError() && !first.containsError() && t != first) {
                c.report(
                    "types.builder.element",
                    "The ${what}s of $name have one type, which its context or a type argument can give: " +
                        "$what ${i + 2} is ${t.display()}, $what 1 ${first.display()}.",
                    x,
                )
            }
        }
        return first
    }

    private fun example(name: String): String = when (name) {
        "@arrOf" -> "Arr<Int32>"
        "@setOf" -> "Set<Int32>"
        "@mapOf" -> "Map<Str, Int32>"
        else -> "List<Int32>"
    }

    /** The written type arguments, which must be [n] or none; null (reported) otherwise. */
    private fun typeArgs(e: BuilderExpr, n: Int): List<KType>? {
        if (e.typeArgs.isEmpty()) {
            return emptyList()
        }
        if (e.typeArgs.size != n) {
            c.report(
                "types.builder.type-args",
                "@${e.kind.intrinsic} takes ${if (n == 1) "one type argument, its element's" else "two type arguments, its key's and its value's"}, not ${e.typeArgs.size}.",
                e,
            )
            return null
        }
        return e.typeArgs.map { c.typeOf(it) }
    }

    private fun elementsOnly(e: BuilderExpr, ctx: BodyContext, scope: Scope): KType {
        e.elements.forEach { c.exprs.synth(it, ctx, scope) }
        return KType.Error
    }

    private fun isKind(t: KType, kind: Builder): Boolean = when (kind) {
        Builder.LIST -> facts.isList(t)
        Builder.ARR -> facts.isArr(t)
        Builder.SET -> facts.isMagic(t, "Set")
        else -> false
    }

    private fun key(e: BuilderExpr, t: KType, what: String) {
        if (!isKeyType(t)) {
            c.report("types.type.key", "@${e.kind.intrinsic} makes ${t.display()} $what; $KEY_RULE.", e)
        }
    }

    /** Stage 1's `[a, b]`: an Arr or List from the context (an Arr<T, N>'s count must match), else an Arr of the first element's type. */
    private fun legacy(e: BuilderExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val arr = hint?.takeIf { facts.isArr(it) || facts.isList(it) } as? KType.Nominal
        if (arr != null) {
            val element = facts.elementOf(arr) ?: KType.Error
            val size = arr.constArgs().firstOrNull()
            if (size != null && e.elements.size.toLong() != size) {
                c.report(
                    "types.const.arr-size",
                    "This literal has ${e.elements.size} element${if (e.elements.size == 1) "" else "s"}, but ${arr.display()} holds exactly $size.",
                    e,
                )
            }
            e.elements.forEachIndexed { i, v -> c.exprs.check(v, element, ctx, scope, "element ${i + 1} of the array") }
            model.inferred[e] = if (facts.isList(arr)) Builder.LIST else Builder.ARR
            return arr
        }
        model.inferred[e] = Builder.ARR
        if (e.elements.isEmpty()) {
            c.report("types.array.untyped", "An empty array literal needs its type from the context, like xs: Arr<Int32> = [].", e)
            return KType.Error
        }
        val first = c.exprs.synth(e.elements.first(), ctx, scope)
        e.elements.drop(1).forEachIndexed { i, v -> c.exprs.check(v, first, ctx, scope, "element ${i + 2} of the array") }
        return if (first.containsError()) KType.Error else facts.arrOf(first)
    }
}
