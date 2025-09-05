package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsSymbols

object AnonymousIdentifier : Identifier(DiagnosticsSymbols.NOT_REPRESENTABLE) {
    override fun toString(): String {
        return "I{?}"
    }

    override fun hashCode(): Int {
        return DiagnosticsSymbols.NOT_REPRESENTABLE.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        return true
    }
}