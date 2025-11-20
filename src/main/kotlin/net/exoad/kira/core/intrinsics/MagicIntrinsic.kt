package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext
import kotlin.reflect.KClass

object MagicIntrinsic : CompilerIntrinsic("_magic", setOf(Identifier::class, Type::class)) {
    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        if (invocation.parameters == null || invocation.parameters.size != 1) {
            throw KiraRuntimeException("Magic intrinsic can only have one parameter")
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        // TODO: lift the identifier to treat as a special type (probably complex)
        return NoExpr
    }
}