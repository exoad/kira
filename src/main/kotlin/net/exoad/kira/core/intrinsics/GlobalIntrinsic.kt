package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbol
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbolKind
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

object GlobalIntrinsic : CompilerIntrinsic(
    "_global", setOf(
        VariableDecl::class,
        FunctionDecl::class,
        ClassDecl::class,
        TraitDecl::class,
        EnumDecl::class,
        VariableDecl::class,
        TypeAliasDecl::class
    )
) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        if (invocation.parameters != null && invocation.parameters.isNotEmpty()) {
            throw KiraRuntimeException("The global intrinsic cannot have parameters (modifier like)")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHU JVCHVUICXUIVXCUUIVHCUIVHVICUVX")
        // TODO: lift the identifier to the top of the scope stack
        if (target is VariableDecl) {
            compilationUnit.symbolTable.declareGlobal(
                target.name.value,
                SemanticSymbol(
                    target.name.value, SemanticSymbolKind.VARIABLE,
                    Token.Type.IDENTIFIER,
                    declaredAt = context.relativeOriginOf(target).toLocationFromContext(context)
                )
            )
        }
        return NoExpr
    }
}