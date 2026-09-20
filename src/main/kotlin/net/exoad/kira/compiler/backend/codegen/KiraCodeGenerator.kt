package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode

abstract class KiraCodeGenerator(open val compilationUnit: CompilationUnit) : KiraASTVisitor() {
    abstract fun visitRootASTNode(node: RootASTNode)
}