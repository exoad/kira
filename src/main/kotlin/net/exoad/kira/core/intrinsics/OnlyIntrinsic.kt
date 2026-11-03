package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * `@_only("py")`, `@_only("cpp", "c")`: the declaration is built for the listed targets only. Target names are
 * [TARGETS], bare or as strings. The typer checks that shared code never reaches it (`rules.only.reach`) and drops it
 * from the other targets' output; this intrinsic checks the spelling and the place.
 */
object OnlyIntrinsic : CompilerIntrinsic(
    "_only",
    setOf(FunctionDecl::class, ClassDecl::class, StructDecl::class, TraitDecl::class, VariableDecl::class)
) {
    /** The targets a declaration may be limited to; `none` is no target (the language server and the typer tests see all). */
    val TARGETS: List<String> = listOf("c", "cpp", "js", "py")

    /** The spelling error of [parameters] and [named] (a target name each, once), or null when it reads. */
    fun spellingError(parameters: List<Expr>?, named: Map<String, Expr>): String? {
        val shape = "@_only names the targets a declaration is built for: @_only(\"py\") or @_only(\"cpp\", \"c\"), from ${TARGETS.joinToString(", ")}"
        if (parameters.isNullOrEmpty() || named.isNotEmpty()) {
            return shape
        }
        val seen = mutableSetOf<String>()
        for (p in parameters) {
            val name = (p as? StringLiteral)?.value ?: return "@_only's targets are strings, not ${p::class.simpleName}: $shape"
            if (name !in TARGETS) {
                return "@_only does not know the target '$name': $shape"
            }
            if (!seen.add(name)) {
                return "@_only names '$name' twice"
            }
        }
        return null
    }

    /** The target names [parameters] spell, in order; the unreadable ones are left out ([spellingError] says so). */
    fun namesOf(parameters: List<Expr>?): Set<String> {
        val out = linkedSetOf<String>()
        parameters.orEmpty().forEach { p -> (p as? StringLiteral)?.let { out.add(it.value) } }
        return out.filter { it in TARGETS }.toSet()
    }

    /** The targets [node] is limited to by an `@_only` written on it, or null when it carries none. */
    fun limitOf(context: SourceContext, node: ASTNode): Set<String>? =
        context.intrinsicInvocationsOf(node).firstOrNull { it.intrinsicKey === this }?.let { namesOf(it.parameters) }

    /** The name of [mode]'s target, or null for `none`. */
    fun targetName(mode: GeneratedProvider.OutputTarget): String? = when (mode) {
        GeneratedProvider.OutputTarget.C -> "c"
        GeneratedProvider.OutputTarget.CPP -> "cpp"
        GeneratedProvider.OutputTarget.JS -> "js"
        GeneratedProvider.OutputTarget.PY -> "py"
        GeneratedProvider.OutputTarget.NONE -> null
    }

    /** The `@_only` of the class or struct called [typeName] in [context], or null when it has none. */
    private fun limitOfType(context: SourceContext, typeName: String): Set<String>? {
        val marked = runCatching { context.astIntrinsicInvocations }.getOrNull() ?: return null
        for ((node, invocations) in marked) {
            val name = when (node) {
                is ClassDecl -> (node.name.identifier as? Identifier)?.value
                is StructDecl -> (node.name.identifier as? Identifier)?.value
                else -> null
            }
            if (name == typeName) {
                invocations.firstOrNull { it.intrinsicKey === this }?.let { return namesOf(it.parameters) }
            }
        }
        return null
    }

    /**
     * Whether [node], or the class or struct [ownerName] it is a member of, is limited to targets that leave [mode] out:
     * the build of [mode] drops it, so its target-specific arguments are not for that build to check.
     */
    fun dropsOn(context: SourceContext, node: ASTNode, ownerName: String?, mode: GeneratedProvider.OutputTarget): Boolean {
        val here = targetName(mode) ?: return false
        val own = limitOf(context, node)
        val owner = ownerName?.let { limitOfType(context, it) }
        return (own != null && here !in own) || (owner != null && here !in owner)
    }

    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        spellingError(invocation.parameters, invocation.namedParameters)?.let { throw KiraRuntimeException(it) }
    }

    /**
     * Where an `@_only` may stand: a module-level function, class, struct, trait or constant, or a method of a class
     * or struct that is not an override. C and JS run on the untyped tree, which neither checks nor drops, so they
     * refuse it; `none` and the tests (mode NONE) read it.
     */
    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        val mode = GeneratedProvider.outputMode
        if (mode == GeneratedProvider.OutputTarget.C || mode == GeneratedProvider.OutputTarget.JS) {
            throw KiraRuntimeException("@_only is read by the typed backends, py and cpp; the ${mode.name} backend has no typed program to check or drop declarations with")
        }
        val scope = compilationUnit.symbolTable.where()
        val inTrait = scope is SemanticScope.Trait
        val inType = scope is SemanticScope.Class
        val atModule = scope is SemanticScope.Module || scope == SemanticScope.Global
        when {
            target is VariableDecl && !atModule ->
                throw KiraRuntimeException("@_only marks a module-level constant, not a field or a local: a construction would change shape per target")
            target is FunctionDecl && inTrait ->
                throw KiraRuntimeException("@_only does not mark a trait's member: every implementer would have to agree on it")
            target is FunctionDecl && Modifier.OVERRIDE in target.modifiers ->
                throw KiraRuntimeException("@_only does not mark an override: the method it overrides decides which targets it exists on")
            target is FunctionDecl && !inType && !atModule ->
                throw KiraRuntimeException("@_only marks a module-level function or a method, not a function declared inside a body")
        }
        return NoExpr
    }
}
