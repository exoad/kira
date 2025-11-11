package net.exoad.kira.compiler.frontend.parser.ast

import net.exoad.kira.core.CompilerIntrinsic

interface ASTNode {
    fun accept(visitor: KiraASTVisitor)

    // Default empty list implementation so implementations don't have to
    // declare/forward attachedIntrinsics unless they need a specific value.
    val attachedIntrinsics: List<CompilerIntrinsic>
        get() = emptyList()
}
