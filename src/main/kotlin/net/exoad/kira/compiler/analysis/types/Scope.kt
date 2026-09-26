package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr

/**
 * One lambda being typed. Every variable the lambda (or a lambda nested in it) reads from
 * outside itself is recorded here as a [Capture], in first-use order and each once, and
 * becomes `TypedModel.captures[lambda]` when the lambda is done.
 *
 * @property parent the lambda this one is written inside, or null.
 * @property owner the class, struct or trait whose method the lambda is written in, when
 *   there is one: a field read through the implicit receiver is captured against it.
 */
internal class LambdaFrame(val lambda: LambdaExpr, val parent: LambdaFrame?, val owner: TypeSymbol?) {
    private val seen = LinkedHashSet<Any>()
    val captures: MutableList<Capture> = mutableListOf()

    fun add(capture: Capture) {
        val key: Any = when (capture) {
            is Capture.Value -> capture.symbol
            is Capture.Field -> capture.field
            is Capture.This -> capture.owner
        }
        if (seen.add(IdentityKey(key))) {
            captures.add(capture)
        }
    }

    /** Symbols compared by identity, never by value. */
    private class IdentityKey(val value: Any) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
        override fun hashCode(): Int = System.identityHashCode(value)
    }
}

/**
 * The lexical scope of a body: locals, parameters, loop variables and a handler's `on e`
 * variable, innermost first. Each scope knows the lambda it belongs to ([lambda]), so a
 * lookup that finds a name declared outside the current lambda knows that name is a capture.
 */
internal class Scope private constructor(val parent: Scope?, val lambda: LambdaFrame?) {
    private val names = LinkedHashMap<String, Symbol>()

    /** A name found by [find]: the symbol, and the lambda whose body declared it (null: none). */
    data class Found(val symbol: Symbol, val declaredIn: LambdaFrame?)

    /** Declares [name] here; returns the symbol it replaces in this same scope, if any. */
    fun declare(name: String, symbol: Symbol): Symbol? = names.put(name, symbol)

    fun find(name: String): Found? {
        var s: Scope? = this
        while (s != null) {
            s.names[name]?.let { return Found(it, s.lambda) }
            s = s.parent
        }
        return null
    }

    /** A nested block: same lambda. */
    fun child(): Scope = Scope(this, lambda)

    /** The body of [frame]: names declared from here on belong to that lambda. */
    fun lambdaBody(frame: LambdaFrame): Scope = Scope(this, frame)

    companion object {
        fun root(): Scope = Scope(null, null)
    }
}

/**
 * Where a body is: the module, the function or method (null for a class's `initially` and
 * `finally`, a default, a global initializer or a static assert), the type whose members
 * are reachable without a receiver ([owner]), and what a `return` must produce.
 *
 * @property thisMutable the implicit receiver may be written: a struct's `mut fx`, and any
 *   class method or class `initially`/`finally` (a class is a reference, D29).
 * @property what the body in words, for messages: "function 'read'".
 */
internal class BodyContext(
    val module: ModuleSymbol,
    val fn: FnSymbol?,
    val owner: TypeSymbol?,
    val returnType: KType,
    val lambda: LambdaFrame?,
    val thisMutable: Boolean,
    val what: String,
) {
    /** The same place, inside [frame], which returns [ret]. */
    fun inLambda(frame: LambdaFrame, ret: KType): BodyContext =
        BodyContext(module, fn, owner, ret, frame, thisMutable, "a lambda in $what")
}
