package net.exoad.kira.core

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.intrinsics.DeclIntrinsic
import net.exoad.kira.core.intrinsics.ExternIntrinsic
import net.exoad.kira.core.intrinsics.GlobalIntrinsic
import net.exoad.kira.core.intrinsics.MagicIntrinsic
import net.exoad.kira.core.intrinsics.OpaqueIntrinsic
import net.exoad.kira.source.SourceContext

object IntrinsicRegistry {
    /** Intrinsic spellings that lower in codegen (print family), not markers. */
    private val codegenIntrinsicNames = listOf("_trace_")

    private val intrinsics: Map<String, CompilerIntrinsic> = buildMap {
        listOf(
            DeclIntrinsic,
            GlobalIntrinsic,
            MagicIntrinsic,
            OpaqueIntrinsic,
            ExternIntrinsic,
        ).forEach { put(it.name, it) }
        // Operator intrinsics (@op_add, @op_sub, ...) are known names the
        // parser accepts as identifiers; they are not markers.
        OperatorIntrinsics.all.forEach { put(it.name, it) }
        // Codegen-backed spellings the docs expose with an @ prefix. These are
        // plain callables (handled by the backends, not markers); registering
        // them here lets `@_trace_(...)` parse as an intrinsic identifier.
        codegenIntrinsicNames.forEach { name ->
            put(name, codegenIntrinsic(name))
        }
    }

    private fun codegenIntrinsic(name: String): CompilerIntrinsic {
        return object : CompilerIntrinsic(name, emptySet()) {
            override fun validate(
                invocation: IntrinsicExpr,
                compilationUnit: CompilationUnit,
                context: SourceContext
            ) {
                // No marker semantics; the backends handle the call.
            }

            override fun apply(
                invocation: IntrinsicExpr,
                target: ASTNode,
                compilationUnit: CompilationUnit,
                context: SourceContext
            ): ASTNode = NoExpr
        }
    }

    fun find(name: String): CompilerIntrinsic? {
        return intrinsics[name]
    }

    /**
     * True when [name] is one of the declaration-marker intrinsics
     * (`@_magic`, `@_extern`, ...) that prefix a declaration. Callable
     * intrinsics (`@op_add`, `@_trace_`) are not markers and must be parsed
     * as expressions instead.
     */
    fun isDeclMarker(name: String): Boolean {
        return name in declMarkerNames
    }

    private val declMarkerNames = setOf(
        DeclIntrinsic.name,
        GlobalIntrinsic.name,
        MagicIntrinsic.name,
        OpaqueIntrinsic.name,
        ExternIntrinsic.name,
    )
}