package net.exoad.kira.core

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraRuntimeException
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.core.intrinsics.ConstIntrinsic
import net.exoad.kira.core.intrinsics.DeclIntrinsic
import net.exoad.kira.core.intrinsics.ExternIntrinsic
import net.exoad.kira.core.intrinsics.GlobalIntrinsic
import net.exoad.kira.core.intrinsics.MagicIntrinsic
import net.exoad.kira.core.intrinsics.OpaqueIntrinsic
import net.exoad.kira.core.intrinsics.StaticAssertIntrinsic
import net.exoad.kira.source.SourceContext

object IntrinsicRegistry {
    /** Intrinsic spellings that lower in codegen (print family), not markers. */
    private val codegenIntrinsicNames = listOf("_trace_")

    private val intrinsics: Map<String, CompilerIntrinsic> = buildMap {
        listOf(
            DeclIntrinsic,
            GlobalIntrinsic,
            MagicIntrinsic,
            OpaqueIntrinsic,
            ExternIntrinsic,
            // design D7: `@_const` marks a declaration, `@_static_assert` is
            // a module-level callable; checks beyond arity are wave 2's.
            ConstIntrinsic,
            StaticAssertIntrinsic,
            InferIntrinsic,
            MutatesIntrinsic,
            CallbackIntrinsic,
            IramIntrinsic,
            EnumIntrinsic,
        ).forEach { put(it.name, it) }
        // Operator intrinsics are known names the parser accepts as identifiers; they are not
        // markers. The member form (@_op_add_, ...) is 1.3.1's table, the one form; the free
        // form (@op_add, ...) stays registered for C and JS only (1.3.5).
        OperatorIntrinsics.all.forEach { put(it.name, it) }
        OperatorIntrinsics.allMembers.forEach { put(it.name, it) }
        // Codegen-backed spellings the docs expose with an @ prefix. These are
        // plain callables (handled by the backends, not markers); registering
        // them here lets `@_trace_(...)` parse as an intrinsic identifier.
        codegenIntrinsicNames.forEach { name ->
            put(name, codegenIntrinsic(name))
        }
    }

    private fun codegenIntrinsic(name: String): CompilerIntrinsic {
        return object : CompilerIntrinsic(name, emptySet()) {
            override fun validate(
                invocation: IntrinsicExpr,
                compilationUnit: CompilationUnit,
                context: SourceContext
            ) {
                // No marker semantics; the backends handle the call.
            }

            override fun apply(
                invocation: IntrinsicExpr,
                target: ASTNode,
                compilationUnit: CompilationUnit,
                context: SourceContext
            ): ASTNode = NoExpr
        }
    }

    fun find(name: String): CompilerIntrinsic? {
        return intrinsics[name]
    }

    /**
     * True when [name] is one of the declaration-marker intrinsics
     * (`@_magic`, `@_extern`, ...) that prefix a declaration. Callable
     * intrinsics (`@op_add`, `@_trace_`) are not markers and must be parsed
     * as expressions instead.
     */
    fun isDeclMarker(name: String): Boolean {
        return name in declMarkerNames
    }

    private val declMarkerNames = setOf(
        DeclIntrinsic.name,
        GlobalIntrinsic.name,
        MagicIntrinsic.name,
        OpaqueIntrinsic.name,
        ExternIntrinsic.name,
        ConstIntrinsic.name,
        InferIntrinsic.name,
        MutatesIntrinsic.name,
        CallbackIntrinsic.name,
        IramIntrinsic.name,
        EnumIntrinsic.name,
    )

    /**
     * `@_infer` (design D20): on a `@_magic` stdlib function, lets a call leave out the type
     * arguments, which the typer infers from the arguments (`sin(x)` with `x: Float32` is
     * `sin<Float32>`). Every other generic call names its type arguments. It takes no
     * arguments and changes nothing in the C and JS backends.
     */
    object InferIntrinsic : CompilerIntrinsic("_infer", setOf(FunctionDecl::class)) {
        override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
            if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
                throw KiraRuntimeException("@_infer takes no arguments (modifier like)")
            }
        }

        override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode = NoExpr
    }

    /**
     * `@_mutates` (stage 4): on a function the host supplies (a `@_magic`, `@_opaque` or `@_extern` type's), the
     * host's code changes the receiver (`List.add`); a function with a body says so by its body. No arguments.
     */
    /**
     * `@_callback` (extern callbacks): on an `@_extern` function, its `Fx` parameters are C callbacks: C++ declares them
     * as a function pointer, a std::function or both, so each is passed as a kira::ffi::FnArg, which converts to either.
     * Left off, an `Fx` parameter is a kira::Fn (std::function), which templates and overload sets rely on. No arguments.
     */
    /**
     * `@_iram` (interrupt handlers): on a function, its C++ prototype and definition carry the section attribute that
     * places its code in IRAM, so an interrupt can run while the flash cache is off (writes, erases). The function's
     * callees must be IRAM too; the compiler does not check that. The cpp target only; other targets ignore it.
     */
    object IramIntrinsic : CompilerIntrinsic("_iram", setOf(FunctionDecl::class)) {
        override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
            if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
                throw KiraRuntimeException("@_iram takes no arguments (modifier like)")
            }
        }

        override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode = NoExpr
    }

    /**
     * `@_enum` (extern enums): on an `@_extern` function, each named parameter is a C++ enum that takes a Kira
     * integer. Kira has no enum type, so the marker names the C++ one: `@_enum(mode = "ns::Mode")`. The call
     * converts the integer with an explicit cast; the cpp target only, other targets ignore it.
     */
    object EnumIntrinsic : CompilerIntrinsic("_enum", setOf(FunctionDecl::class)) {
        override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
            if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isEmpty()) {
                throw KiraRuntimeException("@_enum names the C++ enum of each parameter: @_enum(mode = \"ns::Mode\")")
            }
            invocation.namedParameters.forEach { (name, value) ->
                if (value !is StringLiteral) {
                    throw KiraRuntimeException("@_enum's '$name' is a string literal, not ${value::class.simpleName}")
                }
            }
        }

        override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode {
            val marks = runCatching { context.astIntrinsicMarked[target] }.getOrNull().orEmpty()
            if (marks.none { it.name == ExternIntrinsic.name }) {
                throw KiraRuntimeException("@_enum needs @_extern on the same function: it types the C++ enum of an extern's parameter")
            }
            return NoExpr
        }
    }

    object CallbackIntrinsic : CompilerIntrinsic("_callback", setOf(FunctionDecl::class)) {
        override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
            if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
                throw KiraRuntimeException("@_callback takes no arguments (modifier like)")
            }
        }

        override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode = NoExpr
    }

    object MutatesIntrinsic : CompilerIntrinsic("_mutates", setOf(FunctionDecl::class)) {
        override fun validate(invocation: IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {
            if (!invocation.parameters.isNullOrEmpty() || invocation.namedParameters.isNotEmpty()) {
                throw KiraRuntimeException("@_mutates takes no arguments (modifier like)")
            }
        }

        override fun apply(invocation: IntrinsicExpr, target: ASTNode, compilationUnit: CompilationUnit, context: SourceContext): ASTNode = NoExpr
    }
}
