package net.exoad.kira.core.intrinsics

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
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
 * - `header =`, the C++ header the name is declared in, which the C++ backend includes.
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

    /** The named parameters `@_extern` accepts. */
    val namedParameters: Set<String> = setOf(CPP, C, HEADER)

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
                    "@_extern does not take '$name'; it takes cpp = (the C++ name), c = (the C symbol) and header = (the C++ header)"
                )
            }
            if (value !is StringLiteral) {
                throw KiraRuntimeException("@_extern's '$name' is a string literal, not ${value::class.simpleName}")
            }
        }
    }

    override fun apply(
        invocation: IntrinsicExpr,
        target: ASTNode,
        compilationUnit: CompilationUnit,
        context: SourceContext
    ): ASTNode {
        val kiraName = when (target) {
            is FunctionDecl -> (target.name as? Identifier)?.value
            is Identifier -> target.value
            else -> null
        } ?: return NoExpr
        compilationUnit.registerExternFunction(kiraName, cSymbolOf(invocation) ?: kiraName)
        return NoExpr
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
