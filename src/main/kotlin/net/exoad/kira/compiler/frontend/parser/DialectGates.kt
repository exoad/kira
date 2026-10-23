package net.exoad.kira.compiler.frontend.parser

/**
 * Refusals of stage 3 that old text still breaks: each waits for the corpus to be converted, then
 * is switched on with `return`'s refusal.
 */
object DialectGates {
    /** An `if` used as a value whose arms are all single expressions is refused: `c ? a : b` is its spelling. */
    @Volatile
    var ternaryOnly: Boolean = false

    /** [block] with [ternaryOnly] set to [on], restored after. */
    fun <T> ternaryOnly(on: Boolean, block: () -> T): T {
        val saved = ternaryOnly
        ternaryOnly = on
        try {
            return block()
        } finally {
            ternaryOnly = saved
        }
    }
}
