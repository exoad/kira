package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariantDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

object MagicIntrinsic : CompilerIntrinsic(
    "_magic", setOf(
        Identifier::class,
        Type::class,
        ClassDecl::class,
        TraitDecl::class,
        VariantDecl::class,
        EnumDecl::class,
        TypeAliasDecl::class,
        FunctionDecl::class,
        VariableDecl::class,
    )
) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        val parameterCount = invocation.parameters?.size ?: 0
        if (parameterCount > 1) {
            throw KiraRuntimeException("Magic intrinsic accepts at most one parameter")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        val typeName = when (target) {
            is Identifier -> target.value
            is Type -> (target.identifier as? Identifier)?.value
            is ClassDecl -> (target.name.identifier as? Identifier)?.value
            is TraitDecl -> (target.name.identifier as? Identifier)?.value
            is VariantDecl -> (target.name.identifier as? Identifier)?.value
            is EnumDecl -> target.name.value
            is TypeAliasDecl -> (target.alias.identifier as? Identifier)?.value
            is FunctionDecl -> (target.name as? Identifier)?.value
            is VariableDecl -> target.name.value
            else -> null
        }
        if (typeName != null) {
            compilationUnit.registerMagicType(typeName)
        }
        return NoExpr
    }
}