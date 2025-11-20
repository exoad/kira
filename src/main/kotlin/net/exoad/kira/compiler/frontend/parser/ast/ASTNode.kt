package net.exoad.kira.compiler.frontend.parser.ast

import net.exoad.kira.core.CompilerIntrinsic

abstract class ASTNode {
    abstract fun accept(visitor: KiraASTVisitor)

    // Default empty list implementation so implementations don't have to
    // declare/forward attachedIntrinsics unless they need a specific value.
    open val attachedIntrinsics: List<CompilerIntrinsic>
        get() = emptyList()
}
