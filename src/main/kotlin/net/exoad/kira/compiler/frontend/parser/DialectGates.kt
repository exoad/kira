package net.exoad.kira.compiler.frontend.parser

/**
 * Refusals of stage 3 that old text still breaks: each waits for the corpus to be converted, then
 * is switched on with `return`'s refusal.
 */
object DialectGates {
    /** An `if` used as a value whose arms are all single expressions is refused: `c ? a : b` is its spelling. */
    @Volatile
    var ternaryOnly: Boolean = false

    /** `return` is refused: a block's last expression is its value. kira-dialect's reading of old text keeps it. */
    @Volatile
    var noReturn: Boolean = false

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

    /** [block] with both of stage 3's refusals set to [on], restored after. */
    fun <T> stage3(on: Boolean, block: () -> T): T {
        val saved = noReturn
        noReturn = on
        try {
            return ternaryOnly(on, block)
        } finally {
            noReturn = saved
        }
    }
}
