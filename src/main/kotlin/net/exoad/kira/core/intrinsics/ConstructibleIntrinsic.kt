package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * `@_constructible` on an `@_extern` struct: `T { }` builds the C++ object by value with its
 * default constructor (`::Servo{}`), where an extern type is otherwise made by the C/C++ side
 * only. The object is not copied by Kira (the typer refuses a copy), so a C++ class whose
 * copy shares or duplicates hardware state is never duplicated behind the program's back.
 * C++ only: the other backends have no C++ class to construct. No arguments.
 */
object ConstructibleIntrinsic : CompilerIntrinsic("_constructible", setOf(StructDecl::class, ClassDecl::class)) {
    override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
        if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
            throw KiraRuntimeException("@_constructible takes no arguments (modifier like)")
        }
    }

    override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode {
        val name = nameOf(target)
        refuseOffCpp(name)
        val marks = runCatching { context.astIntrinsicMarked[target] }.getOrNull().orEmpty().map { it.name }
        if (target is ClassDecl || OpaqueIntrinsic.name in marks) {
            throw KiraRuntimeException(
                "@_constructible on '$name': a constructible C++ object is held by value, so declare it as an @_extern struct " +
                    "('$name: Struct { ... }'), not a class or an @_opaque handle"
            )
        }
        if (ExternIntrinsic.name !in marks) {
            throw KiraRuntimeException(
                "@_constructible on '$name': only an @_extern struct is built by its C++ default constructor; " +
                    "a Kira struct is constructed with { } already"
            )
        }
        return NoExpr
    }

    private fun refuseOffCpp(name: String) {
        val mode = GeneratedProvider.outputMode
        if (mode == GeneratedProvider.OutputTarget.CPP || mode == GeneratedProvider.OutputTarget.NONE) {
            return
        }
        throw KiraRuntimeException(
            "@_constructible on '$name' builds a C++ object by value with its default constructor, which reaches --target cpp only: " +
                "the ${mode.name} backend has no C++ class to construct"
        )
    }

    private fun nameOf(target: ASTNode): String = when (target) {
        is ClassDecl -> (target.name.identifier as? Identifier)?.value
        is StructDecl -> (target.name.identifier as? Identifier)?.value
        else -> null
    } ?: "?"
}
