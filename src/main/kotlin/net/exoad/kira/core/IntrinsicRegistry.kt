package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.core.intrinsics.DeclIntrinsic

object IntrinsicRegistry {
    private val intrinsics: Map<String, CompilerIntrinsic>

    init {
        intrinsics = listOf(
            DeclIntrinsic
        ).associateBy { it.name }
    }

    fun find(name: String): CompilerIntrinsic? {
        return intrinsics[name]
    }
}