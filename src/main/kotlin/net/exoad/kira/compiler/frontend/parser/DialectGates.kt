package net.exoad.kira.compiler.frontend.parser

/**
 * The dialect's refusals of old text (stage 3's and 5's), on since the corpus was converted.
 * kira-dialect's reading of old text is never refused; a test may switch one off for a block.
 */
object DialectGates {
    /** An `if` used as a value whose arms are all single expressions is refused: `c ? a : b` is its spelling. */
    @Volatile
    var ternaryOnly: Boolean = true

    /** `return` is refused: a block's last expression is its value. kira-dialect's reading of old text keeps it. */
    @Volatile
    var noReturn: Boolean = true

    /**
     * Stage 5: an import reaches its module's names only through its alias or a member alias; with
     * this off (kira-dialect reading old text), every pub name of an imported module is seen bare too.
     */
    @Volatile
    var aliasOnlyImports: Boolean = true

    /** [block] with [aliasOnlyImports] set to [on], restored after. */
    fun <T> aliasOnlyImports(on: Boolean, block: () -> T): T {
        val saved = aliasOnlyImports
        aliasOnlyImports = on
        try {
            return block()
        } finally {
            aliasOnlyImports = saved
        }
    }

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
