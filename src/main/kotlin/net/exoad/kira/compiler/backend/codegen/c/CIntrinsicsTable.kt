package net.exoad.kira.compiler.backend.codegen.c

object CIntrinsicsTable {
    private data class CIntrinsicBinding(
        val functionName: String,
        val requiredIncludes: Set<String> = emptySet()
    )

    private val bindings = mapOf(
        "trace" to CIntrinsicBinding("printf", setOf("stdio.h")),
        "print" to CIntrinsicBinding("printf", setOf("stdio.h")),
        "println" to CIntrinsicBinding("printf", setOf("stdio.h")),
        "eprint" to CIntrinsicBinding("fprintf", setOf("stdio.h")),
        "_trace_" to CIntrinsicBinding("printf", setOf("stdio.h")),
        "sqrt" to CIntrinsicBinding("sqrt", setOf("math.h")),
        "pow" to CIntrinsicBinding("pow", setOf("math.h")),
        "floor" to CIntrinsicBinding("floor", setOf("math.h")),
        "ceil" to CIntrinsicBinding("ceil", setOf("math.h")),
        "round" to CIntrinsicBinding("round", setOf("math.h")),
        "sin" to CIntrinsicBinding("sin", setOf("math.h")),
        "cos" to CIntrinsicBinding("cos", setOf("math.h")),
        "tan" to CIntrinsicBinding("tan", setOf("math.h")),
        "abs" to CIntrinsicBinding("fabs", setOf("math.h")),
        "min" to CIntrinsicBinding("fmin", setOf("math.h")),
        "max" to CIntrinsicBinding("fmax", setOf("math.h")),
        "assert" to CIntrinsicBinding("assert", setOf("assert.h")),
    )

    fun resolveFunction(name: String): String {
        return bindings[name]?.functionName ?: name
    }

    fun resolveIncludes(name: String): Set<String> {
        return bindings[name]?.requiredIncludes ?: emptySet()
    }
}