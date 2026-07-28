package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * Marks a type as a **foreign opaque handle** (C pointer in C-as-IR).
 * Not Kira-ARC; lifetime follows the C library.
 */
object OpaqueIntrinsic : CompilerIntrinsic(
    "_opaque",
    setOf(ClassDecl::class, TypeAliasDecl::class, Type::class, Identifier::class)
) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        val n = invocation.parameters?.size ?: 0
        if (n > 0) {
            throw KiraRuntimeException("@_opaque does not take parameters")
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
            is TypeAliasDecl -> (target.alias.identifier as? Identifier)?.value
            else -> null
        }
        if (typeName != null) {
            compilationUnit.registerOpaqueType(typeName)
        }
        return NoExpr
    }
}
