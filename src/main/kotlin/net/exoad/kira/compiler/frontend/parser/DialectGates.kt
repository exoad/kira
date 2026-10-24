package net.exoad.kira.compiler.frontend.parser

/**
 * Stage 3's refusals of old text, on since the corpus was converted. kira-dialect's reading of
 * old text (KiraParser's `returns`) is never refused; a test may switch one off for a block.
 */
object DialectGates {
    /** An `if` used as a value whose arms are all single expressions is refused: `c ? a : b` is its spelling. */
    @Volatile
    var ternaryOnly: Boolean = true

    /** `return` is refused: a block's last expression is its value. kira-dialect's reading of old text keeps it. */
    @Volatile
    var noReturn: Boolean = true

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

    /** Stage 4: `mut` is refused (a binding is `x: Mutable<T>`, a type `T: Mutable<P>`); kira-dialect's reading of old text keeps it. */
    @Volatile
    var noMut: Boolean = false

    /**
     * Stage 4's typing: a Mutable type's value is shared and changes through any binding, a type with Mutable
     * state says so, and a field changes only when it is Mutable. Off, the old rules, as kira-dialect reads old text.
     */
    @Volatile
    var mutability: Boolean = false

    /** [block] with both of stage 4's gates set to [on], restored after. */
    fun <T> stage4(on: Boolean, block: () -> T): T {
        val savedMut = noMut
        val savedRules = mutability
        noMut = on
        mutability = on
        try {
            return block()
        } finally {
            noMut = savedMut
            mutability = savedRules
        }
    }
}
