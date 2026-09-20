package net.exoad.kira.compiler.frontend

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode


interface IntrinsicTreeWalker {

    fun walkIntrinsic(node: ASTNode)

}