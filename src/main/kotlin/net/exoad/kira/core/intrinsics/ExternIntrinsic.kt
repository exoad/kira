package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext

/**
 * `@_extern`: a declaration whose definition lives outside Kira (design 7.2, 7.3).
 *
 * Parameters, every one a string literal:
 * - one positional, the symbol for the current target (`@_extern("fopen")`);
 * - `c =`, the C symbol, and `cpp =`, the C++ name (`bibo::Car`, `ImGui::Button`);
 * - `header =`, the C++ header the name is declared in, which the C++ backend includes;
 * - `raises =` (D69): the Python exceptions, space-separated, a py extern's call throws as a Kira
 *   throw; py binds an extern by its Kira name (D67) and refuses the four C and C++ names.
 *
 * Targets: a function (its body, if any, is ignored: the C backend emits a prototype and
 * calls the symbol unmangled), a class or a struct (the C++ backend emits drift checks
 * against the real header, never a second declaration), a module-level constant (used by
 * its C++ name), and a method inside an extern class or struct (its own C++ name, when it
 * differs from the Kira one). `@_opaque` may be combined with `@_extern(cpp = ...)`.
 *
 * [apply] registers the C symbol for the C backend; the typed frontend reads every
 * parameter from the parser's stored invocation ([net.exoad.kira.compiler.analysis.types.Foreign.Extern]).
 */
object ExternIntrinsic : CompilerIntrinsic(
    "_extern",
    setOf(FunctionDecl::class, ClassDecl::class, StructDecl::class, VariableDecl::class, Identifier::class)
) {
    const val CPP = "cpp"
    const val C = "c"
    const val HEADER = "header"
    const val RAISES = "raises"

    /** The named parameters `@_extern` accepts. */
    val namedParameters: Set<String> = setOf(CPP, C, HEADER, RAISES)

    override fun validate(
        invocation: IntrinsicExpr,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ) {
        val positional = invocation.parameters ?: emptyList()
        if (positional.size > 1) {
            throw KiraRuntimeException(
                "@_extern accepts at most one positional string, the symbol for the current target; name the others: " +
                    "@_extern(cpp = \"ns::name\", c = \"name\", header = \"name.hxx\")"
            )
        }
        positional.firstOrNull()?.let { p ->
            if (p !is StringLiteral) {
                throw KiraRuntimeException("@_extern's symbol is a string literal, not ${p::class.simpleName}")
            }
        }
        invocation.namedParameters.forEach { (name, value) ->
            if (name !in namedParameters) {
                throw KiraRuntimeException(
                    "@_extern does not take '$name'; it takes cpp = (the C++ name), c = (the C symbol), header = (the C++ header) and raises = (the Python exceptions, py only)"
                )
            }
            if (value !is StringLiteral) {
                throw KiraRuntimeException("@_extern's '$name' is a string literal, not ${value::class.simpleName}")
            }
        }
    }

    /**
     * Applied by the semantic pass with the declaration's scope entered ([KiraSymbolTable.where]):
     * a module for a module-level declaration, a class for a member, a function for a local.
     *
     * Two rules hold whatever the target: a constant is a module-level declaration (a local
     * `@_extern(cpp = "probe::LOCAL") loc: Int32 = 3` inside a body was accepted and then
     * lowered as a plain local, the marker dropped, measured), and a method takes `@_extern`
     * only inside an extern class or struct (elsewhere the marker would make a method with no
     * body, and the C backend once harvested it as a free C function of that name).
     *
     * The C and JS backends lower `@_extern` on a free function only: they call its symbol.
     * A class, a struct, a constant or a method is C++'s (design 7.2), and under `--target c`
     * the constant `@_extern(c = "INT_MAX", header = "limits.h") pub IMAX: Int32;` was written
     * as a new zero global `Int32 IMAX;` and the program printed 0 for it, `--target js` wrote
     * `const IMAX;`, which node refuses, and both exited 0 (measured). Refused here, with the
     * target named; `--target none` and the tests (mode NONE) take every target of 7.2. A free
     * function whose marker names a C++ symbol and no C one (`cpp =` without `c =` or the
     * positional) is refused under those two targets too: bound to its Kira name it would
     * link to nothing, or to an unrelated C function of that name.
     */
    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        val scope = compilationUnit.symbolTable.where()
        val nameOf = { d: ASTNode ->
            when (d) {
                is FunctionDecl -> (d.name as? Identifier)?.value
                is VariableDecl -> d.name.value
                is ClassDecl -> (d.name.identifier as? Identifier)?.value
                is StructDecl -> (d.name.identifier as? Identifier)?.value
                is Identifier -> d.value
                else -> null
            } ?: "?"
        }
        if (target is VariableDecl && scope !is SemanticScope.Module && scope != SemanticScope.Global) {
            throw KiraRuntimeException(
                "@_extern names a module-level constant; '${nameOf(target)}' is declared inside a ${
                    if (scope is SemanticScope.Class) "class or struct (a field is checked by its Kira name)" else "body (a local takes no marker)"
                }"
            )
        }
        val mode = GeneratedProvider.outputMode
        // A method of an @_opaque class takes the marker for py (D70), so under none too, as the language server runs.
        val opaqueMethod = (mode == GeneratedProvider.OutputTarget.PY || mode == GeneratedProvider.OutputTarget.NONE) &&
            scope is SemanticScope.Class && isMarked(context, scope.name, OpaqueIntrinsic.name)
        if (target is FunctionDecl && scope is SemanticScope.Class && !isMarked(context, scope.name, this.name) && !opaqueMethod) {
            throw KiraRuntimeException(
                "@_extern on the method '${nameOf(target)}': its class or struct '${scope.name}' must be extern itself " +
                    "(@_extern(cpp = \"ns::Name\", header = \"name.hxx\") on the declaration)"
            )
        }
        if (RAISES in invocation.namedParameters && mode != GeneratedProvider.OutputTarget.PY && mode != GeneratedProvider.OutputTarget.NONE) {
            throw KiraRuntimeException(
                "@_extern's raises = names Python exceptions, which the ${mode.name} backend has none of: it reaches --target py only"
            )
        }
        if (mode == GeneratedProvider.OutputTarget.PY) {
            val named = invocation.namedParameters.keys.filter { it != RAISES }.map { "$it =" } +
                listOfNotNull(invocation.parameters?.firstOrNull()?.let { "a positional symbol" })
            if (named.isNotEmpty()) {
                throw KiraRuntimeException(
                    "@_extern on '${nameOf(target)}' names ${named.joinToString(", ")}; " +
                        "the py backend binds an extern to the function of its own name in the module's sidecar (X_ext.py beside X.kira), and takes raises = only"
                )
            }
        }
        if (mode == GeneratedProvider.OutputTarget.C || mode == GeneratedProvider.OutputTarget.JS) {
            val what = when {
                target is FunctionDecl && scope is SemanticScope.Class -> "the method '${nameOf(target)}'"
                target is FunctionDecl -> null
                target is ClassDecl -> "the class '${nameOf(target)}'"
                target is StructDecl -> "the struct '${nameOf(target)}'"
                target is VariableDecl -> "the constant '${nameOf(target)}'"
                else -> "'${nameOf(target)}'"
            }
            if (what != null) {
                throw KiraRuntimeException(
                    "@_extern on $what reaches C++ only (--target cpp, design 7.2): the ${mode.name} backend takes @_extern on a free function, whose symbol it calls"
                )
            }
            if (CPP in invocation.namedParameters && cSymbolOf(invocation) == null) {
                // `@_extern(cpp = "probe::twice", header = "probe.hxx")` names no C symbol; bound
                // to its Kira name, the C backend wrote `extern Int32 twice(Int32 n);` and called
                // it (measured), which links to nothing or to an unrelated C `twice`.
                throw KiraRuntimeException(
                    "@_extern on the function '${nameOf(target)}' names a C++ symbol only (cpp =); the ${mode.name} backend calls the C symbol: " +
                        "add c = \"name\" (or the positional symbol) beside it"
                )
            }
        }
        val kiraName = when (target) {
            is FunctionDecl -> (target.name as? Identifier)?.value
            is Identifier -> target.value
            else -> null
        } ?: return NoExpr
        compilationUnit.registerExternFunction(kiraName, cSymbolOf(invocation) ?: kiraName)
        return NoExpr
    }

    /** Whether the class or struct named [typeName] in [context] carries the intrinsic [marker] itself. */
    private fun isMarked(context: SourceContext, typeName: String, marker: String): Boolean {
        val marks = runCatching { context.astIntrinsicMarked }.getOrNull() ?: return false
        return marks.any { (node, intrinsics) ->
            val name = when (node) {
                is ClassDecl -> (node.name.identifier as? Identifier)?.value
                is StructDecl -> (node.name.identifier as? Identifier)?.value
                else -> null
            }
            name == typeName && intrinsics.any { it.name == marker }
        }
    }

    /**
     * The C symbol [invocation] names: the positional string, else `c =`; null when it names
     * neither (the Kira name is the symbol then). `cpp =` never names a C symbol.
     */
    fun cSymbolOf(invocation: IntrinsicExpr): String? {
        (invocation.parameters?.firstOrNull() as? StringLiteral)?.let { return it.value }
        return (invocation.namedParameters[C] as? StringLiteral)?.value
    }
}
