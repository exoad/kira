package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.DialectGates
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A test class of what only stage 3's text spells (D4's `mut` arguments and parameters, a value's `mut fx`, the
 * copies of D44), typed with stage 4's gates off for the whole class, as kira-dialect reads that text.
 */
class StageThreeText : BeforeAllCallback, AfterAllCallback {
    private var saved: Pair<Boolean, Boolean>? = null

    override fun beforeAll(context: ExtensionContext) {
        saved = DialectGates.noMut to DialectGates.mutability
        DialectGates.noMut = false
        DialectGates.mutability = false
    }

    override fun afterAll(context: ExtensionContext) {
        saved?.let { (noMut, rules) ->
            DialectGates.noMut = noMut
            DialectGates.mutability = rules
        }
    }
}
