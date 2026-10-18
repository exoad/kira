package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode

abstract class KiraCodeGenerator(compilationUnit: CompilationUnit) : KiraASTVisitor() {
    init {
        net.exoad.kira.compiler.DialectLink.link(compilationUnit)
    }

    open val compilationUnit: CompilationUnit = compilationUnit

    abstract fun visitRootASTNode(node: RootASTNode)
}